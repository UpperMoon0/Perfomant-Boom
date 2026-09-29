#!/usr/bin/env python3
"""Real dedicated-server + graphical-client verification for Perfomant Boom."""

from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import json
import hashlib
import statistics
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
    "Mod Loading has failed",
    "Mod loading error has occurred",
    "Cowardly refusing to send event",
    "redefining classes",
    "Changes detected",
)
DEFAULT_TIMEOUT = 900
GUARD = lambda: None


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
            GUARD()
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
        if not ctypes.windll.kernel32.GetExitCodeProcess(wintypes.HANDLE(self._handle), ctypes.byref(code)):
            raise ctypes.WinError()
        if code.value == 259:
            return None
        self.returncode = code.value
        ctypes.windll.kernel32.CloseHandle(wintypes.HANDLE(self._handle))
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


def command(root: Path, loader: str) -> list[str]:
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [str(wrapper), f":{loader}:runLiveBoomTestServer", f":{loader}:runLiveBoomTestClient",
            "-I", str(root / "tools" / "export_live_launch.gradle"), "--no-daemon", "--console=plain",
            "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx1280m"]


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


def interactive_environment(env: dict[str, str]) -> dict[str, str]:
    # Only launch inputs cross the service/desktop boundary; never serialize the
    # shell's credentials into an evidence command file. Forge needs both userdev keys.
    allowed={"JAVA_HOME", "GRADLE_USER_HOME", "MOD_CLASSES", "MCP_MAPPINGS"}
    return {key: value for key, value in env.items()
            if key.startswith("PERFOMANT_BOOM_") or key in allowed}


