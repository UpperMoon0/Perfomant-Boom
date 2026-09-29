from pathlib import Path
import io
import json
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

import live_boom_test as live


class HarnessTests(unittest.TestCase):
    def test_windows_interactive_launch_preserves_forge_discovery_environment(self):
        source={'MOD_CLASSES':'main%%classes;main%%resources','MCP_MAPPINGS':'loom.stub',
                'PERFOMANT_BOOM_TEST_RUN':'123','JAVA_HOME':'jdk'}
        self.assertEqual(source,live.interactive_environment(source))

    def test_windows_launch_does_not_serialize_shell_credentials(self):
        source={'GH_TOKEN':'private','CURSEFORGE_API_TOKEN':'private','MOD_CLASSES':'main%%classes'}
        self.assertEqual({'MOD_CLASSES':'main%%classes'},live.interactive_environment(source))

    def test_repeated_jvm_module_flags_remain_valid(self):
        live.validate_launch({'command':['java','--add-opens','a/b=c','--add-opens','d/e=f',
            'dev.architectury.transformer.TransformerRuntime'],'cwd':'run','environment':{}})

    def test_deduplicated_jvm_flags_are_rejected_before_launch(self):
        with self.assertRaises(ValueError):
            live.validate_launch({'command':['java','--add-opens','a/b=c','d/e=f',
                'dev.architectury.transformer.TransformerRuntime'],'cwd':'run','environment':{}})

    def test_missing_java_executable_is_rejected(self):
        with self.assertRaises(ValueError):
            live.validate_launch({'command':[None,'dev.architectury.transformer.TransformerRuntime'],
                'cwd':'run','environment':{}})

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

    def lifecycle_fixture(self, root):
        records = {
            'lifecycle-ray-unload.json': dict(token='run', distinctChunk=True, selectedBefore=8, selectedAfter=8),
            'lifecycle-mutation-unload.json': dict(token='run', distinctChunk=True, savedPrefixMatched=True,
                detachedUnchanged=True, changedBefore=8, changedAfter=16),
            'lifecycle-partial.json': dict(token='run', processed=8, total=500, changed=8,
                interiorSupport=True, sandPendingRemoval=True, fastSandTick=True, controlSandTick=True),
            'lifecycle-physics.json': dict(token='run', interiorSupport=True, sandPendingRemoval=True,
                fastSandTick=True, controlSandTick=True, fastSandTickRestored=True, controlSandTickRestored=True,
                blockLightMatched=True, tickingTicks=40, sourceY=97, landingY=96, fastSandY=96, controlSandY=96),
            'lifecycle-persistence.json': dict(token='run', processed=8, total=500, changed=8,
                partialMatched=True, vanillaBlockLightMatched=True, metadataMatched=True),
        }
        for name, value in records.items(): (root/name).write_text(json.dumps(value))
        return records

    def test_complete_lifecycle_evidence_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); self.lifecycle_fixture(root)
            live.verify_lifecycle_evidence(root,'run')

    def test_lifecycle_evidence_requires_every_phase(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); self.lifecycle_fixture(root)
            (root/'lifecycle-mutation-unload.json').unlink()
            with self.assertRaises(OSError): live.verify_lifecycle_evidence(root,'run')

    def test_lifecycle_evidence_rejects_foreign_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); self.lifecycle_fixture(root)
            with self.assertRaisesRegex(ValueError,'different run'): live.verify_lifecycle_evidence(root,'other')

    def test_lifecycle_evidence_cannot_claim_completed_task_as_partial(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); records=self.lifecycle_fixture(root)
            records['lifecycle-partial.json']['processed']=500
            (root/'lifecycle-partial.json').write_text(json.dumps(records['lifecycle-partial.json']))
            with self.assertRaisesRegex(ValueError,'partial-mutation'): live.verify_lifecycle_evidence(root,'run')

    def test_lifecycle_evidence_requires_actual_chunk_replacement(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); records=self.lifecycle_fixture(root)
            records['lifecycle-ray-unload.json']['distinctChunk']=False
            (root/'lifecycle-ray-unload.json').write_text(json.dumps(records['lifecycle-ray-unload.json']))
            with self.assertRaisesRegex(ValueError,'ray chunk'): live.verify_lifecycle_evidence(root,'run')

    def test_lifecycle_evidence_requires_vanilla_partial_light_parity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); records=self.lifecycle_fixture(root)
            records['lifecycle-persistence.json']['vanillaBlockLightMatched']=False
            (root/'lifecycle-persistence.json').write_text(json.dumps(records['lifecycle-persistence.json']))
            with self.assertRaisesRegex(ValueError,'partial-mutation'): live.verify_lifecycle_evidence(root,'run')

    def test_lifecycle_evidence_requires_saved_and_executed_physics(self):
        for field, bad in [('fastSandTick', False), ('controlSandTick', False),
                           ('fastSandTickRestored', False), ('controlSandTickRestored', False),
                           ('sandPendingRemoval', False), ('interiorSupport', False),
                           ('blockLightMatched', False), ('tickingTicks', 39),
                           ('fastSandY', 97), ('controlSandY', 97), ('sourceY', 96)]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp); records=self.lifecycle_fixture(root)
                records['lifecycle-physics.json'][field]=bad
                (root/'lifecycle-physics.json').write_text(json.dumps(records['lifecycle-physics.json']))
                with self.assertRaisesRegex(ValueError,'neighbor/shape physics'):
                    live.verify_lifecycle_evidence(root,'run')

    def test_lifecycle_evidence_rejects_unrecorded_pre_stop_physics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); records=self.lifecycle_fixture(root)
            del records['lifecycle-partial.json']['fastSandTick']
            (root/'lifecycle-partial.json').write_text(json.dumps(records['lifecycle-partial.json']))
            with self.assertRaisesRegex(ValueError,'neighbor/shape physics'):
                live.verify_lifecycle_evidence(root,'run')

    def test_lifecycle_evidence_requires_physics_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); self.lifecycle_fixture(root)
            (root/'lifecycle-physics.json').unlink()
            with self.assertRaises(OSError): live.verify_lifecycle_evidence(root,'run')

    def test_incomplete_evidence_cannot_produce_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp); (p/'server-samples.json').write_text('[]')
            with self.assertRaises(ValueError): live.summarize(p)

if __name__=='__main__': unittest.main()
