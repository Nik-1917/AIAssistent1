"""Prevent replay of completed training and overwrite of existing build outputs."""
from pathlib import Path
import tempfile
import unittest

from finish import completed_training_step, require_fresh_outputs


class FinishTests(unittest.TestCase):
    def test_completed_training_is_retained(self):
        step = {'stage': 'training', 'exit_code': 0}
        previous = {'status': 'FAILED', 'stage': 'gguf_build',
                    'steps': [step, {'stage': 'gguf_build', 'exit_code': 1}]}
        training = {'status': 'COMPLETE', 'completed_optimizer_steps': 1128}
        self.assertEqual(completed_training_step(previous, training), step)

    def test_incomplete_training_or_later_stage_is_rejected(self):
        previous = {'status': 'FAILED', 'stage': 'gguf_build',
                    'steps': [{'stage': 'training', 'exit_code': 0}]}
        for training in ({'status': 'RUNNING', 'completed_optimizer_steps': 1128},
                         {'status': 'COMPLETE', 'completed_optimizer_steps': 924}):
            with self.subTest(training=training), self.assertRaises(ValueError):
                completed_training_step(previous, training)
        previous['stage'] = 'q4_evaluation'
        with self.assertRaises(ValueError):
            completed_training_step(previous, {'status': 'COMPLETE', 'completed_optimizer_steps': 1128})

    def test_existing_build_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            require_fresh_outputs(run)
            (run / 'build').mkdir()
            partial = run / 'build/partial.gguf'
            partial.write_bytes(b'preserve')
            with self.assertRaises(ValueError):
                require_fresh_outputs(run)
            self.assertEqual(partial.read_bytes(), b'preserve')


if __name__ == '__main__':
    unittest.main()
