"""No network, credentials, or real releases: exercise the release contract in temp repos."""
from pathlib import Path
import json
import subprocess
import tempfile
import unittest
import zipfile

import release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Release Tests")
        self.git("config", "user.email", "release-tests@example.invalid")
        self.write_version("1.0.0")
        self.commit()
        self.before = release.head_sha(self.root)
        self.write_version("1.0.1")
        self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True, stderr=subprocess.PIPE).strip()

    def write_version(self, version):
        (self.root / "gradle.properties").write_text(
            f"mod_version = {version}\narchives_name = perfomant_boom\nminecraft_version = 1.20.1\n")
        path = self.root / "changelog" / f"{version}.md"
        path.parent.mkdir(exist_ok=True)
        path.write_text(f"# {version}\nChanges.\n")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "Fixture")

    def plan(self, event="push", before=None, ref="refs/heads/main"):
        return release.plan(self.root, event, self.before if before is None else before, ref)

    def fake_jars(self, version="1.0.1"):
        for loader in release.LOADERS:
            path = self.root / loader / "build" / "libs" / release.jar_name(loader, version)
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr("com/nstut/explosion/FastExplosionEngine.class", b"fixture")
                if loader == "fabric":
                    jar.writestr("fabric.mod.json", json.dumps({"id": "perfomant_boom", "version": version}))
                else:
                    jar.writestr("META-INF/mods.toml", f'[[mods]]\nmodId = "perfomant_boom"\nversion = "{version}"\n')

    def package(self):
        self.fake_jars()
        directory = self.root / "release"
        release.package(self.root, directory)
        return directory

    def test_version_bump_releases(self):
        self.assertEqual(self.plan()["release"], "true")
        self.assertEqual(self.plan()["tag"], "v1.0.1")

    def test_unchanged_version_with_old_tag_does_not_release(self):
        self.git("tag", "v1.0.1")
        previous = release.head_sha(self.root)
        (self.root / "readme").write_text("No version bump")
        self.commit()
        self.assertEqual(self.plan(before=previous)["release"], "false")

    def test_pending_untagged_version_can_retry(self):
        self.assertEqual(self.plan(before=release.head_sha(self.root))["release"], "true")

    def test_manual_retry_at_same_tagged_head_is_safe(self):
        self.git("tag", "-a", "v1.0.1", "-m", "Version")
        self.assertEqual(self.plan(event="workflow_dispatch", before="")["release"], "true")

    def test_manual_retry_cannot_reassign_old_tag(self):
        self.git("tag", "v1.0.1", self.before)
        with self.assertRaisesRegex(ValueError, "already belongs"):
            self.plan(event="workflow_dispatch", before="")

    def test_bump_cannot_reassign_old_tag(self):
        self.git("tag", "v1.0.1", self.before)
        with self.assertRaisesRegex(ValueError, "already belongs"):
            self.plan()

    def test_release_guard_rechecks_collision(self):
        self.git("tag", "v1.0.1", self.before)
        with self.assertRaises(ValueError):
            release.guard_tag(self.root, "1.0.1")

    def test_no_release_from_feature_branch(self):
        with self.assertRaises(ValueError):
            self.plan(ref="refs/heads/feature")

    def test_no_release_from_pull_request_event(self):
        with self.assertRaises(ValueError):
            self.plan(event="pull_request")

    def test_first_push_zero_before(self):
        self.assertEqual(self.plan(before="0" * 40)["release"], "true")

    def test_invalid_before_is_rejected(self):
        with self.assertRaises(ValueError):
            self.plan(before="--all")

    def test_invalid_or_unsafe_versions(self):
        for version in ("1.0", "01.0.1", "../1.0.1", "1.0.1\nx=y", "1.0.1-SNAPSHOT"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.version_tuple(version)

    def test_version_downgrade_is_rejected(self):
        self.write_version("0.9.9")
        with self.assertRaisesRegex(ValueError, "downgrade"):
            self.plan()

    def test_duplicate_property_rejected(self):
        with self.assertRaises(ValueError):
            release.properties("mod_version=1.0.0\nmod_version=1.0.1\n")

    def test_whitespace_crlf_supported(self):
        self.assertEqual(release.properties("# hi\r\n mod_version = 1.0.1 \r\n")["mod_version"], "1.0.1")

    def test_missing_changelog_rejected(self):
        (self.root / "changelog/1.0.1.md").unlink()
        with self.assertRaises(ValueError):
            self.plan()

    def test_whitespace_only_changelog_rejected(self):
        (self.root / "changelog/1.0.1.md").write_text(" \n\t")
        with self.assertRaises(ValueError):
            self.plan()

    def test_package_and_verify_both_loaders(self):
        directory = self.package()
        release.verify(self.root, directory)
        self.assertEqual(len(list(directory.glob("*.jar"))), 2)

    def test_sources_and_dev_jars_are_excluded(self):
        self.fake_jars()
        for suffix in ("sources", "dev", "dev-shadow", "javadoc"):
            (self.root / f"fabric/build/libs/perfomant_boom-fabric-1.0.1-{suffix}.jar").write_bytes(b"not release")
        release.package(self.root, self.root / "release")
        self.assertEqual(len(list((self.root / "release").glob("*.jar"))), 2)

    def test_extra_runnable_jar_rejected(self):
        self.fake_jars()
        (self.root / "fabric/build/libs/unexpected.jar").write_bytes(b"bad")
        with self.assertRaises(ValueError):
            release.package(self.root, self.root / "release")

    def test_missing_loader_rejected(self):
        self.fake_jars()
        (self.root / "forge/build/libs/perfomant_boom-forge-1.0.1.jar").unlink()
        with self.assertRaises(ValueError):
            release.package(self.root, self.root / "release")

    def test_wrong_metadata_rejected(self):
        self.fake_jars()
        path = self.root / "fabric/build/libs/perfomant_boom-fabric-1.0.1.jar"
        with self.assertRaises(ValueError):
            release.inspect_jar(path, "fabric", "9.9.9")

    def test_missing_shared_classes_rejected(self):
        path = self.root / "broken.jar"
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("fabric.mod.json", "{}")
        with self.assertRaises(ValueError):
            release.inspect_jar(path, "fabric", "1.0.1")

    def test_tampered_jar_rejected(self):
        directory = self.package()
        next(directory.glob("*.jar")).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            release.verify(self.root, directory)

    def test_tampered_checksum_file_rejected(self):
        directory = self.package()
        (directory / "SHA256SUMS").write_text("wrong")
        with self.assertRaisesRegex(ValueError, "SHA256SUMS"):
            release.verify(self.root, directory)

    def test_other_commit_artifacts_rejected(self):
        directory = self.package()
        (self.root / "new-file").write_text("Different head")
        self.commit()
        with self.assertRaisesRegex(ValueError, "provenance"):
            release.verify(self.root, directory)

    def test_manifest_cannot_omit_a_loader_or_traverse_paths(self):
        directory = self.package()
        path = directory / "manifest.json"
        original = json.loads(path.read_text())
        for files in (original["files"][:1], [{"loader": "fabric", "name": "../outside", "sha256": "0"} ]):
            value = dict(original, files=files)
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                release.verify(self.root, directory)

    def test_nonempty_staging_directory_rejected(self):
        directory = self.package()
        with self.assertRaisesRegex(ValueError, "empty"):
            release.package(self.root, directory)


if __name__ == "__main__":
    unittest.main()
