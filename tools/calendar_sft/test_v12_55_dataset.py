"""Literal half-hour data, ambiguity, clock arithmetic and frozen-source checks."""

from collections import Counter
from copy import deepcopy
import json
import unittest

from dataset_contract import file_sha256, load_jsonl, normalized_user_prompt
from dataset_provenance import verify_dataset_provenance
from prepare_v12_51 import read_json
from prepare_v12_55 import (
    MANUAL, NEW_COUNTS, ORDERS, OUTPUT, SOURCE, SOURCE_HASHES, TOTAL_ROWS,
    declaration, read_half_clock, serialize_preserving_sources, validate_example,
    validate_inputs, validate_manual, word_order,
)


class V1255DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manual = read_json(MANUAL)
        cls.cases = {e["id"]: e for e in cls.manual["examples"]}
        cls.added, cls.outputs = validate_inputs()

    def test_complete_literal_conversations_and_balanced_training(self):
        self.assertEqual(NEW_COUNTS, {s: len(r) for s, r in self.added.items()})
        counts, orders, pairs = Counter(), Counter(), Counter()
        for split, rows in self.added.items():
            for row in rows:
                example = self.cases[row["case_id"]]
                self.assertEqual(example["system"], row["messages"][0]["content"])
                self.assertEqual(example["user"], row["messages"][1]["content"])
                self.assertEqual(example["assistant"], json.loads(row["messages"][2]["content"]))
                if split == "train":
                    _, hour, form = declaration(row["case_id"])
                    counts[form] += 1
                    orders[word_order(example)] += 1
                    pairs[hour, form] += 1
        self.assertEqual({form: 24 for form in range(1, 7)}, counts)
        self.assertEqual({order: 24 for order in ORDERS}, orders)
        self.assertEqual({(h, f): 1 for h in range(24) for f in range(1, 7)}, pairs)

    def test_original_bytes_are_preserved_in_all_splits(self):
        payloads = serialize_preserving_sources(self.added)
        for split, digest in SOURCE_HASHES.items():
            before = (SOURCE / f"{split}.jsonl").read_bytes()
            self.assertEqual(digest, file_sha256(SOURCE / f"{split}.jsonl"))
            self.assertTrue(payloads[split].startswith(before))
            if split not in ("train", "validation"):
                self.assertEqual(before, payloads[split])
        for split, payload in payloads.items():
            self.assertEqual(payload, (OUTPUT / f"{split}.jsonl").read_bytes())
            self.assertEqual(self.outputs[split], load_jsonl(OUTPUT / f"{split}.jsonl"))

    def test_half_hour_meaning_spelling_and_context(self):
        # Literal independent answers include noon, midnight and ASR spellings.
        cases = (
            ("полтретьего ночи", 1, 2), ("в полтретьего утра", 2, 2),
            ("пол третьего дня", 3, 14), ("завтра днём в пол третьего", 4, 14),
            ("половина третьего дня", 5, 14), ("в половине третьего ночи", 6, 2),
            ("полпервого ночи", 1, 0), ("в полпервого дня", 2, 12),
            ("пол двенадцатого дня", 3, 11), ("в пол двенадцатого ночи", 4, 23),
            ("половина двенадцатого утра", 5, 11), ("в половине первого ночи", 6, 0),
            ("Пол-одиннадцатого утра", 1, 10), ("в полодиннадцатого вечера", 2, 22),
            ("пол одиннадцатого вечера", 3, 22), ("в пол четвёртого дня", 4, 15),
            ("завтра утром половина седьмого", 5, 6), ("ночью в половине четвёртого", 6, 3),
        )
        for phrase, form, hour in cases:
            with self.subTest(phrase=phrase):
                self.assertEqual((form, hour), read_half_clock(phrase)[:2])

    def test_no_daypart_does_not_invent_am_or_pm(self):
        for number in range(1, 7):
            example = self.cases[f"V1255HC{number:02d}"]
            self.assertIsNone(read_half_clock(example["user"])[1])
            self.assertEqual({"title", "date", "duration_min"}, set(example["assistant"]["params"]))
            validate_example(example)
        bad = deepcopy(self.cases["V1255HC02"])
        bad["assistant"]["params"].pop("date")
        bad["assistant"]["params"]["starts_at"] = "2032-05-01T14:30"
        with self.assertRaisesRegex(ValueError, "unexpected command fields"):
            validate_example(bad)

    def test_wrong_named_hour_half_day_or_date_is_rejected(self):
        for wrong in ("2028-02-29T15:30", "2028-02-29T02:30", "2028-02-29T14:00", "2028-03-01T14:30"):
            bad = deepcopy(self.cases["V1255T1402"])
            bad["assistant"]["params"]["starts_at"] = wrong
            with self.subTest(wrong=wrong), self.assertRaisesRegex(ValueError, "wrong date or clock"):
                validate_example(bad)
        for case_id, expected in (
            ("V1255T0001", "2028-02-29T00:30"),
            ("V1255E1201", "2032-01-01T12:30"),
            ("V1255H2302", "2032-05-01T23:30"),
        ):
            self.assertEqual(expected, self.cases[case_id]["assistant"]["params"]["starts_at"])

    def test_clock_duration_and_offset_are_separate(self):
        for number, expected in (
            (7, "2032-05-01T16:00"), (8, "2032-05-01T08:00"), (9, "2032-05-01T21:00"),
            (10, "2032-05-01T00:20"), (11, "2032-05-01T00:10"), (12, "2032-05-01T00:25"),
        ):
            example = self.cases[f"V1255HC{number:02d}"]
            self.assertEqual(expected, example["assistant"]["params"]["starts_at"])
            validate_example(example)
        bad = deepcopy(self.cases["V1255HC10"])
        bad["assistant"]["params"]["starts_at"] = "2032-05-01T00:30"
        with self.assertRaisesRegex(ValueError, "wrong date or clock"):
            validate_example(bad)
        bad = deepcopy(self.cases["V1255T1404"])
        bad["assistant"]["params"]["duration_min"] = 60
        with self.assertRaisesRegex(ValueError, "wrong explicit duration"):
            validate_example(bad)

    def test_reply_time_daypart_title_and_duration_mutations_fail(self):
        original = self.cases["V1255T1402"]
        for reply in (
            "Осмотр макета завтра, в половине четвёртого, на четверть часа.",
            "Осмотр макета завтра, в половине третьего дня, на четверть часа.",
            "Осмотр макета завтра, в половине третьего, на полчаса.",
            "Проверка макета завтра, в половине третьего, на четверть часа.",
        ):
            bad = deepcopy(original)
            bad["assistant"]["reply"] = reply
            with self.subTest(reply=reply), self.assertRaises(ValueError):
                validate_example(bad)

    def test_conflicting_context_or_two_clock_phrases_fail(self):
        for phrase in (
            "утром в полтретьего дня", "полтретьего ночи или половина третьего дня",
            "в половина третьего дня", "пол-третьего дня",
        ):
            with self.subTest(phrase=phrase), self.assertRaises(ValueError):
                read_half_clock(phrase)
        for phrase in ("на полчаса", "через половину часа", "на пол часа"):
            with self.subTest(phrase=phrase), self.assertRaises(ValueError):
                read_half_clock(phrase)

    def test_missing_duplicate_and_unbalanced_word_order_fail(self):
        bad = deepcopy(self.manual)
        bad["examples"].pop(0)
        with self.assertRaisesRegex(ValueError, "incomplete or unbalanced"):
            validate_manual(bad)
        bad = deepcopy(self.manual)
        bad["examples"].append(deepcopy(bad["examples"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate manual case ID"):
            validate_manual(bad)
        bad = deepcopy(self.manual)
        changed = next(e for e in bad["examples"] if e["id"] == "V1255T1402")
        changed["user"] = "Поставь осмотр макета завтра в полтретьего дня на четверть часа."
        with self.assertRaisesRegex(ValueError, "six word orders"):
            validate_manual(bad)

    def test_new_holdout_has_no_fitting_prompt_or_case_id(self):
        fitting = [row for split in ("train", "validation") for row in self.outputs[split]]
        prompts = {normalized_user_prompt(row) for row in fitting}
        ids = {row.get("case_id") for row in fitting}
        self.assertEqual(36, len(self.outputs["half_hour_holdout"]))
        for row in self.outputs["half_hour_holdout"]:
            self.assertNotIn(normalized_user_prompt(row), prompts)
            self.assertNotIn(row["case_id"], ids)

    def test_manifest_and_provenance_bind_all_output_files(self):
        manifest = read_json(OUTPUT / "manifest.json")
        self.assertEqual("v12.55", manifest["version"])
        self.assertEqual(TOTAL_ROWS, manifest["total_rows"])
        self.assertEqual(file_sha256(MANUAL), manifest["manual_sha256"])
        for split in TOTAL_ROWS:
            self.assertEqual(file_sha256(OUTPUT / f"{split}.jsonl"), manifest["artifacts"][split]["sha256"])
        register = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual("aiassistent1-assistant-sft-v12.55", register["dataset_id"])
        self.assertEqual("NOT_RUN", manifest["training"])
        self.assertEqual("NOT_RUN", manifest["model_evaluation"])


if __name__ == "__main__":
    unittest.main()
