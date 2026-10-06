"""Check isolation, temporal selection and the actual untouched preparation."""
import unittest

import run_context as context
from v12_66_launch_integrity import verify_launch_inputs


class RunContractTest(unittest.TestCase):
    def test_isolated_model_and_output(self):
        self.assertEqual(context.CONFIG["repository"], "Qwen/Qwen3-4B-Instruct-2507")
        self.assertEqual(context.CONFIG["model"], "qwen3_4b")
        self.assertNotEqual(context.WORK, context.ROOT / "build/calendar_v12_66_qwen35")

    def test_complete_checkpoint_selection_schedule(self):
        self.assertEqual(context.selection_steps(6009), [188, 376, 564, 752, 940, 1128])
        with self.assertRaises(ValueError):
            context.selection_steps(0)

    def test_holdouts_are_excluded_and_android_cases_keep_targets(self):
        rows = context.read(context.DATA / "generation_dev.json")
        cases = context.development_cases(rows)
        self.assertEqual(len(cases), 232)
        for standard, android in zip(cases[::2], cases[1::2]):
            self.assertEqual(standard["expected"], android["expected"])
            self.assertEqual(standard["user"], android["user"])
            self.assertNotEqual(standard["system"], android["system"])
            self.assertNotEqual(standard.get("split"), "holdout")

    def test_existing_preparation_is_untouched(self):
        self.assertEqual(verify_launch_inputs()["status"], "PASS")
        self.assertEqual(context.verify_preservation()["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
