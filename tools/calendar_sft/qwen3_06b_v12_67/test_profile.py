"""Integration checks for the model binding and independent evaluation boundary."""
from copy import deepcopy
import json
import unittest
from unittest.mock import patch

import run_context as profile
import train_v12_67 as trainer
from release_evaluation import independent_inputs
from evaluate_v12_67 import score_cached, case_digest


class ProfileChecks(unittest.TestCase):
    def test_trainer_uses_isolated_model_and_preserves_original_configuration(self):
        self.assertEqual(trainer.NAMES, {"qwen3_0_6b": "Qwen3-0.6B"})
        self.assertEqual(trainer.CONFIG_PATH, profile.HERE / "config_batch2.json")
        self.assertEqual(trainer.WORK, profile.ROOT / "build/calendar_v12_67_qwen3_06b_batch2")
        original = profile.read(profile.ROOT / "tools/calendar_sft/v12_67_training_config.json")
        self.assertEqual(original["model"], "qwen3_1_7b")
        frozen = set(profile.frozen_paths())
        for path in profile.HERE.glob("*.py"):
            self.assertIn(path, frozen)
        self.assertEqual(profile.verify_preservation()["files_checked"], 1014)
        self.assertEqual(profile.verify_launch_inputs()["historical_freeze_files"], 166)

    def test_wrong_model_revision_and_directory_are_rejected_before_loading(self):
        with self.assertRaisesRegex(ValueError, "Wrong model"):
            profile.verify_source("qwen3_1_7b")
        original = profile.read(profile.SOURCE_LOCK)
        changed = deepcopy(original)
        changed["revision"] = "0" * 40
        with patch.object(profile, "read", return_value=changed):
            with self.assertRaisesRegex(ValueError, "revision"):
                profile.verify_source(profile.CONFIG["model"])
        changed = deepcopy(original)
        changed["directory"] = str(profile.ROOT / "build/calendar_sft_models/Qwen3-1.7B")
        with patch.object(profile, "read", return_value=changed):
            with self.assertRaisesRegex(ValueError, "directory"):
                profile.verify_source(profile.CONFIG["model"])

    def test_all_independent_targets_use_current_grader_and_reject_reordered_answers(self):
        packs = independent_inputs()
        self.assertEqual(sum(len(pack["cases"]) for pack in packs.values()), 824)
        for pack in packs.values():
            answers = [dict(case_id=case["id"], case_sha256=case_digest(case),
                            raw_output=json.dumps(case["expected"], ensure_ascii=False))
                       for case in pack["cases"]]
            cached = dict(binding=pack["binding"], model_sha256="a" * 64, answers=answers)
            result = score_cached(pack, cached)
            self.assertEqual(result["report"]["failed_ids"], [])
            changed = deepcopy(cached)
            changed["answers"][0], changed["answers"][1] = changed["answers"][1], changed["answers"][0]
            with self.assertRaisesRegex(ValueError, "reordered"):
                score_cached(pack, changed)


if __name__ == "__main__":
    unittest.main()
