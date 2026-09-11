"""V12.54 literal reply scope, clock meaning and historical-data regression checks."""

from collections import Counter
from copy import deepcopy
from datetime import datetime
import json
import unittest

from dataset_contract import file_sha256, load_jsonl
from dataset_provenance import verify_dataset_provenance
from prepare_v12_54 import (
    EDIT_COUNTS, OUTPUT, REPLY_EDITS, SOURCE, SOURCE_HASHES, TOTAL_ROWS,
    TO_CLOCK_RE, apply_reply_edits, read_manual, serialize_preserving_sources,
    validate_inputs, validate_reply_edit,
)


class V1254DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manual = read_manual()
        cls.sources = {s: load_jsonl(SOURCE / f"{s}.jsonl") for s in SOURCE_HASHES}
        cls.outputs, cls.changed = validate_inputs()
        cls.cases = {row.get("case_id"): row for rows in cls.sources.values() for row in rows}

    def test_all_expected_rows_and_literal_replies_are_present(self):
        self.assertEqual(TOTAL_ROWS, {s: len(rows) for s, rows in self.outputs.items()})
        self.assertEqual(EDIT_COUNTS, self.changed)
        self.assertEqual(180, len(self.manual["by_case_id"]))
        for rows in self.outputs.values():
            for row in rows:
                case_id = row.get("case_id")
                if case_id in self.manual["by_case_id"]:
                    self.assertEqual(
                        self.manual["by_case_id"][case_id],
                        json.loads(row["messages"][-1]["content"])["reply"],
                    )

    def test_each_new_form_has_36_train_replies_and_all_24_hours(self):
        minute_counts = Counter()
        coverage = set()
        for row in self.outputs["train"]:
            if row.get("case_id") in self.manual["by_case_id"]:
                response = json.loads(row["messages"][-1]["content"])
                start = datetime.fromisoformat(response["params"]["starts_at"])
                minute_counts[start.minute] += 1
                coverage.add((start.hour, start.minute))
        self.assertEqual({35: 36, 40: 36, 50: 36, 55: 36}, minute_counts)
        self.assertEqual({(h, m) for h in range(24) for m in (35, 40, 50, 55)}, coverage)

    def test_frozen_sources_unchanged_rows_and_holdouts_are_byte_identical(self):
        serialized = serialize_preserving_sources(self.outputs)
        for split, digest in SOURCE_HASHES.items():
            self.assertEqual(digest, file_sha256(SOURCE / f"{split}.jsonl"))
            self.assertEqual(serialized[split], (OUTPUT / f"{split}.jsonl").read_bytes())
            old_lines = (SOURCE / f"{split}.jsonl").read_bytes().splitlines(keepends=True)
            new_lines = serialized[split].splitlines(keepends=True)
            for old_line, new_line in zip(old_lines, new_lines, strict=True):
                if json.loads(old_line).get("case_id") not in self.manual["by_case_id"]:
                    self.assertEqual(old_line, new_line)
            if EDIT_COUNTS[split] == 0:
                self.assertEqual((SOURCE / f"{split}.jsonl").read_bytes(), serialized[split])

    def test_every_input_parameter_metadata_and_split_is_preserved(self):
        for split, old_rows in self.sources.items():
            new_rows = load_jsonl(OUTPUT / f"{split}.jsonl")
            self.assertEqual(Counter(r["category"] for r in old_rows), Counter(r["category"] for r in new_rows))
            for old, new in zip(old_rows, new_rows, strict=True):
                before, after = deepcopy(old), deepcopy(new)
                old_response = json.loads(before["messages"][-1]["content"])
                new_response = json.loads(after["messages"][-1]["content"])
                old_response.pop("reply")
                new_response.pop("reply")
                self.assertEqual(old_response, new_response)
                before["messages"][-1]["content"] = after["messages"][-1]["content"]
                self.assertEqual(before, after)

    def test_before_midnight_reply_does_not_move_date_or_hour(self):
        row = next(r for r in self.outputs["train"] if r.get("case_id") == "V1253T2355")
        response = json.loads(row["messages"][-1]["content"])
        self.assertEqual("2027-09-01T23:55", response["params"]["starts_at"])
        self.assertEqual("Сверка журнала смены завтра, без пяти минут двенадцать, на десять минут.", response["reply"])
        row = next(r for r in self.outputs["validation"] if r.get("case_id") == "V1252E0955")
        response = json.loads(row["messages"][-1]["content"])
        self.assertEqual("Сверка плана следующего дня", response["params"]["title"])
        self.assertEqual("Сверка плана следующего дня завтра, без пяти минут девять, на четверть часа.", response["reply"])

    def test_wrong_minute_hour_daypart_or_preposition_is_rejected(self):
        source = json.loads(self.cases["V1253T1435"]["messages"][-1]["content"])
        for wrong in (
            "Сверка турнирной таблицы завтра, без двадцати минут три, на десять минут.",
            "Сверка турнирной таблицы завтра, без двадцати пяти минут два, на десять минут.",
            "Сверка турнирной таблицы завтра, без двадцати пяти минут три дня, на десять минут.",
            "Сверка турнирной таблицы завтра, в без двадцати пяти минут три, на десять минут.",
        ):
            with self.subTest(reply=wrong), self.assertRaises(ValueError):
                validate_reply_edit(source, wrong)

    def test_title_duration_or_date_wording_changes_are_rejected(self):
        source = json.loads(self.cases["V1253T1435"]["messages"][-1]["content"])
        for wrong in (
            "Проверка таблицы завтра, без двадцати пяти минут три, на десять минут.",
            "Сверка турнирной таблицы завтра, без двадцати пяти минут три, на пять минут.",
            "Сверка турнирной таблицы послезавтра, без двадцати пяти минут три, на десять минут.",
        ):
            with self.subTest(reply=wrong), self.assertRaisesRegex(ValueError, "outside the clock"):
                validate_reply_edit(source, wrong)

    def test_missing_unrelated_or_unused_manual_edits_are_rejected(self):
        manual = deepcopy(self.manual)
        manual["by_case_id"].pop("V1253T1435")
        with self.assertRaisesRegex(ValueError, "missing or unrelated"):
            apply_reply_edits(self.sources, manual)
        manual = deepcopy(self.manual)
        manual["by_case_id"]["V1253T1415"] = "Встреча завтра."
        with self.assertRaisesRegex(ValueError, "missing or unrelated"):
            apply_reply_edits(self.sources, manual)
        manual = deepcopy(self.manual)
        manual["by_case_id"]["MISSING"] = "Встреча завтра."
        with self.assertRaisesRegex(ValueError, "unused manual"):
            apply_reply_edits(self.sources, manual)

    def test_new_clock_replies_do_not_contain_dayparts(self):
        for rows in self.outputs.values():
            for row in rows:
                if row.get("case_id") in self.manual["by_case_id"]:
                    reply = json.loads(row["messages"][-1]["content"])["reply"]
                    match = TO_CLOCK_RE.search(reply)
                    self.assertIsNotNone(match)
                    self.assertTrue(reply[match.end():].startswith(", на "))

    def test_output_manifest_and_provenance_match_the_new_files(self):
        manifest = json.loads((OUTPUT / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("v12.54", manifest["version"])
        self.assertEqual(file_sha256(REPLY_EDITS), manifest["reply_edits_manual_sha256"])
        for split in SOURCE_HASHES:
            self.assertEqual(file_sha256(OUTPUT / f"{split}.jsonl"), manifest["artifacts"][split]["sha256"])
        result = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual("aiassistent1-assistant-sft-v12.54", result["dataset_id"])
        self.assertEqual("NOT_RUN", manifest["training"])
        self.assertEqual("NOT_RUN", manifest["model_evaluation"])


if __name__ == "__main__":
    unittest.main()
