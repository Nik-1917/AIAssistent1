"""Reject mismatched model/prompt caches instead of silently reusing them."""
from copy import deepcopy
import json
import unittest

from resume_evaluation import bound_cached, prepared


class RecoveryTest(unittest.TestCase):
    def test_real_saved_answers_are_bound_to_unchanged_inputs(self):
        folder, _, model_sha, cases, _, cached = prepared("f16")
        self.assertEqual(len(cases), 200)
        self.assertGreaterEqual(len(cached), 68)
        case = next(case for case in cases if case["id"] in cached)
        saved = json.loads((folder / "cases" / (case["id"] + ".json")).read_text(encoding="utf-8"))
        self.assertEqual(bound_cached(case, saved, model_sha)["case_id"], case["id"])
        for key in ("id", "prompt_sha256", "model_sha256", "output"):
            changed = deepcopy(saved)
            changed[key] = "wrong"
            with self.subTest(key=key), self.assertRaises(ValueError):
                bound_cached(case, changed, model_sha)

    def test_cached_failures_are_retained_in_the_result(self):
        _, _, model_sha, cases, _, _ = prepared("f16")
        case = cases[0]
        saved = {"id": case["id"], "prompt_sha256": case["prompt_sha256"], "model_sha256": model_sha,
                 "output": "{}", "raw_response": {"content": "{}"}}
        result = bound_cached(case, saved, model_sha)
        self.assertFalse(result["passed"])
        self.assertEqual(result["raw"], "{}")


if __name__ == "__main__":
    unittest.main()
