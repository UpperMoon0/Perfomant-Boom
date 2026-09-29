from pathlib import Path
import io
import json
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

import live_boom_test as live


class HarnessTests(unittest.TestCase):
    def test_free_port_is_local_and_valid(self):
        self.assertGreater(live.find_free_port(), 0)

    def test_both_launches_are_prepared_in_one_gradle_invocation(self):
        cmd=live.command(Path('/test'), 'forge')
        self.assertIn(':forge:runLiveBoomTestServer', cmd)
        self.assertIn(':forge:runLiveBoomTestClient', cmd)
        self.assertIn(str(Path('/test')/'tools/export_live_launch.gradle'), cmd)
        self.assertNotIn('-x',cmd)

    def test_run_directories_are_exclusive_and_loopback_bound(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            live.prepare_server(root,'fabric',25585,'run1')
            props=(root/'fabric/run/live-boom/run1/server/server.properties').read_text()
            self.assertIn('server-ip=127.0.0.1',props)
            with self.assertRaises(FileExistsError):
                live.prepare_server(root,'fabric',25585,'run1')
            live.prepare_server(root,'fabric',25586,'run2')
            self.assertTrue((root/'fabric/run/live-boom/run1/server/eula.txt').exists())

    def test_frozen_inputs_reject_source_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); p=root/'build.gradle'; p.write_text('one')
            frozen=live.FrozenInputs(root); frozen.check()
            p.write_text('two')
            with self.assertRaises(RuntimeError): frozen.verify_hashes()

    def test_frozen_inputs_reject_new_source_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'common/src').mkdir(parents=True)
            frozen=live.FrozenInputs(root)
            (root/'common/src/new.java').write_text('changed')
            with self.assertRaises(RuntimeError): frozen.check()

    def test_frozen_inputs_include_common_development_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); p=root/'common/build/devlibs/mod.jar'; p.parent.mkdir(parents=True); p.write_bytes(b'old')
            frozen=live.FrozenInputs(root); p.write_bytes(b'new bytes')
            with self.assertRaises(RuntimeError): frozen.check()

    def test_evidence_creation_does_not_invalidate_frozen_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); frozen=live.FrozenInputs(root)
            p=root/'build/live-boom-evidence/test/result.json'; p.parent.mkdir(parents=True); p.write_text('{}')
            frozen.verify_hashes()

    def test_pass_marker_cannot_hide_nonzero_exit(self):
        process=Mock(); process.poll.return_value=1; process.returncode=1
        pump=Mock(); pump._thread.is_alive.return_value=False; pump.prefix='test'; pump.history=[live.CLIENT_PASS]
        with self.assertRaises(RuntimeError): live.assert_successful_exit(process,pump,1)

    def test_pass_marker_cannot_hide_late_fatal_output(self):
        process=Mock(); process.poll.return_value=0; process.returncode=0
        pump=Mock(); pump._thread.is_alive.return_value=False; pump.prefix='test'; pump.history=[live.CLIENT_PASS,'BUILD FAILED']
        with self.assertRaises(RuntimeError): live.assert_successful_exit(process,pump,1)

    def test_clean_exit_is_accepted(self):
        process=Mock(); process.poll.return_value=0; process.returncode=0
        pump=Mock(); pump._thread.is_alive.return_value=False; pump.prefix='test'; pump.history=[live.CLIENT_PASS]
        live.assert_successful_exit(process,pump,1)

    def test_incomplete_evidence_cannot_produce_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp); (p/'server-samples.json').write_text('[]')
            with self.assertRaises(ValueError): live.summarize(p)

if __name__=='__main__': unittest.main()
