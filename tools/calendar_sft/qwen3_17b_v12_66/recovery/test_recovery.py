"""Exercise interruption evidence and rejection of unsafe checkpoint/log caches."""
import json
from pathlib import Path
import tempfile
import unittest

from recover import archive_runtime, scan_runtime, verify_cached, digest, require_interrupted_training


class RecoveryTests(unittest.TestCase):
    def test_second_interruption_in_training_can_be_recovered(self):
        require_interrupted_training({'status': 'RUNNING', 'stage': 'training', 'steps': []})
        require_interrupted_training({'status': 'FAILED', 'stage': 'training',
                                      'steps': [{'stage': 'training', 'exit_code': 1}]})

    def test_completed_or_later_stage_cannot_be_replayed_as_training(self):
        for previous in (
            {'status': 'COMPLETE', 'stage': 'training', 'steps': []},
            {'status': 'FAILED', 'stage': 'gguf_build', 'steps': []},
            {'status': 'FAILED', 'stage': 'training', 'steps': [{'stage': 'training', 'exit_code': 0}]},
            {'status': 'FAILED', 'stage': 'training', 'steps': [{'stage': 'gguf_build', 'exit_code': 1}]},
        ):
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                require_interrupted_training(previous)

    def test_corrupt_progress_is_archived_byte_for_byte_without_changing_original(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            broken = run / 'development_state.json'
            broken.write_bytes(b'\0' * 94)
            before = digest(broken)
            copies = archive_runtime(run, run / 'originals')
            self.assertEqual((run / 'originals' / broken.name).read_bytes(), broken.read_bytes())
            self.assertEqual(copies[broken.name]['sha256'], before)
            self.assertEqual(digest(broken), before)
            with self.assertRaises(FileExistsError):
                archive_runtime(run, run / 'originals')

    def test_valid_history_retains_broken_scratch_as_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            (run / 'optimizer_steps.jsonl').write_text('{"step":1,"seconds":12}\n', encoding='utf-8')
            (run / 'development_state.json').write_bytes(b'\0' * 94)
            result = scan_runtime(run, 1)
            self.assertEqual(result['invalid_progress_metadata']['bytes'], 94)
            self.assertEqual(result['jsonl']['optimizer_steps.jsonl']['rows'], 1)

    def test_corrupt_gpu_log_is_rejected_without_repair_or_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            (run / 'optimizer_steps.jsonl').write_text('{"step":1,"seconds":12}\n', encoding='utf-8')
            log = run / 'gpu_power_samples.jsonl'
            log.write_bytes(b'{"limit_watts":150}\n\0\0')
            before = log.read_bytes()
            with self.assertRaisesRegex(ValueError, 'Invalid JSONL'):
                scan_runtime(run, 1)
            self.assertEqual(log.read_bytes(), before)

    def test_duplicate_or_unsaved_optimizer_updates_are_rejected(self):
        for steps in ([1, 1], [1, 2], [2]):
            with self.subTest(steps=steps), tempfile.TemporaryDirectory() as folder:
                run = Path(folder)
                (run / 'optimizer_steps.jsonl').write_text(
                    ''.join(json.dumps({'step': step, 'seconds': 12}) + '\n' for step in steps), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'Optimizer log'):
                    scan_runtime(run, 1)

    def test_foreign_adapter_answer_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            checkpoint = Path(folder)
            (checkpoint / 'development_cases').mkdir()
            case = {'id': 'heldout1', 'user': 'Когда?', 'system': 'Дата', 'prompt_format': 'android_chatml'}
            cached = checkpoint / 'development_cases/heldout1.json'
            item = {'case': case, 'step': 752, 'adapter_sha256': 'a' * 64, 'raw_output': '{}'}
            cached.write_text(json.dumps(item), encoding='utf-8')
            integrity = {'step': 752, 'adapter_sha256': 'a' * 64}
            self.assertEqual(len(verify_cached(checkpoint, [case], integrity)), 1)
            item['adapter_sha256'] = 'b' * 64
            cached.write_text(json.dumps(item), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'different inputs'):
                verify_cached(checkpoint, [case], integrity)


if __name__ == '__main__':
    unittest.main()
