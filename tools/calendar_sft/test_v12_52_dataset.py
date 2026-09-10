"""Independent clock assertions and frozen-source checks for V12.52."""

from collections import Counter
from copy import deepcopy
import json
import unittest

from dataset_contract import DatasetContractError, file_sha256, normalize_record
from dataset_provenance import verify_dataset_provenance
from prepare_v12_52 import (
    MANUAL, OUTPUT, SOURCE, SOURCE_HASHES, literal_row, read_json,
    read_minutes_of_hour, read_reply_clock, validate_example,
    validate_inputs, validate_manual,
)


class V1252DatasetTest(unittest.TestCase):
    def setUp(self):
        self.manual = read_json(MANUAL)

    def test_complete_grid_balanced_halves_and_expected_totals(self):
        added, outputs = validate_inputs()
        self.assertEqual({"train": 132, "validation": 36}, {s: len(rows) for s, rows in added.items()})
        self.assertEqual(
            {"train": 2136, "validation": 647, "holdout": 36,
             "calendar_holdout_v12_2": 8, "regression_holdout": 63},
            {s: len(rows) for s, rows in outputs.items()},
        )
        for split, expected_minutes, expected_half in (
            ("train", {5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55}, 66),
            ("validation", {5, 30, 55}, 18),
        ):
            examples = [e for e in self.manual["examples"] if e["split"] == split]
            for hour in range(1, 13):
                self.assertEqual(expected_minutes, {e["minute"] for e in examples if e["next_hour"] == hour})
            halves = Counter(int(e["assistant"]["params"]["starts_at"][11:13]) < 12 for e in examples)
            self.assertEqual({True: expected_half, False: expected_half}, dict(halves))

    def test_all_eleven_minute_values_have_independent_numerical_assertions(self):
        for phrase, clock in (
            ("пять минут первого ночи", "00:05"),
            ("десять минут первого ночи", "00:10"),
            ("пятнадцать минут первого ночи", "00:15"),
            ("двадцать минут первого ночи", "00:20"),
            ("двадцать пять минут первого ночи", "00:25"),
            ("тридцать минут первого ночи", "00:30"),
            ("тридцать пять минут первого ночи", "00:35"),
            ("сорок минут первого ночи", "00:40"),
            ("сорок пять минут первого ночи", "00:45"),
            ("пятьдесят минут первого ночи", "00:50"),
            ("пятьдесят пять минут первого ночи", "00:55"),
        ):
            with self.subTest(phrase=phrase):
                self.assertEqual(clock, read_minutes_of_hour(phrase)[3])

    def test_literal_answers_against_handwritten_twenty_four_hour_table(self):
        expected_hours = {
            1: {"ночи": "00", "дня": "12"}, 2: {"ночи": "01", "дня": "13"},
            3: {"ночи": "02", "дня": "14"}, 4: {"ночи": "03", "дня": "15"},
            5: {"утра": "04", "дня": "16"}, 6: {"утра": "05", "вечера": "17"},
            7: {"утра": "06", "вечера": "18"}, 8: {"утра": "07", "вечера": "19"},
            9: {"утра": "08", "вечера": "20"}, 10: {"утра": "09", "вечера": "21"},
            11: {"утра": "10", "вечера": "22"}, 12: {"дня": "11", "ночи": "23"},
        }
        for example in self.manual["examples"]:
            day = "2027-04-13" if example["split"] == "train" else "2028-02-29"
            hour = expected_hours[example["next_hour"]][example["period"]]
            with self.subTest(case_id=example["id"]):
                self.assertEqual(
                    f"{day}T{hour}:{example['minute']:02d}",
                    example["assistant"]["params"]["starts_at"],
                )

    def test_noon_midnight_and_spelling_are_distinguished(self):
        for phrase, clock in (
            ("пять минут первого дня", "12:05"),
            ("пять минут первого ночи", "00:05"),
            ("пятьдесят пять минут двенадцатого дня", "11:55"),
            ("пятьдесят пять минут двенадцатого ночи", "23:55"),
            ("пятнадцать минут четвёртого дня", "15:15"),
            ("ПЯТНАДЦАТЬ МИНУТ ЧЕТВЕРТОГО ДНЯ", "15:15"),
        ):
            self.assertEqual(clock, read_minutes_of_hour(phrase)[3])

    def test_reply_quarter_half_and_before_quarter_keep_the_same_time(self):
        for phrase, minute, clock in (
            ("в четверть первого дня", 15, "12:15"),
            ("в половине первого ночи", 30, "00:30"),
            ("без четверти час ночи", 45, "00:45"),
            ("без четверти двенадцать дня", 45, "11:45"),
            ("без четверти двенадцать ночи", 45, "23:45"),
            ("в пятьдесят пять минут двенадцатого ночи", 55, "23:55"),
        ):
            self.assertEqual(clock, read_reply_clock(phrase, minute))
        for phrase, minute in (
            ("в половине первого дня", 15), ("в четверть первого дня", 30),
            ("в без четверти час ночи", 45), ("в четверть третьего дня", 20),
        ):
            with self.assertRaises(ValueError):
                read_reply_clock(phrase, minute)

    def test_duration_and_incomplete_or_out_of_range_phrases_are_not_clock_assertions(self):
        for phrase in (
            "пять минут", "на пять минут", "через десять минут",
            "пять минут первого", "ноль минут первого ночи",
            "шестьдесят минут первого ночи", "пять минут тринадцатого дня",
            "пять минут первого ночислово",
        ):
            with self.subTest(phrase=phrase), self.assertRaises(ValueError):
                read_minutes_of_hour(phrase)

    def test_wrong_hours_rounding_and_midnight_date_shift_are_rejected(self):
        for case_id, wrong in (
            ("V1252T0105", "2027-04-13T01:05"),
            ("V1252T0105", "2027-04-13T12:05"),
            ("V1252T0120", "2027-04-13T12:15"),
            ("V1252T1245", "2027-04-14T00:45"),
            ("V1252T1255", "2027-04-14T23:55"),
            ("V1252E1230", "2028-03-01T23:30"),
        ):
            example = deepcopy(next(e for e in self.manual["examples"] if e["id"] == case_id))
            example["assistant"]["params"]["starts_at"] = wrong
            with self.subTest(case_id=case_id), self.assertRaisesRegex(ValueError, "wrong date or clock time"):
                validate_example(example)

    def test_explicit_duration_is_independent_of_the_minute_in_clock_time(self):
        example = deepcopy(next(e for e in self.manual["examples"] if e["id"] == "V1252T0325"))
        self.assertEqual(10, example["assistant"]["params"]["duration_min"])
        self.assertEqual("2027-04-13T02:25", example["assistant"]["params"]["starts_at"])
        example["duration_min"] = 25
        example["assistant"]["params"]["duration_min"] = 25
        with self.assertRaisesRegex(ValueError, "wrong explicit duration"):
            validate_example(example)

    def test_wrong_reply_minute_missing_daypart_and_false_metadata_are_rejected(self):
        example = deepcopy(self.manual["examples"][0])
        example["assistant"]["reply"] = example["assistant"]["reply"].replace("пять минут", "десять минут")
        with self.assertRaisesRegex(ValueError, "reply time or duration"):
            validate_example(example)
        example = deepcopy(self.manual["examples"][0])
        example["user"] = example["user"].replace("первого ночи", "первого")
        with self.assertRaisesRegex(ValueError, "one explicit clock"):
            validate_example(example)
        example = deepcopy(self.manual["examples"][0])
        example["period"] = "дня"
        with self.assertRaisesRegex(ValueError, "contradicts its metadata"):
            validate_example(example)

    def test_missing_combination_and_duplicate_id_are_rejected(self):
        manual = deepcopy(self.manual)
        manual["examples"].pop()
        with self.assertRaisesRegex(ValueError, "all 132 train combinations"):
            validate_manual(manual)
        manual = deepcopy(self.manual)
        manual["examples"].append(deepcopy(manual["examples"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate manual ID"):
            validate_manual(manual)

    def test_source_bytes_manual_suffix_and_provenance_are_preserved(self):
        added = validate_manual(self.manual)
        for split, digest in SOURCE_HASHES.items():
            self.assertEqual(digest, file_sha256(SOURCE / f"{split}.jsonl"))
            original = (SOURCE / f"{split}.jsonl").read_bytes()
            prepared = (OUTPUT / f"{split}.jsonl").read_bytes()
            self.assertTrue(prepared.startswith(original))
            self.assertEqual(added.get(split, []), [json.loads(line) for line in prepared[len(original):].splitlines()])
            if split not in added:
                self.assertEqual(original, prepared)
        result = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual("aiassistent1-assistant-sft-v12.52", result["dataset_id"])
        manifest = read_json(OUTPUT / "manifest.json")
        self.assertEqual(file_sha256(MANUAL), manifest["manual_sha256"])
        for split in SOURCE_HASHES:
            self.assertEqual(file_sha256(OUTPUT / f"{split}.jsonl"), manifest["artifacts"][split]["sha256"])

    def test_all_literal_messages_survive_serialization_without_repair(self):
        for example in self.manual["examples"]:
            row = validate_example(example)
            self.assertEqual(example["system"], row["messages"][0]["content"])
            self.assertEqual(example["user"], row["messages"][1]["content"])
            self.assertEqual(example["assistant"], json.loads(row["messages"][-1]["content"]))

    def test_reply_rules_do_not_rewrite_literal_titles_or_numeric_parameters(self):
        example = deepcopy(self.manual["examples"][0])
        example["user"] = "Запиши «План — 2033» завтра в пять минут первого ночи на двадцать минут."
        example["assistant"]["params"]["title"] = "План — 2033"
        normalized = normalize_record(literal_row(example), "scope")
        self.assertEqual(example["assistant"]["params"], json.loads(normalized["messages"][-1]["content"])["params"])
        self.assertEqual(example["user"], normalized["messages"][1]["content"])
        example["assistant"]["reply"] = "Встреча в 00:05."
        with self.assertRaises(DatasetContractError):
            normalize_record(literal_row(example), "scope")


if __name__ == "__main__":
    unittest.main()
