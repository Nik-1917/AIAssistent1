import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
DATASET = ROOT / "docs" / "calendar_sft_v12_4"
ALLOWED = {
    "chat",
    "calendar_add",
    "calendar_search",
    "calendar_update",
    "calendar_delete",
    "calendar_sum",
}


def rows(name):
    path = DATASET / name
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def intent(row):
    assistant = next(message for message in row["messages"] if message["role"] == "assistant")
    return json.loads(assistant["content"])["intent"]


class V12_4DatasetTest(unittest.TestCase):
    def test_current_training_and_validation_have_no_removed_intent(self):
        for name in ("train.jsonl", "validation.jsonl"):
            values = [intent(row) for row in rows(name)]
            self.assertTrue(set(values) <= ALLOWED)
            self.assertNotIn("note_add", values)

    def test_manual_filter_counts(self):
        self.assertEqual(1950, len(rows("train.jsonl")))
        self.assertEqual(581, len(rows("validation.jsonl")))
        self.assertEqual(36, len(rows("holdout.jsonl")))


if __name__ == "__main__":
    unittest.main()
