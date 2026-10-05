from pathlib import Path
import hashlib
import json
import tempfile
import unittest
from v12_66_launch_integrity import verify_launch_inputs, PROTECTED_LOCK, PREPARATION_LOCK


class LaunchIntegrityTest(unittest.TestCase):
    def fixture(self, root):
        files = {"app/sample.kt": b"old app", "tools/calendar_sft/encoder.py": b"encoder",
                 "docs/calendar_sft_v12_66/train.jsonl": b"dataset", "docs/rules.md": b"rules"}
        hashes = {}
        for name, value in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(value)
            hashes[name] = hashlib.sha256(value).hexdigest()
        for path, value in ((PROTECTED_LOCK, {"files": hashes}),
                            (PREPARATION_LOCK, {"status": "PASS", "files": {
                                k: v for k, v in hashes.items() if not k.startswith("app/")}})):
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_text(json.dumps(value), encoding="utf-8")

    def test_android_drift_is_reported_but_training_inputs_remain_exact(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            (root / "app/sample.kt").write_bytes(b"new app")
            result = verify_launch_inputs(root)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["historical_whole_project_snapshot"], "DIFFERS")
            self.assertEqual(len(result["android_only_drift"]), 1)

    def test_data_rule_or_encoder_changes_cannot_be_ignored(self):
        for name in ("tools/calendar_sft/encoder.py", "docs/calendar_sft_v12_66/train.jsonl", "docs/rules.md"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); self.fixture(root)
                (root / name).write_bytes(b"changed")
                with self.assertRaisesRegex(ValueError, "changed"):
                    verify_launch_inputs(root)

    def test_new_preparation_files_are_also_protected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            lock = root / PROTECTED_LOCK
            data = json.loads(lock.read_text(encoding="utf-8"))
            del data["files"]["docs/calendar_sft_v12_66/train.jsonl"]
            lock.write_text(json.dumps(data), encoding="utf-8")
            (root / "docs/calendar_sft_v12_66/train.jsonl").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "Prepared V12.66"):
                verify_launch_inputs(root)

    def test_missing_protected_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            (root / "app/sample.kt").unlink()
            with self.assertRaisesRegex(ValueError, "missing"):
                verify_launch_inputs(root)

    def test_actual_preparation_is_unchanged(self):
        self.assertEqual(verify_launch_inputs()["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
