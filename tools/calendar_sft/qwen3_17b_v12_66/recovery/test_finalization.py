"""Reject data drift, later Android edits, and incomplete computation evidence."""
from pathlib import Path
import tempfile
import unittest

from finalize_completed import capture_boundary, verify_boundary, verify_optimizer, verify_selection, digest


class FinalizationTests(unittest.TestCase):
    def fixture(self, root):
        for name in ('app/src/main/Source.kt', 'docs/data.json', 'docs/rules.md'):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('original', encoding='utf-8')
        return {name: digest(root / name) for name in ('app/src/main/Source.kt', 'docs/data.json', 'docs/rules.md')}

    def test_android_drift_is_explicit_and_original_snapshot_is_retained(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            original = self.fixture(root)
            retained = dict(original)
            (root / 'app/src/main/Source.kt').write_text('new Android', encoding='utf-8')
            boundary = capture_boundary(root, original)
            self.assertEqual(original, retained)
            self.assertEqual(boundary['original_whole_project_snapshot'], 'DIFFERS')
            self.assertEqual(len(boundary['android_changed']), 1)
            self.assertEqual(verify_boundary(root, boundary)['status'], 'PASS_TRAINING_INPUTS_ANDROID_DRIFT')

    def test_data_and_rules_drift_are_rejected(self):
        for name in ('docs/data.json', 'docs/rules.md'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                original = self.fixture(root)
                (root / name).write_text('changed', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'data/rules changed'):
                    capture_boundary(root, original)

    def test_later_android_modification_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            boundary = capture_boundary(root, self.fixture(root))
            (root / 'app/src/main/Source.kt').write_text('later edit', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Android changed'):
                verify_boundary(root, boundary)

    def test_new_android_file_after_boundary_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            boundary = capture_boundary(root, self.fixture(root))
            (root / 'app/src/main/Added.kt').write_text('new', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Android changed'):
                verify_boundary(root, boundary)

    def test_missing_original_android_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            original = self.fixture(root)
            (root / 'app/src/main/Source.kt').unlink()
            with self.assertRaisesRegex(ValueError, 'Missing'):
                capture_boundary(root, original)

    def test_later_non_android_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            boundary = capture_boundary(root, self.fixture(root))
            (root / 'docs/rules.md').write_text('changed', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Protected file changed'):
                verify_boundary(root, boundary)

    def rows(self):
        return [dict(step=step, epoch=1, loss=1.0, gradient_norm=1.0, learning_rate=0.01,
                     seconds=step * 4.0, optimizer_step_seconds=4.0, scale=128.0, overflow_retries=0)
                for step in (1, 2)]

    def test_duplicate_and_missing_optimizer_updates_are_rejected(self):
        rows = self.rows()
        self.assertEqual(verify_optimizer(rows, 2, 2)['steps'], 2)
        for bad in (rows[:1], [rows[0], rows[0]], list(reversed(rows))):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'incomplete'):
                verify_optimizer(bad, 2, 2)

    def test_nonfinite_loss_and_wrong_epoch_are_rejected(self):
        for key, value in (('loss', float('nan')), ('gradient_norm', float('inf')), ('epoch', 2)):
            rows = self.rows()
            rows[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_optimizer(rows, 2, 2)

    def test_missing_evaluation_and_wrong_selected_step_are_rejected(self):
        evaluations = [dict(step=1, score=[0.8, 1.0], seconds=4.0), dict(step=2, score=[0.7, 1.0], seconds=4.0)]
        self.assertEqual(verify_selection(evaluations, [1, 2], 1, [0.8, 1.0]), 1)
        for bad, step, score in ((evaluations[:1], 1, [0.8, 1.0]), (evaluations, 2, [0.7, 1.0])):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                verify_selection(bad, [1, 2], step, score)


if __name__ == '__main__':
    unittest.main()
