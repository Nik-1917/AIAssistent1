"""The generated-answer grader must distinguish a fluent reply from correctness."""
from copy import deepcopy
import json
import unittest

from evaluate_v12_62_gguf import cases, grade


class GeneratedReplyGradeTest(unittest.TestCase):
    def setUp(self):
        self.row = next(x for x in cases() if x["id"] == "M15")
        self.answer = {"intent": "calendar_add", "reply": "Едем в четверть первого.",
                       "params": {"title": "Едем", "starts_at": "2026-09-30T12:15"}}

    def score(self, answer):
        return grade(self.row, json.dumps(answer, ensure_ascii=False))

    def test_correct_answer(self):
        self.assertTrue(self.score(self.answer)["passed"])

    def test_right_reply_wrong_params(self):
        value = deepcopy(self.answer)
        value["params"]["starts_at"] = "2026-09-30T12:30"
        result = self.score(value)
        self.assertTrue(result["reply_clock_correct"])
        self.assertFalse(result["temporal_params_match"])
        self.assertFalse(result["passed"])

    def test_right_params_wrong_reply(self):
        value = deepcopy(self.answer)
        value["reply"] = "Едем в половине первого."
        result = self.score(value)
        self.assertTrue(result["params_exact"])
        self.assertFalse(result["reply_clock_correct"])
        self.assertFalse(result["passed"])

    def test_contradictory_reply(self):
        value = deepcopy(self.answer)
        value["reply"] = "Едем в четверть первого, в половине первого."
        self.assertFalse(self.score(value)["reply_clock_correct"])

    def test_invalid_json(self):
        self.assertFalse(grade(self.row, "Едем в четверть первого.")["passed"])


if __name__ == "__main__":
    unittest.main()
