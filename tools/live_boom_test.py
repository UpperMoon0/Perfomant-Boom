#!/usr/bin/env python3
"""Real dedicated-server + graphical-client verification for Perfomant Boom."""

from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

SERVER_READY = ("Done (", 'For help, type "help"')
SERVER_PASS = "PERFOMANT_BOOM_E2E_SERVER_PASS"
SERVER_FAIL = "PERFOMANT_BOOM_E2E_SERVER_FAIL"
CLIENT_PASS = "PERFOMANT_BOOM_E2E_CLIENT_PASS"
CLIENT_FAIL = "PERFOMANT_BOOM_E2E_CLIENT_FAIL"
FATAL = (
    "Encountered an unexpected exception",
    "This crash report has been saved to:",
    "Failed to start the minecraft server",
    "Critical injection failure",
    "BUILD FAILED",
)
DEFAULT_TIMEOUT = 300


class OutputPump:
    def __init__(self, process, prefix: str, log_path: Path):
        self.process = process
        self.prefix = prefix
        self.log_path = log_path
        self.lines: queue.Queue[str] = queue.Queue()
        self.history: list[str] = []
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self._log = log_path.open("w", encoding="utf-8", errors="replace")
        self._thread = threading.Thread(target=self._pump, daemon=True)
        self._thread.start()

    def _pump(self):
        stream = self.process.stdout
        if stream is None:
            return
        for line in stream:
            self.history.append(line)
            self.lines.put(line)
            self._log.write(line)
            self._log.flush()
            print(f"[{self.prefix}] {line}", end="", flush=True)
        self._log.flush()

    def wait_for_any(self, markers, timeout: float, fail_markers=()):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for line in self.history:
                if any(m in line for m in fail_markers):
                    raise RuntimeError(f"{self.prefix}: fatal output: {line.rstrip()}")
                for marker in markers:
                    if marker in line:
                        return line
            if self.process.poll() is not None:
                tail = "".join(self.history[-40:])
                raise RuntimeError(
                    f"{self.prefix}: process exited {self.process.returncode} before marker\n{tail}"
                )
            try:
                line = self.lines.get(timeout=0.2)
            except queue.Empty:
                continue
            if any(m in line for m in fail_markers):
                raise RuntimeError(f"{self.prefix}: fatal output: {line.rstrip()}")
            for marker in markers:
                if marker in line:
                    return line
        raise RuntimeError(f"{self.prefix}: timed out waiting for {markers}")


class InteractiveLogStream:
    def __init__(self, process, path: Path):
        self.process = process
        self.path = path

    def __iter__(self):
        position = 0
        while True:
            if self.path.exists():
                with self.path.open("r", encoding="utf-8", errors="replace") as f:
                    f.seek(position)
                    while True:
                        line = f.readline()
                        if line:
                            position = f.tell()
                            yield line
                        elif self.process.poll() is not None:
                            return
                        else:
                            time.sleep(0.1)
            elif self.process.poll() is not None:
                return
            else:
                time.sleep(0.1)


class WindowsInteractiveProcess:
    stdin = None

    def __init__(self, pid: int, handle: int, log_path: Path):
        self.pid = pid
        self._handle = handle
        self.returncode = None
        self.stdout = InteractiveLogStream(self, log_path)

    def poll(self):
        if self.returncode is not None:
            return self.returncode
        code = wintypes.DWORD()
        if not ctypes.windll.kernel32.GetExitCodeProcess(self._handle, ctypes.byref(code)):
            raise ctypes.WinError()
        if code.value == 259:
            return None
        self.returncode = code.value
        ctypes.windll.kernel32.CloseHandle(self._handle)
        self._handle = 0
        return self.returncode

    def wait(self, timeout=None):
        deadline = None if timeout is None else time.monotonic() + timeout
        while True:
            value = self.poll()
            if value is not None:
                return value
            if deadline is not None and time.monotonic() >= deadline:
                raise subprocess.TimeoutExpired("interactive-client", timeout)
            time.sleep(0.1)


