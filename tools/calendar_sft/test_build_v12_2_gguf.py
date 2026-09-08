"""Check selection and grading boundaries without generating model responses."""

from copy import deepcopy
import unittest

from build_v12_2_gguf import DATA, SMOKE_IDS, completion_text, select_calibration, select_smoke
from dataset_contract import load_jsonl, RUNTIME_SYSTEM_RE
from evaluate_v12_2_adapter import score_rows


class GgufPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.train = load_jsonl(DATA / "train.jsonl")
        cls.holdout = load_jsonl(DATA / "holdout.jsonl") + load_jsonl(DATA / "regression_holdout.jsonl")

    def test_calibration_is_256_unique_existing_train_rows(self):
        snapshot = deepcopy(self.train)
        indices = select_calibration(self.train)
        self.assertEqual(256, len(indices))
        self.assertEqual(256, len(set(indices)))
        self.assertTrue(all(0 <= index < len(self.train) for index in indices))
        self.assertEqual(snapshot, self.train)

    def test_all_train_categories_are_represented(self):
        selected = select_calibration(self.train)
        self.assertEqual({row["category"] for row in self.train}, {self.train[index]["category"] for index in selected})

    def test_selection_is_deterministic_and_validates_size(self):
        self.assertEqual(select_calibration(self.train), select_calibration(self.train))
        for count in (0, -1, len(self.train) + 1):
            with self.assertRaises(ValueError):
                select_calibration(self.train, count)

    def test_smoke_is_exactly_three_train_and_seven_existing_holdouts(self):
        snapshot = deepcopy((self.train, self.holdout))
        rows = select_smoke(self.train, self.holdout)
        self.assertEqual(10, len(rows))
        self.assertEqual(list(SMOKE_IDS), [row["case_id"] for row in rows[3:]])
        for row in rows[:3]:
            self.assertEqual(self.train[row["source_row_1based"] - 1]["messages"], row["messages"])
        for row in rows:
            self.assertIsNotNone(RUNTIME_SYSTEM_RE.fullmatch(row["messages"][0]["content"]))
        self.assertEqual(snapshot, (self.train, self.holdout))

    def test_all_smoke_references_pass_existing_grading(self):
        rows = select_smoke(self.train, self.holdout)
        predictions = [{"case_id": row["case_id"], "output": row["messages"][-1]["content"]} for row in rows]
        self.assertEqual(10, score_rows(rows, predictions)["metrics"]["strict_pass"])

    def test_only_a_terminal_runner_marker_can_be_removed(self):
        self.assertEqual('{"intent":"chat"}', completion_text(' \n{"intent":"chat"}\n [end of text]\n'))
        self.assertEqual('prefix {"intent":"chat"}', completion_text('prefix {"intent":"chat"}'))
        self.assertEqual('{"reply":"[end of text]"}', completion_text('{"reply":"[end of text]"}'))

    def test_duplicate_holdout_ids_are_rejected(self):
        with self.assertRaises(ValueError):
            select_smoke(self.train, self.holdout + [self.holdout[0]])


if __name__ == "__main__":
    unittest.main()
