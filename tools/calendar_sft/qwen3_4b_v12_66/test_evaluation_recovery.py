"""Reject mismatched model/prompt caches instead of silently reusing them."""
from copy import deepcopy
import json
import unittest

from resume_evaluation import bound_cached
from evaluate_v12_66 import prepared_inputs


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.case = prepared_inputs(split='intent_holdout')['cases'][0]
        self.model_sha = 'a' * 64

    def test_saved_answers_are_bound_to_unchanged_inputs(self):
        case, model_sha = self.case, self.model_sha
        saved = dict(id=case['id'], prompt_sha256=case['prompt_sha256'], model_sha256=model_sha,
                     output='{}', raw_response={'content':'{}'})
        self.assertEqual(bound_cached(case, saved, model_sha)["case_id"], case["id"])
        for key in ("id", "prompt_sha256", "model_sha256", "output"):
            changed = deepcopy(saved)
            changed[key] = "wrong"
            with self.subTest(key=key), self.assertRaises(ValueError):
                bound_cached(case, changed, model_sha)

    def test_cached_failures_are_retained_in_the_result(self):
        case, model_sha = self.case, self.model_sha
        saved = {"id": case["id"], "prompt_sha256": case["prompt_sha256"], "model_sha256": model_sha,
                 "output": "{}", "raw_response": {"content": "{}"}}
        result = bound_cached(case, saved, model_sha)
        self.assertFalse(result["passed"])
        self.assertEqual(result["raw"], "{}")


if __name__ == "__main__":
    unittest.main()
