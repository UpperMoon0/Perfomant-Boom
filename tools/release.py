#!/usr/bin/env python3
"""Version-driven release planning and exact, verified loader artifacts (Python 3.11+)."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib
import zipfile

LOADERS = ("fabric", "forge")
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")
SHA = re.compile(r"[0-9a-f]{40}\Z")


def properties(text: str) -> dict[str, str]:
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        if key in result:
            raise ValueError(f"Duplicate property: {key}")
        result[key] = value
    return result


def version_tuple(value: str) -> tuple[int, int, int]:
    match = VERSION.fullmatch(value)
    if not match:
        raise ValueError(f"Expected a stable major.minor.patch version, got {value!r}")
    return tuple(int(part) for part in match.groups())


def config(root: Path) -> dict[str, str]:
    data = properties((root / "gradle.properties").read_text(encoding="utf-8-sig"))
    version_tuple(data["mod_version"])
    if data["archives_name"] != "perfomant_boom" or data["minecraft_version"] != "1.20.1":
        raise ValueError("Update the release target matrix before changing archives_name or Minecraft version")
    return data


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True, stderr=subprocess.PIPE).strip()


def head_sha(root: Path) -> str:
    return git(root, "rev-parse", "HEAD")


def tag_commit(root: Path, tag: str) -> str | None:
    result = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", f"refs/tags/{tag}"], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    if result.returncode == 1:
        return None
    if result.returncode != 0:
        raise ValueError(f"Unable to inspect tag {tag}: {result.stderr}")
    return git(root, "rev-parse", f"refs/tags/{tag}^{{commit}}")


def changelog(root: Path, version: str) -> Path:
    path = root / "changelog" / f"{version}.md"
    if not path.is_file() or not path.read_text(encoding="utf-8").strip():
        raise ValueError(f"Missing or empty release changelog: {path}")
    return path


def guard_release_history(root: Path, version: str) -> None:
    """All publication paths require the complete fetched stable-version tag history.

    Inspect every vMAJOR.MINOR.PATCH tag, including annotated tags and tags not
    reachable from HEAD. A repair push's parent is not a release-version floor.
    """
    if git(root, "rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Release history is shallow; fetch full history and tags before publication")
    versions = [tag[1:] for tag in git(root, "tag", "--list", "v*").splitlines()
                if VERSION.fullmatch(tag[1:])]
    if versions:
        highest = max(versions, key=version_tuple)
        if version_tuple(version) < version_tuple(highest):
            raise ValueError(f"Refusing a version downgrade below release history: {highest} -> {version}")


def guard_tag(root: Path, version: str) -> None:
    guard_release_history(root, version)
    tagged = tag_commit(root, f"v{version}")
    if tagged and tagged != head_sha(root):
        raise ValueError(f"v{version} already belongs to {tagged}; bump mod_version, never move an existing tag")


def plan(root: Path, event: str, before: str, ref: str) -> dict[str, str]:
    if ref != "refs/heads/main" or event not in ("push", "workflow_dispatch"):
        raise ValueError("Releases are allowed only for main push/manual events")
    cfg = config(root)
    current = cfg["mod_version"]
    guard_release_history(root, current)
    previous = None
    if before and before != "0" * 40:
        if not SHA.fullmatch(before):
            raise ValueError("Invalid previous commit SHA")
        previous = properties(git(root, "show", f"{before}:gradle.properties"))["mod_version"]
        if version_tuple(current) < version_tuple(previous):
            raise ValueError(f"Refusing a version downgrade: {previous} -> {current}")
    tagged = tag_commit(root, f"v{current}")
    release = event == "workflow_dispatch" or previous != current or tagged is None
    if release:
        changelog(root, current)
        guard_tag(root, current)
    return {
        "release": str(release).lower(), "version": current, "tag": f"v{current}",
        "changelog": f"changelog/{current}.md", "sha": head_sha(root),
    }


def inspect_jar(path: Path, loader: str, version: str) -> None:
    with zipfile.ZipFile(path) as jar:
        if "com/nstut/explosion/FastExplosionEngine.class" not in jar.namelist():
            raise ValueError(f"Missing shared explosion engine in {path}")
        if loader == "fabric":
            metadata = json.loads(jar.read("fabric.mod.json"))
            mod_id, actual = metadata["id"], metadata["version"]
        else:
            metadata = tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8"))
            mod_id, actual = metadata["mods"][0]["modId"], metadata["mods"][0]["version"]
        if (mod_id, actual) != ("perfomant_boom", version):
            raise ValueError(f"Wrong {loader} JAR metadata: {mod_id} {actual}; expected {version}")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def jar_name(loader: str, version: str) -> str:
    return f"perfomant_boom-{loader}-{version}.jar"


def package(root: Path, output: Path) -> None:
    cfg = config(root)
    version = cfg["mod_version"]
    changelog(root, version)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError(f"Release staging directory must be empty: {output}")
    # Validate both outputs before copying either; never publish a partial loader set.
    jars = []
    for loader in LOADERS:
        directory = root / loader / "build" / "libs"
        candidates = sorted(p for p in directory.glob("*.jar") if not p.name.endswith(
            ("-sources.jar", "-dev.jar", "-dev-shadow.jar", "-javadoc.jar")))
        expected = directory / jar_name(loader, version)
        if candidates != [expected]:
            raise ValueError(f"Expected exactly {expected}; found {[p.name for p in candidates]}")
        inspect_jar(expected, loader, version)
        jars.append((loader, expected))
    files = []
    for loader, source in jars:
        destination = output / source.name
        shutil.copyfile(source, destination)
        files.append({"loader": loader, "name": source.name, "sha256": digest(destination)})
    manifest = {"source_sha": head_sha(root), "version": version,
                "minecraft_version": cfg["minecraft_version"], "files": files}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS").write_text(
        "".join(f"{item['sha256']}  {item['name']}\n" for item in files), encoding="utf-8")


def verify(root: Path, directory: Path) -> None:
    cfg = config(root)
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if (manifest["source_sha"], manifest["version"], manifest["minecraft_version"]) != (
        head_sha(root), cfg["mod_version"], cfg["minecraft_version"]
    ):
        raise ValueError("Artifact provenance does not match this exact source commit/version")
    files = manifest["files"]
    expected_names = [jar_name(loader, cfg["mod_version"]) for loader in LOADERS]
    if [entry["name"] for entry in files] != expected_names or [entry["loader"] for entry in files] != list(LOADERS):
        raise ValueError("Manifest does not describe exactly the supported Fabric and Forge artifacts")
    if sorted(p.name for p in directory.glob("*.jar")) != sorted(expected_names):
        raise ValueError("Unexpected or missing release JARs")
    for entry in files:
        path = directory / entry["name"]
        if digest(path) != entry["sha256"]:
            raise ValueError(f"Artifact checksum mismatch: {path}")
        inspect_jar(path, entry["loader"], cfg["mod_version"])
    checksums = "".join(f"{entry['sha256']}  {entry['name']}\n" for entry in files)
    if (directory / "SHA256SUMS").read_text(encoding="utf-8") != checksums:
        raise ValueError("SHA256SUMS does not match manifest")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "plan", "package", "verify", "guard-tag"))
    parser.add_argument("--directory", type=Path, default=Path("build/release"))
    args = parser.parse_args()
    root = Path.cwd()
    try:
        if args.command == "plan":
            outputs = plan(root, os.environ["GITHUB_EVENT_NAME"], os.getenv("BEFORE_SHA", ""), os.environ["GITHUB_REF"])
            print(json.dumps(outputs, indent=2))
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
                stream.writelines(f"{key}={value}\n" for key, value in outputs.items())
        elif args.command == "check":
            cfg = config(root)
            changelog(root, cfg["mod_version"])
            print(f"Release metadata valid: {cfg['mod_version']}")
        elif args.command == "guard-tag":
            guard_tag(root, config(root)["mod_version"])
        elif args.command == "package":
            package(root, args.directory)
        else:
            verify(root, args.directory)
        return 0
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        print(f"Release validation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