def current_and_interactive_session() -> tuple[int, int]:
    class WTS_SESSION_INFOW(ctypes.Structure):
        _fields_ = [
            ("SessionId", wintypes.DWORD),
            ("pWinStationName", wintypes.LPWSTR),
            ("State", ctypes.c_int),
        ]

    current = wintypes.DWORD()
    if not ctypes.windll.kernel32.ProcessIdToSessionId(os.getpid(), ctypes.byref(current)):
        raise ctypes.WinError()

    buffer = ctypes.POINTER(WTS_SESSION_INFOW)()
    count = wintypes.DWORD()
    if not ctypes.windll.wtsapi32.WTSEnumerateSessionsW(
        None, 0, 1, ctypes.byref(buffer), ctypes.byref(count)
    ):
        raise ctypes.WinError()

    candidates = []
    try:
        for i in range(count.value):
            info = buffer[i]
            username_buffer = wintypes.LPWSTR()
            username_bytes = wintypes.DWORD()
            username = ""
            if ctypes.windll.wtsapi32.WTSQuerySessionInformationW(
                None, info.SessionId, 5, ctypes.byref(username_buffer), ctypes.byref(username_bytes)
            ):
                try:
                    username = username_buffer.value or ""
                finally:
                    ctypes.windll.wtsapi32.WTSFreeMemory(username_buffer)
            if info.State == 0 and username.strip():
                candidates.append(info.SessionId)
    finally:
        ctypes.windll.wtsapi32.WTSFreeMemory(buffer)

    active_console = ctypes.windll.kernel32.WTSGetActiveConsoleSessionId()
    if current.value in candidates:
        target = current.value
    elif active_console in candidates:
        target = active_console
    elif candidates:
        target = min(candidates)
    else:
        raise RuntimeError("graphical verification requires an active logged-in Windows session")
    return current.value, target


def command(root: Path, task: str, skip_common_rebuild: bool = False) -> list[str]:
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    args = [
        str(wrapper),
        task,
        "--no-daemon",
        "--console=plain",
        "--max-workers=4",
        "-Dorg.gradle.jvmargs=-Xmx1280m",
    ]
    if skip_common_rebuild:
        args += [
            "-x", ":common:compileJava",
            "-x", ":common:processResources",
            "-x", ":common:classes",
            "-x", ":common:jar",
        ]
    return args


def popen(cmd, root: Path, env: dict[str, str]):
    kwargs = dict(
        cwd=root,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env=env,
    )
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(cmd, **kwargs)


