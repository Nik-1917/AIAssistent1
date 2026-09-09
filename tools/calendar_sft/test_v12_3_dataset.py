"""Check the handwritten word-order layer and preservation of V12.2 data."""

from collections import Counter
from copy import deepcopy
import json
import unittest

from dataset_contract import DatasetContractError, file_sha256, load_jsonl, normalize_record
from dataset_provenance import verify_dataset_provenance
from prepare_v12_3 import (
    FAMILY_COUNTS, MANUAL, ORDERS, OUTPUT, SOURCE, SOURCE_HASHES,
    observed_order, validate_family, validate_inputs, validate_manual,
)


class V123DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manual = json.loads(MANUAL.read_text(encoding="utf-8"))
        cls.families = {f["id"]: f for f in cls.manual["families"]}
        cls.manifest = json.loads((OUTPUT / "manifest.json").read_text(encoding="utf-8"))
        cls.splits = {name: load_jsonl(OUTPUT / f"{name}.jsonl") for name in cls.manifest["total_rows"]}

    def test_exact_counts_and_checksums(self):
        expected = {"train": 1964, "validation": 585, "holdout": 36, "calendar_holdout_v12_2": 8, "regression_holdout": 63}
        self.assertEqual(expected, self.manifest["total_rows"])
        for name, rows in self.splits.items():
            with self.subTest(split=name):
                self.assertEqual(expected[name], len(rows))
                self.assertEqual(self.manifest["artifacts"][name]["sha256"], file_sha256(OUTPUT / f"{name}.jsonl"))
        self.assertEqual(self.manifest["manual_sha256"], file_sha256(MANUAL))

    def test_inherited_data_are_byte_for_byte_unchanged(self):
        for name in ("train", "validation"):
            self.assertTrue((OUTPUT / f"{name}.jsonl").read_bytes().startswith((SOURCE / f"{name}.jsonl").read_bytes()))
        for output, source in (("calendar_holdout_v12_2", "holdout"), ("regression_holdout", "regression_holdout")):
            self.assertEqual((SOURCE / f"{source}.jsonl").read_bytes(), (OUTPUT / f"{output}.jsonl").read_bytes())
        for name, expected in SOURCE_HASHES.items():
            self.assertEqual(expected, file_sha256(SOURCE / f"{name}.jsonl"))

    def test_all_sentences_and_responses_match_manual_source(self):
        written = {v["id"]: (f, v) for f in self.families.values() for v in f["variants"]}
        encountered = set()
        for split, rows in self.splits.items():
            for row in rows:
                if not row.get("case_id", "").startswith("V123"):
                    continue
                family, variant = written[row["case_id"]]
                self.assertEqual(family["split"], split)
                self.assertEqual(3, len(row["messages"]))
                self.assertEqual(variant["user"], row["messages"][1]["content"])
                self.assertEqual(family["response"], json.loads(row["messages"][-1]["content"]))
                self.assertEqual("v12.1", row["contract_version"])
                self.assertNotIn("V123", row["messages"][0]["content"])
                encountered.add(row["case_id"])
        self.assertEqual(set(written), encountered)
        self.assertEqual(132, len(encountered))

    def test_equal_observed_orders_in_each_split(self):
        for split, quota in FAMILY_COUNTS.items():
            counts = Counter()
            for family in self.families.values():
                if family["split"] == split:
                    for variant in family["variants"]:
                        counts[observed_order(variant["user"], family["spans"])] += 1
            self.assertEqual({order: quota for order in ORDERS}, counts)
            self.assertEqual(counts, self.manifest["manual_order_counts"][split])

    def test_complete_validation_and_no_split_leakage(self):
        _, added, outputs = validate_inputs()
        self.assertEqual({"train": 60, "validation": 36, "holdout": 36}, {k: len(v) for k, v in added.items()})
        self.assertEqual(self.splits, outputs)

    def test_calendar_boundary_and_weekday_oracles(self):
        expected = {
            "V123T05": "2027-08-13T10:00", "V123T06": "2028-10-05T09:00",
            "V123T09": "2032-01-01T08:00", "V123T10": "2028-03-01T18:05",
            "V123E01": "2028-01-01T09:50", "V123E02": "2032-03-01T18:00",
            "V123E03": "2029-05-01T16:15", "V123E05": "2031-03-15T14:35",
            "V123H01": "2030-01-01T13:25", "V123H02": "2030-03-01T20:00",
            "V123H03": "2028-11-01T07:10", "V123H05": "2027-07-11T17:55",
        }
        for family_id, timestamp in expected.items():
            with self.subTest(family=family_id):
                self.assertEqual(timestamp, self.families[family_id]["response"]["params"]["starts_at"])

    def test_spoken_clock_oracles(self):
        clocks = {
            "в пятнадцать двадцать": "15:20", "в семь вечера": "19:00",
            "в восемь сорок пять утра": "08:45", "в пять тридцать вечера": "17:30",
            "в десять часов утра": "10:00", "в девять утра": "09:00",
            "в двенадцать сорок": "12:40", "в полдень": "12:00",
            "в восемь утра": "08:00", "в восемнадцать ноль пять": "18:05",
            "в девять пятьдесят утра": "09:50", "в шесть вечера": "18:00",
            "в шестнадцать пятнадцать": "16:15", "в одиннадцать утра": "11:00",
            "в четырнадцать тридцать пять": "14:35", "в десять утра": "10:00",
            "в тринадцать двадцать пять": "13:25", "в восемь вечера": "20:00",
            "в семь десять утра": "07:10", "в половине третьего дня": "14:30",
            "в семнадцать пятьдесят пять": "17:55", "в два часа дня": "14:00",
        }
        for family in self.families.values():
            expected = clocks[family["spans"]["time"]]
            self.assertEqual(expected, family["clock"])
            self.assertTrue(family["response"]["params"]["starts_at"].endswith("T" + expected))

    def test_incorrect_order_and_missing_fact_are_rejected(self):
        for change in ("label", "time"):
            family = deepcopy(self.families["V123T01"])
            if change == "label":
                family["variants"][0]["user"] = family["variants"][1]["user"]
            else:
                family["variants"][0]["user"] = "Запиши встречу с редактором завтра."
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_family(family)

    def test_lost_or_duplicate_orders_are_rejected(self):
        family = deepcopy(self.families["V123T01"])
        family["variants"][0]["order"] = "time_day_title"
        with self.assertRaisesRegex(ValueError, "six orders"):
            validate_family(family)

    def test_same_event_cannot_be_reused_across_splits(self):
        manual = deepcopy(self.manual)
        target = next(f for f in manual["families"] if f["split"] == "holdout")
        target["response"] = deepcopy(manual["families"][0]["response"])
        with self.assertRaisesRegex(ValueError, "one family and one split"):
            validate_manual(manual)

    def test_calendar_error_is_rejected_without_repair(self):
        family = deepcopy(self.families["V123T10"])
        family["response"]["params"]["starts_at"] = "2028-03-02T18:05"
        saved = deepcopy(family)
        with self.assertRaisesRegex(ValueError, "disagrees with calendar"):
            validate_family(family)
        self.assertEqual(saved, family)

    def test_extra_params_and_scheduling_reply_are_rejected(self):
        for change in ("duration_min", "value", "reply"):
            family = deepcopy(self.families["V123T01"])
            if change == "reply":
                family["response"]["reply"] = "Встреча с редактором завтра."
            else:
                family["response"]["params"][change] = 30
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_family(family)

    def test_existing_spelled_clock_rule_is_not_bypassed(self):
        row = deepcopy(self.splits["holdout"][0])
        row["messages"][1]["content"] = "Добавь проверку домофона завтра в 13:25."
        with self.assertRaisesRegex(DatasetContractError, "spell clock times in words"):
            normalize_record(row, "numeric-clock")

    def test_literal_temporal_titles_survive_all_six_orders(self):
        for family_id, title in (("V123T09", "Вечерняя прогулка"), ("V123E06", 'Лекция "Завтра"')):
            rows = validate_family(self.families[family_id])
            self.assertEqual(6, len(rows))
            for row in rows:
                answer = json.loads(row["messages"][-1]["content"])
                self.assertEqual(title, answer["params"]["title"])
                self.assertEqual(title + ".", answer["reply"])

    def test_oversized_sentence_is_rejected(self):
        family = deepcopy(self.families["V123T01"])
        family["variants"][0]["user"] += " " + "пожалуйста " * 15
        with self.assertRaisesRegex(ValueError, "fourteen words"):
            validate_family(family)

    def test_provenance_and_unstarted_training(self):
        result = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual("aiassistent1-assistant-sft-v12.3-20260909", result["dataset_id"])
        for action in ("data_generator", "training", "model_evaluation", "gguf_conversion", "android_validation"):
            self.assertEqual("NOT_RUN", self.manifest[action])


if __name__ == "__main__":
    unittest.main()
