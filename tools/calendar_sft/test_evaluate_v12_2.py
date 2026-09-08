"""Check V12.2 scoring independently of model inference."""

from copy import deepcopy
import json
import unittest

from dataset_contract import load_jsonl
from evaluate_v12_2_adapter import DATA, score_rows


class V122ScoringTest(unittest.TestCase):
    def setUp(self):
        self.rows = load_jsonl(DATA / "holdout.jsonl")
        self.predictions = [{"case_id": row["case_id"], "output": row["messages"][-1]["content"]} for row in self.rows]

    def test_reference_outputs_pass_all_checks(self):
        report = score_rows(self.rows, self.predictions)
        self.assertEqual(8, report["metrics"]["strict_pass"])
        self.assertEqual(8, report["metrics"]["calendar_add_temporal_match"])

    def test_wrong_leap_date_cannot_pass(self):
        answer = json.loads(self.predictions[0]["output"])
        answer["params"]["starts_at"] = "2028-03-01T16:00"
        self.predictions[0]["output"] = json.dumps(answer, ensure_ascii=False)
        report = score_rows(self.rows, self.predictions)
        self.assertEqual(7, report["metrics"]["strict_pass"])
        self.assertEqual(7, report["metrics"]["calendar_add_temporal_match"])
        self.assertEqual(8, report["metrics"]["contract_valid"])

    def test_case_only_changes_have_their_own_metric(self):
        answer = json.loads(self.predictions[0]["output"])
        answer["params"]["title"] = answer["params"]["title"].lower()
        answer["reply"] = answer["reply"].lower()
        self.predictions[0]["output"] = json.dumps(answer, ensure_ascii=False)
        report = score_rows(self.rows, self.predictions)
        self.assertEqual(7, report["metrics"]["strict_pass"])
        self.assertEqual(8, report["metrics"]["case_insensitive_pass"])

    def test_invalid_json_is_not_a_valid_contract(self):
        self.predictions[0]["output"] = "not JSON"
        report = score_rows(self.rows, self.predictions)
        self.assertEqual(7, report["metrics"]["json_object_valid"])
        self.assertEqual(7, report["metrics"]["contract_valid"])
        self.assertEqual(7, report["metrics"]["strict_pass"])

    def test_an_invented_time_cannot_pass_date_only_case(self):
        answer = json.loads(self.predictions[1]["output"])
        answer["params"]["time"] = "13:00"
        self.predictions[1]["output"] = json.dumps(answer, ensure_ascii=False)
        report = score_rows(self.rows, self.predictions)
        self.assertEqual(7, report["metrics"]["calendar_add_temporal_match"])

    def test_missing_or_duplicate_predictions_are_rejected(self):
        for predictions in (self.predictions[:-1], self.predictions + [deepcopy(self.predictions[0])]):
            with self.assertRaisesRegex(ValueError, "IDs"):
                score_rows(self.rows, predictions)


if __name__ == "__main__":
    unittest.main()