def launch_windows_interactive(
    cmd: list[str], root: Path, env: dict[str, str], target_session: int, log_path: Path
):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.unlink(missing_ok=True)
    wrapper = log_path.with_name(log_path.stem + "-session.cmd")
    lines = ["@echo off", f'cd /d "{root}"']
    for key, value in sorted(interactive_environment(env).items()):
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
    run_dir = root / loader / "run" / "live-boom" / run_id / "server"
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text(
        "\n".join([
            f"server-port={port}",
            "server-ip=127.0.0.1",
            "online-mode=false",
            "level-name=world",
            "level-seed=perfomant-boom-e2e",
            "level-type=minecraft:flat",
            'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]}',
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


class FrozenInputs:
    """Reject any source, compiled-class, development-JAR or launch-config drift.

    Hashes are retained in evidence. Runtime polling compares stat identities; the
    final digest pass also catches same-size modifications and timestamp restoration.
    """
    def __init__(self, root: Path, extra_paths=()):
        self.root = root
        self.extra_paths = tuple(extra_paths)
        self.paths = self.inventory()
        self.stats = self.stat_all()
        self.hashes = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.paths}

    def inventory(self):
        paths = set(self.extra_paths) | set(p for p in self.root.iterdir() if p.is_file() and p.name != '.git')
        for directory in ('tools', '.github', 'gradle', 'common/src', 'fabric/src', 'forge/src',
                          'common/build/classes', 'common/build/resources', 'common/build/devlibs', 'common/build/libs',
                          '.gradle/architectury', 'fabric/.gradle/architectury', 'forge/.gradle/architectury',
                          'fabric/build/classes', 'fabric/build/resources', 'fabric/build/devlibs', 'fabric/build/libs',
                          'forge/build/classes', 'forge/build/resources', 'forge/build/devlibs', 'forge/build/libs'):
            for p in (self.root / directory).rglob('*'):
                if p.is_file() and '__pycache__' not in p.parts:
                    paths.add(p)
        return sorted(paths)

    def stat_all(self):
        return {p: (p.stat().st_size, p.stat().st_mtime_ns) for p in self.paths}

    def check(self):
        if self.inventory() != self.paths or self.stat_all() != self.stats:
            raise RuntimeError('Source or runtime artifacts changed during the frozen run')

    def verify_hashes(self):
        self.check()
        for p in self.paths:
            if hashlib.sha256(p.read_bytes()).hexdigest() != self.hashes[str(p.relative_to(self.root))]:
                raise RuntimeError(f'Content drift during the frozen run: {p}')


def validate_launch(spec: dict) -> None:
    """Reject malformed exports before starting either process (not after server startup)."""
    args=spec.get('command')
    if not isinstance(args,list) or not args or any(not isinstance(a,str) or not a for a in args):
        raise ValueError('Launch command contains an absent executable or argument')
    main='dev.architectury.transformer.TransformerRuntime'
    if main not in args or not isinstance(spec.get('cwd'),str) or not isinstance(spec.get('environment'),dict):
        raise ValueError('Unsupported or incomplete Loom launch description')
    pairs={'--add-opens','--add-exports','--add-reads','--add-modules','--limit-modules',
           '--patch-module','--module-path','-p','--upgrade-module-path','-cp','-classpath','--class-path'}
    i=1
    while i<args.index(main):
        arg=args[i]
        if arg in pairs:
            if i+1>=args.index(main) or args[i+1].startswith('-'):
                raise ValueError(f'JVM option is missing its value: {arg}')
            i+=2
        elif arg.startswith(('-', '@')):
            i+=1
        else:
            raise ValueError(f'Unpaired JVM option value would be interpreted as a main class: {arg}')


def assert_successful_exit(process, pump: OutputPump, timeout: float):
    deadline = time.monotonic() + timeout
    while process.poll() is None:
        GUARD()
        if time.monotonic() > deadline:
            raise RuntimeError(f'{pump.prefix}: gameplay marker received but clean shutdown timed out')
        time.sleep(0.25)
    pump._thread.join(timeout=10)
    if pump._thread.is_alive():
        raise RuntimeError(f'{pump.prefix}: log stream did not drain')
    if process.returncode != 0:
        raise RuntimeError(f'{pump.prefix}: nonzero exit {process.returncode} after gameplay')
    for line in pump.history:
        if any(marker in line for marker in FATAL + (SERVER_FAIL, CLIENT_FAIL)):
            raise RuntimeError(f'{pump.prefix}: fatal output even though a PASS marker appeared: {line.strip()}')


def summarize(evidence: Path) -> list[dict]:
    samples = json.loads((evidence / 'server-samples.json').read_text(encoding='utf-8'))
    if len(samples) != 34:
        raise ValueError(f'Expected 34 raw server samples, found {len(samples)}')
    grouped = {}
    for sample in samples:
        t = sample['trial']
        client = json.loads((evidence / f"client-sample-{t['index']:02d}.json").read_text(encoding='utf-8'))
        if client['actual'] != sample['authoritative'] or client['postConvergenceFrames'] < 2:
            raise ValueError('Missing exact real-client state/render evidence')
        if t['warmup']:
            continue
        key = (t['scenario'], t['fast'])
        grouped.setdefault(key, []).append((sample, client))
    summary = []
    for (scenario, fast), values in grouped.items():
        row = {'scenario': scenario, 'engine': 'fast' if fast else 'vanilla', 'samples': len(values)}
        for field in ('activeWorkMs', 'maxObservedServerTickMs', 'clientAcknowledgedWallMs'):
            raw = sorted(v[0][field] for v in values)
            row[field] = {'median': statistics.median(raw), 'min': min(raw), 'max': max(raw),
                          'q1': raw[(len(raw)-1)//4], 'q3': raw[(3*(len(raw)-1)+3)//4]}
        row['maxObservedFrameGapMs'] = max(v[1]['maxObservedFrameGapMs'] for v in values)
        row['medianChangedBlocks'] = statistics.median(v[0]['authoritative']['air'] for v in values)
        summary.append(row)
    return summary


def verify_lifecycle_evidence(evidence: Path, token: str) -> None:
    ray = json.loads((evidence/'lifecycle-ray-unload.json').read_text(encoding='utf-8'))
    mutation = json.loads((evidence/'lifecycle-mutation-unload.json').read_text(encoding='utf-8'))
    partial = json.loads((evidence/'lifecycle-partial.json').read_text(encoding='utf-8'))
    reload = json.loads((evidence/'lifecycle-persistence.json').read_text(encoding='utf-8'))
    if any(item.get('token') != token for item in (ray, mutation, partial, reload)):
        raise ValueError('Lifecycle evidence belongs to a different run')
    if not (ray.get('distinctChunk') is True and ray['selectedBefore'] > 0
            and ray['selectedBefore'] == ray['selectedAfter']):
        raise ValueError('Missing real ray chunk replacement evidence')
    if not (all(mutation.get(key) is True for key in ('distinctChunk', 'savedPrefixMatched', 'detachedUnchanged'))
            and 0 < mutation['changedBefore'] < mutation['changedAfter']):
        raise ValueError('Missing real mutation chunk unload/reload evidence')
    if not (0 < partial['processed'] < partial['total'] and partial['changed'] > 0
            and all(reload.get(key) is True for key in ('partialMatched', 'vanillaBlockLightMatched', 'metadataMatched'))
            and all(reload[key] == partial[key] for key in ('processed', 'total', 'changed'))):
        raise ValueError('Missing partial-mutation shutdown/persistence evidence')


def run(loader: str, timeout: int, require_clean: bool = False) -> int:
    global GUARD
    root = Path(__file__).resolve().parents[1]
    lock_path = root / 'build' / 'live-boom.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        lock_fd = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        print(f'PERFOMANT_BOOM_LIVE_TEST_FAIL checkout already locked: {lock_path}', file=sys.stderr)
        return 2
    os.write(lock_fd, f'pid={os.getpid()} loader={loader}\n'.encode())
    run_id = f'{int(time.time()*1000)}-{os.getpid()}'
    evidence = root / 'build' / 'live-boom-evidence' / loader / run_id
    evidence.mkdir(parents=True, exist_ok=False)
    processes = []
    result = {'loader': loader, 'run_id': run_id, 'gameplay_pass': False, 'clean_shutdown': False,
              'persistence_pass': False, 'lifecycle_pass': False, 'source_unchanged': False, 'forced_cleanup': False}
    started = time.monotonic()
    try:
        result['git_head'] = subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
        result['git_dirty'] = bool(subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip())
        if require_clean and result['git_dirty']:
            raise RuntimeError('Release verification requires a clean checkout')
        port = find_free_port()
        result['port'] = port
        env = os.environ.copy()
        env.update(PERFOMANT_BOOM_TEST_PORT=str(port), PERFOMANT_BOOM_TEST_RUN=run_id,
                   PERFOMANT_BOOM_EVIDENCE=str(evidence))
        prepare_server(root, loader, port, run_id)
        client_dir = root / loader / 'run' / 'live-boom' / run_id / 'client'
        client_dir.mkdir(parents=True, exist_ok=False)
        (client_dir / 'options.txt').write_text('renderDistance:10\nmaxFps:60\ngraphicsMode:0\npauseOnLostFocus:false\n',encoding='utf-8')
        # Finish BOTH launch task dependencies before either Minecraft JVM starts.
        with (evidence / 'prepare.log').open('w', encoding='utf-8') as log:
            prep = popen(command(root, loader), root, env)
            processes.append(prep)
            pump = OutputPump(prep, 'prepare', evidence / 'gradle.log')
            assert_successful_exit(prep, pump, max(900, timeout))
        specs = {side: json.loads((evidence/f'{side}-launch.json').read_text(encoding='utf-8')) for side in ('server','client')}
        for spec in specs.values():
            validate_launch(spec)
        launch_files = [p for p in (evidence/'frozen-launch').rglob('*') if p.is_file()]
        launch_files += list((root/loader/'.gradle/loom-cache').glob('*.cfg'))
        frozen = FrozenInputs(root, launch_files)
        (evidence/'frozen-inputs.json').write_text(json.dumps(frozen.hashes,indent=2)+'\n',encoding='utf-8')
        GUARD = frozen.check
        server_spec = specs['server']
        server = popen(server_spec['command'], Path(server_spec['cwd']), env | server_spec['environment'])
        processes.append(server)
        server_out = OutputPump(server, 'server', evidence/'server.log')
        server_out.wait_for_any(SERVER_READY, timeout, FATAL+(SERVER_FAIL,))
        client_spec = specs['client']
        client, client_out = launch_client(client_spec['command'],Path(client_spec['cwd']),
                                          env | client_spec['environment'],evidence/'client.log')
        processes.append(client)
        def guard_peers():
            frozen.check()
            for peer in (server_out, client_out):
                for line in peer.history:
                    if any(marker in line for marker in FATAL+(SERVER_FAIL,CLIENT_FAIL,"Kicked for spamming")):
                        raise RuntimeError(f'{peer.prefix}: {line.strip()}')
        GUARD = guard_peers
        client_out.wait_for_any((CLIENT_PASS,), timeout, FATAL+(CLIENT_FAIL,))
        server_out.wait_for_any((SERVER_PASS,), timeout, FATAL+(SERVER_FAIL,))
        result['gameplay_pass'] = True
        assert_successful_exit(client,client_out,120)
        assert_successful_exit(server,server_out,120)
        result['clean_shutdown'] = True
        # Relaunch the same saved world in a FRESH server process; do not regenerate fixtures.
        reload_env = env | server_spec['environment'] | {'PERFOMANT_BOOM_RELOAD': '1'}
        reload_server = popen(server_spec['command'], Path(server_spec['cwd']),reload_env)
        processes.append(reload_server)
        reload_out = OutputPump(reload_server,'reload',evidence/'reload-server.log')
        reload_out.wait_for_any(('PERFOMANT_BOOM_E2E_PERSISTENCE_PASS',),timeout,FATAL+(SERVER_FAIL,))
        assert_successful_exit(reload_server,reload_out,120)
        result['persistence_pass'] = True
        # Real chunk unload/replacement, then an immediate partial-slice normal stop
        # and a second fresh JVM. No graphical client participates in these phases.
        for mode, marker in (('exercise', 'PERFOMANT_BOOM_LIFECYCLE_PARTIAL_STOP_PASS'),
                             ('reload', 'PERFOMANT_BOOM_LIFECYCLE_RELOAD_PASS')):
            lifecycle_env = env | server_spec['environment'] | {'PERFOMANT_BOOM_LIFECYCLE': mode}
            lifecycle = popen(server_spec['command'], Path(server_spec['cwd']), lifecycle_env)
            processes.append(lifecycle)
            lifecycle_out = OutputPump(lifecycle, 'lifecycle-'+mode, evidence/f'lifecycle-{mode}.log')
            lifecycle_out.wait_for_any((marker,), timeout, FATAL+(SERVER_FAIL,))
            assert_successful_exit(lifecycle, lifecycle_out, 120)
        verify_lifecycle_evidence(evidence, run_id)
        result['lifecycle_pass'] = True
        frozen.verify_hashes()
        result['source_unchanged'] = True
        result['summary'] = summarize(evidence)
        result['pass'] = True
    except Exception as exc:
        result['pass'] = False
        result['error'] = str(exc)
    finally:
        GUARD = lambda: None
        for process in reversed(processes):
            if process.poll() is None:
                result['forced_cleanup'] = True
                result['pass'] = False
                stop_tree(process)
        result['elapsed_seconds'] = round(time.monotonic()-started,3)
        (evidence/'result.json').write_text(json.dumps(result,indent=2,sort_keys=True)+'\n',encoding='utf-8')
        os.close(lock_fd)
        lock_path.unlink(missing_ok=True)
    marker = 'PASS' if result.get('pass') else 'FAIL'
    print(f'PERFOMANT_BOOM_LIVE_TEST_{marker} '+json.dumps(result,sort_keys=True),flush=True)
    print(f'Evidence: {evidence}',flush=True)
    return 0 if result.get('pass') else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--loader',choices=('fabric','forge'),default='fabric')
    parser.add_argument('--timeout',type=int,default=DEFAULT_TIMEOUT)
    parser.add_argument('--require-clean',action='store_true')
    args=parser.parse_args()
    if args.timeout <= 0:
        parser.error('--timeout must be positive')
    return run(args.loader,args.timeout,args.require_clean)


if __name__ == '__main__':
    raise SystemExit(main())
