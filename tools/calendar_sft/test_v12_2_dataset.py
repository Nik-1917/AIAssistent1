"""Tests of the fixed handwritten V12.2 layer, without any data generation."""

import calendar
from collections import Counter
from copy import deepcopy
from datetime import datetime
import json
import unittest

from dataset_contract import file_sha256, load_jsonl
from dataset_provenance import verify_dataset_provenance
from prepare_v12_2 import MANUAL, OUTPUT, SOURCE, SOURCE_HASHES, validate_inputs, validate_manual_case


class V122DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = json.loads(MANUAL.read_text(encoding="utf-8"))["cases"]
        cls.manifest = json.loads((OUTPUT / "manifest.json").read_text(encoding="utf-8"))

    def test_all_48_calendar_assertions_and_split_checks(self):
        sources, added = validate_inputs()
        self.assertEqual({"train": 32, "validation": 8, "holdout": 8}, {k: len(v) for k, v in added.items()})
        self.assertEqual(1872, len(sources["train"]))

    def test_each_split_is_balanced_between_leap_and_common_years(self):
        counts = Counter((c["split"], calendar.isleap(datetime.fromisoformat(c["now"]).year)) for c in self.cases)
        for split, total in (("train", 32), ("validation", 8), ("holdout", 8)):
            self.assertEqual(total // 2, counts[split, True])
            self.assertEqual(total // 2, counts[split, False])

    def test_all_approved_years_and_only_those_years_are_present(self):
        counts = Counter(datetime.fromisoformat(c["now"]).year for c in self.cases)
        self.assertEqual({2027: 6, 2028: 12, 2029: 6, 2030: 6, 2031: 6, 2032: 12}, counts)

    def test_preexisting_examples_are_byte_for_byte_unchanged(self):
        for split in ("train", "validation"):
            original = (SOURCE / f"{split}.jsonl").read_bytes()
            self.assertTrue((OUTPUT / f"{split}.jsonl").read_bytes().startswith(original))
        self.assertEqual((SOURCE / "holdout.jsonl").read_bytes(), (OUTPUT / "regression_holdout.jsonl").read_bytes())
        for split, expected in SOURCE_HASHES.items():
            self.assertEqual(expected, file_sha256(SOURCE / f"{split}.jsonl"))

    def test_artifact_sizes_hashes_and_unchanged_contract(self):
        self.assertEqual("v12.2", self.manifest["version"])
        for split, expected_count in (("train", 1904), ("validation", 549), ("holdout", 8), ("regression_holdout", 63)):
            path = OUTPUT / f"{split}.jsonl"
            rows = load_jsonl(path)
            self.assertEqual(expected_count, len(rows))
            self.assertEqual(self.manifest["artifacts"][split]["sha256"], file_sha256(path))
            self.assertTrue(all(row["contract_version"] == "v12.1" for row in rows))
        self.assertEqual(self.manifest["manual_sha256"], file_sha256(MANUAL))

    def test_a_wrong_leap_day_is_rejected_not_repaired(self):
        case = deepcopy(self.cases[0])
        case["check"]["expected"] = "2028-03-01T15:00"
        case["response"]["params"]["starts_at"] = "2028-03-01T15:00"
        with self.assertRaisesRegex(ValueError, "disagrees with calendar"):
            validate_manual_case(case)

    def test_a_response_cannot_invent_or_lose_fields(self):
        case = deepcopy(self.cases[1])
        case["response"]["params"]["duration_min"] = 60
        with self.assertRaisesRegex(ValueError, "unexpected field"):
            validate_manual_case(case)
        case = deepcopy(self.cases[0])
        case["response"]["reply"] = "Встреча с тренером завтра."
        with self.assertRaisesRegex(ValueError, "scheduling metadata"):
            validate_manual_case(case)

    def test_exact_provenance_binding(self):
        result = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual("aiassistent1-assistant-sft-v12.2-20260907", result["dataset_id"])


if __name__ == "__main__":
    unittest.main()