def launch_windows_interactive(
    cmd: list[str], root: Path, env: dict[str, str], target_session: int, log_path: Path
):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.unlink(missing_ok=True)
    wrapper = log_path.with_name(log_path.stem + "-session.cmd")
    lines = ["@echo off", f'cd /d "{root}"']
    for key, value in sorted(env.items()):
        if key.startswith("PERFOMANT_BOOM_") or key in ("JAVA_HOME", "GRADLE_USER_HOME"):
            lines.append(f'set "{key}={value.replace("%", "%%")}"')
    if env.get("JAVA_HOME"):
        lines.append('set "PATH=%JAVA_HOME%\\bin;%PATH%"')
    lines.append(f'call {subprocess.list2cmdline(cmd)} > "{log_path}" 2>&1')
    lines.append("exit /b %ERRORLEVEL%")
    wrapper.write_text("\r\n".join(lines) + "\r\n", encoding="utf-8")

    CREATE_NEW_PROCESS_GROUP = 0x00000200
    CREATE_UNICODE_ENVIRONMENT = 0x00000400

    class STARTUPINFOW(ctypes.Structure):
        _fields_ = [
            ("cb", wintypes.DWORD), ("lpReserved", wintypes.LPWSTR),
            ("lpDesktop", wintypes.LPWSTR), ("lpTitle", wintypes.LPWSTR),
            ("dwX", wintypes.DWORD), ("dwY", wintypes.DWORD),
            ("dwXSize", wintypes.DWORD), ("dwYSize", wintypes.DWORD),
            ("dwXCountChars", wintypes.DWORD), ("dwYCountChars", wintypes.DWORD),
            ("dwFillAttribute", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
            ("wShowWindow", wintypes.WORD), ("cbReserved2", wintypes.WORD),
            ("lpReserved2", ctypes.POINTER(ctypes.c_byte)),
            ("hStdInput", wintypes.HANDLE), ("hStdOutput", wintypes.HANDLE),
            ("hStdError", wintypes.HANDLE),
        ]

    class PROCESS_INFORMATION(ctypes.Structure):
        _fields_ = [
            ("hProcess", wintypes.HANDLE), ("hThread", wintypes.HANDLE),
            ("dwProcessId", wintypes.DWORD), ("dwThreadId", wintypes.DWORD),
        ]

    token = wintypes.HANDLE()
    env_block = ctypes.c_void_p()
    wtsapi32 = ctypes.windll.wtsapi32
    userenv = ctypes.windll.userenv
    advapi32 = ctypes.windll.advapi32
    kernel32 = ctypes.windll.kernel32

    if not wtsapi32.WTSQueryUserToken(target_session, ctypes.byref(token)):
        raise ctypes.WinError()
    try:
        if not userenv.CreateEnvironmentBlock(ctypes.byref(env_block), token, False):
            raise ctypes.WinError()
        try:
            startup = STARTUPINFOW()
            startup.cb = ctypes.sizeof(startup)
            startup.lpDesktop = "winsta0\\default"
            proc = PROCESS_INFORMATION()
            comspec = os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe")
            cmdline = ctypes.create_unicode_buffer(
                f'{subprocess.list2cmdline([comspec])} /d /s /c ""{wrapper}""'
            )
            if not advapi32.CreateProcessAsUserW(
                token, comspec, cmdline, None, None, False,
                CREATE_NEW_PROCESS_GROUP | CREATE_UNICODE_ENVIRONMENT,
                env_block, str(root), ctypes.byref(startup), ctypes.byref(proc)
            ):
                raise ctypes.WinError()
            kernel32.CloseHandle(proc.hThread)
            return WindowsInteractiveProcess(proc.dwProcessId, proc.hProcess, log_path)
        finally:
            userenv.DestroyEnvironmentBlock(env_block)
    finally:
        kernel32.CloseHandle(token)


def launch_client(cmd, root: Path, env, log_path: Path):
    if os.name == "nt":
        current, target = current_and_interactive_session()
        if current != target:
            process = launch_windows_interactive(cmd, root, env, target, log_path)
            return process, OutputPump(process, "client", log_path.with_suffix(".pump.log"))
    elif not os.environ.get("DISPLAY"):
        xvfb = shutil.which("xvfb-run")
        if not xvfb:
            raise RuntimeError("DISPLAY is unset and xvfb-run is unavailable")
        cmd = [xvfb, "-a", *cmd]
    process = popen(cmd, root, env)
    return process, OutputPump(process, "client", log_path)


def stop_tree(process, graceful_server=False):
    if process is None or process.poll() is not None:
        return
    if graceful_server and getattr(process, "stdin", None) is not None:
        try:
            process.stdin.write("stop\n")
            process.stdin.flush()
            process.wait(timeout=15)
            return
        except (BrokenPipeError, subprocess.TimeoutExpired):
            pass
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False
        )
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=10)
        except Exception:
            pass


