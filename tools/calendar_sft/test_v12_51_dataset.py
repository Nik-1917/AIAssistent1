"""Checks for the handwritten V12.51 clock labels, quotas and frozen base."""

from collections import Counter
from copy import deepcopy
import json
import unittest

from dataset_contract import DatasetContractError, file_sha256, normalize_record
from dataset_provenance import verify_dataset_provenance
from prepare_v12_51 import (
    MANUAL, OUTPUT, SOURCE, SOURCE_HASHES, literal_row, read_json,
    read_on_hour_phrase, validate_inputs, validate_manual,
)


class V1251DatasetTest(unittest.TestCase):
    def setUp(self):
        self.manual = read_json(MANUAL)

    def test_all_hours_have_two_train_forms_and_one_validation_form(self):
        added, outputs = validate_inputs()
        self.assertEqual({"train": 48, "validation": 24}, {s: len(rows) for s, rows in added.items()})
        self.assertEqual(
            {"train": 2004, "validation": 611, "holdout": 36,
             "calendar_holdout_v12_2": 8, "regression_holdout": 63},
            {s: len(rows) for s, rows in outputs.items()},
        )
        counts = Counter((e["split"], e["hour"]) for e in self.manual["examples"])
        for hour in range(24):
            self.assertEqual(2, counts["train", hour])
            self.assertEqual(1, counts["validation", hour])

    def test_every_hour_against_independent_clock_assertions(self):
        expected = (
            ("час ноль ноль", 1), ("два ноль ноль", 2),
            ("три ноль ноль", 3), ("четыре ноль ноль", 4),
            ("пять ноль ноль", 5), ("шесть ноль ноль", 6),
            ("семь ноль ноль", 7), ("восемь ноль ноль", 8),
            ("девять ноль ноль", 9), ("десять ноль ноль", 10),
            ("одиннадцать ноль ноль", 11), ("двенадцать ноль ноль", 12),
            ("тринадцать ноль ноль", 13), ("четырнадцать ноль ноль", 14),
            ("пятнадцать ноль ноль", 15), ("шестнадцать ноль ноль", 16),
            ("семнадцать ноль ноль", 17), ("восемнадцать ноль ноль", 18),
            ("девятнадцать ноль ноль", 19), ("двадцать ноль ноль", 20),
            ("двадцать один час ноль ноль минут", 21),
            ("двадцать два часа ноль ноль минут", 22),
            ("двадцать три часа ноль ноль минут", 23),
            ("ноль часов ноль ноль минут", 0),
        )
        for phrase, hour in expected:
            with self.subTest(phrase=phrase):
                self.assertEqual(hour, read_on_hour_phrase(phrase))
        for phrase in ("один ноль ноль", "один час ноль ноль минут", "час ноль ноль минут"):
            self.assertEqual(1, read_on_hour_phrase(phrase))
        self.assertEqual(0, read_on_hour_phrase("ноль ноль ноль"))
        self.assertEqual(20, read_on_hour_phrase("двадцать ноль ноль минут"))

    def test_rejects_incomplete_clock_bad_units_durations_and_hour_twenty_four(self):
        for phrase in (
            "двадцать ноль", "ноль ноль", "двадцать четыре ноль ноль",
            "двадцать ноль пять", "двадцать часов ноль минут", "два часов ноль ноль",
            "двадцать один часов ноль ноль минут", "час час ноль ноль",
            "полчаса", "четверть часа", "через час", "половина часа",
        ):
            with self.subTest(phrase=phrase), self.assertRaises(ValueError):
                read_on_hour_phrase(phrase)

    def test_handwritten_dates_and_durations_against_independent_expectations(self):
        # Hour, compact train duration, expanded train duration, validation duration.
        expected = (
            (0, 30, 30, 30), (1, 20, 15, 20), (2, 30, 20, 30), (3, 40, 30, 40),
            (4, 60, 40, 15), (5, 10, 25, 25), (6, 15, 45, 20), (7, 25, 60, 35),
            (8, 20, 35, 45), (9, 45, 15, 15), (10, 30, 20, 30), (11, 15, 30, 40),
            (12, 40, 40, 20), (13, 60, 25, 25), (14, 35, 45, 35), (15, 45, 60, 45),
            (16, 20, 35, 15), (17, 30, 10, 20), (18, 25, 15, 30), (19, 15, 20, 40),
            (20, 30, 30, 15), (21, 20, 40, 25), (22, 10, 25, 20), (23, 15, 15, 15),
        )
        by_id = {e["id"]: e for e in self.manual["examples"]}
        for hour, compact, expanded, validation in expected:
            for case_id, day, duration in (
                (f"V1251T{hour:02d}S", "2027-04-13", compact),
                (f"V1251T{hour:02d}F", "2028-03-01", expanded),
                (f"V1251E{hour:02d}", "2028-01-01", validation),
            ):
                params = by_id[case_id]["assistant"]["params"]
                with self.subTest(case_id=case_id):
                    self.assertEqual(
                        (f"{day}T{hour:02d}:00", duration),
                        (params["starts_at"], params["duration_min"]),
                    )

    def test_wrong_date_hour_minute_and_duration_are_rejected(self):
        for field, value in (
            ("starts_at", "2027-04-14T20:00"),
            ("starts_at", "2027-04-13T02:00"),
            ("starts_at", "2027-04-13T20:30"),
            ("starts_at", "2027-04-13T24:00"),
            ("duration_min", 0),
        ):
            manual = deepcopy(self.manual)
            example = next(e for e in manual["examples"] if e["id"] == "V1251T20S")
            example["assistant"]["params"][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                validate_manual(manual)

    def test_midnight_cannot_silently_move_to_the_following_date(self):
        manual = deepcopy(self.manual)
        example = next(e for e in manual["examples"] if e["id"] == "V1251E00")
        example["assistant"]["params"]["starts_at"] = "2028-01-02T00:00"
        with self.assertRaisesRegex(ValueError, "wrong date or clock time"):
            validate_manual(manual)

    def test_wrong_spoken_reply_and_duration_metadata_are_rejected(self):
        manual = deepcopy(self.manual)
        example = next(e for e in manual["examples"] if e["id"] == "V1251T20S")
        example["assistant"]["reply"] = example["assistant"]["reply"].replace("в двадцать часов", "в два часа")
        with self.assertRaisesRegex(ValueError, "reply time or duration"):
            validate_manual(manual)
        manual = deepcopy(self.manual)
        manual["examples"][0]["duration_min"] = 15
        manual["examples"][0]["assistant"]["params"]["duration_min"] = 15
        with self.assertRaisesRegex(ValueError, "wrong explicit duration"):
            validate_manual(manual)

    def test_missing_hour_duplicate_id_and_mislabeled_form_are_rejected(self):
        manual = deepcopy(self.manual)
        manual["examples"].pop()
        with self.assertRaisesRegex(ValueError, "exactly one example per hour"):
            validate_manual(manual)
        manual = deepcopy(self.manual)
        manual["examples"].append(deepcopy(manual["examples"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate ID"):
            validate_manual(manual)
        manual = deepcopy(self.manual)
        manual["examples"][0]["phrase"] = "один час ноль ноль минут"
        with self.assertRaisesRegex(ValueError, "declared form"):
            validate_manual(manual)

    def test_unrecognized_phrase_and_wrong_weekday_are_rejected(self):
        manual = deepcopy(self.manual)
        manual["examples"][0]["phrase"] = "час ноль"
        with self.assertRaisesRegex(ValueError, "unrecognized whole-hour"):
            validate_manual(manual)
        manual = deepcopy(self.manual)
        manual["examples"][0]["system"] = manual["examples"][0]["system"].replace("понедельник", "вторник")
        with self.assertRaisesRegex(ValueError, "wrong system weekday"):
            validate_manual(manual)

    def test_serialization_preserves_all_handwritten_messages(self):
        added = validate_manual(self.manual)
        by_id = {row["case_id"]: row for rows in added.values() for row in rows}
        for example in self.manual["examples"]:
            row = by_id[example["id"]]
            self.assertEqual("v12.5", row["contract_version"])
            self.assertEqual(example["system"], row["messages"][0]["content"])
            self.assertEqual(example["user"], row["messages"][1]["content"])
            self.assertEqual(example["assistant"], json.loads(row["messages"][2]["content"]))

    def test_prepared_data_preserves_base_bytes_and_exact_manual_suffix(self):
        added = validate_manual(self.manual)
        for split, digest in SOURCE_HASHES.items():
            self.assertEqual(digest, file_sha256(SOURCE / f"{split}.jsonl"))
            original = (SOURCE / f"{split}.jsonl").read_bytes()
            prepared = (OUTPUT / f"{split}.jsonl").read_bytes()
            self.assertTrue(prepared.startswith(original))
            actual_suffix = [json.loads(line) for line in prepared[len(original):].splitlines()]
            self.assertEqual(added.get(split, []), actual_suffix)
            if split not in added:
                self.assertEqual(original, prepared)
        result = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual("aiassistent1-assistant-sft-v12.51", result["dataset_id"])
        manifest = read_json(OUTPUT / "manifest.json")
        self.assertEqual(file_sha256(MANUAL), manifest["manual_sha256"])
        for split in SOURCE_HASHES:
            self.assertEqual(file_sha256(OUTPUT / f"{split}.jsonl"), manifest["artifacts"][split]["sha256"])

    def test_reply_scope_does_not_convert_numeric_fields_or_user_text(self):
        example = deepcopy(self.manual["examples"][0])
        example["assistant"]["params"]["title"] = "Проект «2032» — 20:00"
        example["user"] = "Запиши Проект «2032» — 20:00 завтра в 01:00 на двадцать минут."
        normalized = normalize_record(literal_row(example), "scope")
        self.assertEqual(example["user"], normalized["messages"][1]["content"])
        self.assertEqual(example["assistant"], json.loads(normalized["messages"][-1]["content"]))
        example["assistant"]["reply"] = "Проверка в 01:00."
        with self.assertRaises(DatasetContractError):
            normalize_record(literal_row(example), "scope")


if __name__ == "__main__":
    unittest.main()
