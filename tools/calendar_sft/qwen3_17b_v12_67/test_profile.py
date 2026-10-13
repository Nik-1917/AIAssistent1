"""Checks that the isolated 1.7B profile remains tied to verified V12.67 inputs."""
import unittest
import run_context as profile
import workflow


class Qwen17BProfileTests(unittest.TestCase):
    def test_profile_pins_the_expected_model_and_run(self):
        self.assertEqual(profile.CONFIG["model"], "qwen3_1_7b")
        self.assertEqual(profile.CONFIG["repository"], "Qwen/Qwen3-1.7B")
        self.assertEqual(profile.CONFIG["source_revision"], "70d244cc86ccca08cf5af4e1e306ecf908b1ad5e")
        self.assertEqual(profile.CONFIG["default_micro_batch"], 2)
        self.assertEqual(profile.CONFIG["effective_batch_size"], 16)
        self.assertNotEqual(profile.WORK, profile.ROOT / "build/calendar_v12_67_qwen3_17b")

    def test_all_pinned_source_files_match_the_lock(self):
        lock, source = profile.verify_source(profile.CONFIG["model"])
        self.assertEqual(len(lock["files"]), 11)
        self.assertEqual(source, source.resolve())

    def test_v12_67_contract_and_reviewed_rows_are_unchanged(self):
        self.assertEqual(profile.verify_launch_inputs()["status"], "PASS")
        self.assertEqual(profile.verify_manual_review()["manually_reviewed"], 6616)
        with (profile.DATA / "train.jsonl").open(encoding="utf-8") as training_file:
            training_rows = sum(1 for line in training_file if line.strip())
        self.assertEqual(training_rows, 5405)

    def test_full_prompt_fits_without_truncation(self):
        report = profile.read(profile.ROOT /
            "build/calendar_v12_67_qwen3_17b/qwen3_1_7b/preflight.json")
        self.assertEqual(report["max_tokens_all_transports"], 766)
        self.assertEqual(report["truncated_records"], 0)
        self.assertEqual(report["planned_optimizer_steps"], 1014)

    def test_only_the_known_pretraining_path_mismatch_is_recoverable(self):
        parent = {"status": "FAILED", "stage": "verification", "steps": [],
                  "error": "FileNotFoundError: token_audit.json"}
        prior = {"status": "FAILED"}
        self.assertTrue(workflow.recoverable_verification_failure(parent, prior))
        self.assertFalse(workflow.recoverable_verification_failure(
            {**parent, "stage": "training"}, prior))
        self.assertFalse(workflow.recoverable_verification_failure(
            {**parent, "steps": [{"stage": "training"}]}, prior))

    def test_completed_token_audit_is_bound_to_current_inputs(self):
        audit = workflow.verify_cached_token_audit()
        self.assertEqual(audit["status"], "PASS")
        self.assertEqual(audit["source_revision"], profile.CONFIG["source_revision"])
        self.assertEqual(audit["conversations"], 6616)
        self.assertFalse(audit["model_weights_loaded"])


if __name__ == "__main__":
    unittest.main()