def prepare_server(root: Path, loader: str, port: int, run_id: str):
    shutil.rmtree(root / loader / ".gradle" / "architectury", ignore_errors=True)
    run_dir = root / loader / "run" / "live-boom" / run_id / "server"
    run_dir.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(run_dir / "world", ignore_errors=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text(
        "\n".join([
            f"server-port={port}",
            "online-mode=false",
            "level-name=world",
            "level-seed=perfomant-boom-e2e",
            "level-type=minecraft:flat",
            "gamemode=creative",
            "difficulty=peaceful",
            "spawn-protection=0",
            "view-distance=10",
            "simulation-distance=5",
            "max-tick-time=-1",
            "generate-structures=false",
            "sync-chunk-writes=true",
            "enable-command-block=true",
            "motd=Perfomant Boom E2E",
            "",
        ]),
        encoding="utf-8",
    )


def parse_fields(line: str) -> dict[str, object]:
    result: dict[str, object] = {}
    parts = line.strip().split()
    for token in parts[1:]:
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        try:
            result[key] = float(value) if "." in value else int(value)
        except ValueError:
            result[key] = value
    return result


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def run(loader: str, timeout: int) -> int:
    root = Path(__file__).resolve().parents[1]
    lock_path = root / "build" / "live-boom.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        lock_fd = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        owner = lock_path.read_text(encoding="utf-8", errors="replace").strip()
        print(f"PERFOMANT_BOOM_LIVE_TEST_FAIL another live benchmark owns {lock_path}: {owner}", file=sys.stderr)
        return 2

    os.write(lock_fd, f"pid={os.getpid()} loader={loader}\n".encode("utf-8"))
    port = find_free_port()
    run_id = f"{int(time.time() * 1000)}-{os.getpid()}"
    prepare_server(root, loader, port, run_id)

    evidence = root / "build" / "live-boom-evidence" / loader
    shutil.rmtree(evidence, ignore_errors=True)
    evidence.mkdir(parents=True, exist_ok=True)

    env = os.environ.copy()
    env["PERFOMANT_BOOM_TEST_PORT"] = str(port)
    env["PERFOMANT_BOOM_TEST_RUN"] = run_id

    server = None
    client = None
    started = time.monotonic()
    try:
        server = popen(command(root, f":{loader}:runLiveBoomTestServer"), root, env)
        server_out = OutputPump(server, "server", evidence / "server.log")
        server_out.wait_for_any(SERVER_READY, timeout, FATAL + (SERVER_FAIL,))

        client, client_out = launch_client(
            command(root, f":{loader}:runLiveBoomTestClient", skip_common_rebuild=True),
            root, env, evidence / "client.log"
        )

        server_line = server_out.wait_for_any((SERVER_PASS,), timeout, FATAL + (SERVER_FAIL,))
        client_line = client_out.wait_for_any((CLIENT_PASS,), timeout, FATAL + (CLIENT_FAIL,))

        result = {
            "loader": loader,
            "run_id": run_id,
            "port": port,
            "elapsed_seconds": round(time.monotonic() - started, 3),
            "server": parse_fields(server_line),
            "client": parse_fields(client_line),
            "git_head": subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=root, text=True
            ).strip(),
            "git_dirty": bool(subprocess.check_output(
                ["git", "status", "--porcelain"], cwd=root, text=True
            ).strip()),
        }
        (evidence / "result.json").write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        print("PERFOMANT_BOOM_LIVE_TEST_PASS " + json.dumps(result, sort_keys=True))
        return 0
    except Exception as exc:
        result = {
            "loader": loader,
            "run_id": run_id,
            "port": port,
            "elapsed_seconds": round(time.monotonic() - started, 3),
            "error": str(exc),
        }
        (evidence / "result.json").write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        print(f"PERFOMANT_BOOM_LIVE_TEST_FAIL {exc}", file=sys.stderr)
        return 1
    finally:
        if client is not None:
            stop_tree(client)
        if server is not None:
            stop_tree(server, graceful_server=False)
        os.close(lock_fd)
        lock_path.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--loader", choices=("fabric", "forge"), default="fabric")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    args = parser.parse_args()
    return run(args.loader, args.timeout)


if __name__ == "__main__":
    raise SystemExit(main())
