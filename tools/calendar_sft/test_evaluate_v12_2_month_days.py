"""Offline checks for the isolated context-only V12.2 comparison."""

from copy import deepcopy
import json
import unittest

from dataset_contract import load_jsonl
from evaluate_v12_2_adapter import DATA, score_rows
from evaluate_v12_2_month_days import LABEL, ROOT, compare_reports, score_saved_predictions, with_month_days


class MonthDaysContextTest(unittest.TestCase):
    def setUp(self):
        self.rows = load_jsonl(DATA / "holdout.jsonl")

    def test_actual_holdout_days_match_leap_and_common_years(self):
        for row, days in zip(self.rows, (29, 29, 29, 29, 28, 28, 28, 28)):
            extended = with_month_days(row)
            self.assertEqual(row["messages"][0]["content"] + f"\n{LABEL}: {days}", extended["messages"][0]["content"])

    def test_only_system_content_changes_in_all_existing_cases(self):
        rows = self.rows + load_jsonl(DATA / "regression_holdout.jsonl")
        snapshot = deepcopy(rows)
        for row in rows:
            extended = with_month_days(row)
            extended["messages"][0]["content"] = row["messages"][0]["content"]
            self.assertEqual(row, extended)
        self.assertEqual(snapshot, rows)

    def test_other_month_lengths(self):
        original = self.rows[0]["messages"][0]["content"]
        for month, days in ((1, 31), (4, 30), (12, 31)):
            row = deepcopy(self.rows[0])
            row["messages"][0]["content"] = original.replace("2028-02-28", f"2028-{month:02d}-28")
            self.assertTrue(with_month_days(row)["messages"][0]["content"].endswith(f": {days}"))

    def test_duplicate_hint_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "context"):
            with_month_days(with_month_days(self.rows[0]))

    def test_invalid_current_date_is_rejected(self):
        row = deepcopy(self.rows[0])
        row["messages"][0]["content"] = row["messages"][0]["content"].replace("2028-02-28", "2030-02-29")
        with self.assertRaises(ValueError):
            with_month_days(row)

    def test_missing_or_extra_system_context_is_rejected(self):
        row = deepcopy(self.rows[0])
        row["messages"][0]["role"] = "user"
        with self.assertRaises(ValueError):
            with_month_days(row)
        row = deepcopy(self.rows[0])
        row["messages"].insert(1, deepcopy(row["messages"][0]))
        with self.assertRaises(ValueError):
            with_month_days(row)


class MonthDaysComparisonTest(unittest.TestCase):
    def setUp(self):
        self.rows = load_jsonl(DATA / "holdout.jsonl")
        self.predictions = [{"case_id": row["case_id"], "output": row["messages"][-1]["content"]} for row in self.rows]
        self.report = score_rows(self.rows, self.predictions)

    def test_hint_does_not_change_grading(self):
        report = score_rows([with_month_days(row) for row in self.rows], self.predictions)
        comparison = compare_reports(self.report, report)
        self.assertEqual(comparison["baseline_metrics"], comparison["month_days_metrics"])
        self.assertEqual([], comparison["changed_output_ids"])
        self.assertEqual([], comparison["newly_failing"])

    def test_improvement_and_regression_are_not_interchanged(self):
        predictions = deepcopy(self.predictions)
        answer = json.loads(predictions[0]["output"])
        answer["params"]["starts_at"] = "2028-03-01T16:00"
        predictions[0]["output"] = json.dumps(answer, ensure_ascii=False)
        wrong = score_rows(self.rows, predictions)
        self.assertEqual(["V122H01"], compare_reports(wrong, self.report)["newly_passing"])
        self.assertEqual(["V122H01"], compare_reports(self.report, wrong)["newly_failing"])

    def test_mismatched_ids_and_references_are_rejected(self):
        for field in ("case_id", "expected", "users"):
            report = deepcopy(self.report)
            report["cases"][0][field] = "changed"
            with self.assertRaises(ValueError):
                compare_reports(self.report, report)

    def test_duplicate_case_ids_are_rejected(self):
        report = deepcopy(self.report)
        report["cases"].append(deepcopy(report["cases"][0]))
        with self.assertRaises(ValueError):
            compare_reports(self.report, report)

    def test_saved_prediction_format_reproduces_previous_reports(self):
        run = ROOT / "build/calendar_sft_qwen3_v12_2_epoch1_20260907"
        if not (run / "holdout_predictions.jsonl").is_file():
            self.skipTest("local V12.2 evaluation artifacts are unavailable")
        for name in ("holdout", "regression_holdout"):
            rows = load_jsonl(DATA / f"{name}.jsonl")
            recorded = json.loads((run / f"{name}_report.json").read_text(encoding="utf-8"))
            report = score_saved_predictions(rows, run / f"{name}_predictions.jsonl")
            self.assertEqual(recorded["metrics"], report["metrics"])


if __name__ == "__main__":
    unittest.main()
