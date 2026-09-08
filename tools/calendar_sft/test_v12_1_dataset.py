"""V12.1 editorial regression checks; never invoke training or a data generator."""

from copy import deepcopy
import json
from pathlib import Path
import unittest

from apply_v12_1_editorial import preserved_payload_sha256, user_texts
from dataset_contract import (
    DatasetContractError, file_sha256, load_jsonl, message_signature,
    normalize_record, normalized_user_prompt, parse_and_validate_assistant_response,
)

ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / "docs/calendar_sft_v12_1"


class V121DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = json.loads((RELEASE / "manifest.json").read_text(encoding="utf-8"))
        cls.splits = {name: load_jsonl(RELEASE / f"{name}.jsonl") for name in ("train", "validation", "holdout")}
        cls.focused = {
            row["case_id"]: row for rows in cls.splits.values() for row in rows
            if row.get("case_id", "").startswith(("T121", "E121", "H121"))
        }

    def test_exact_sizes_and_file_hashes(self) -> None:
        for name, size in (("train", 1872), ("validation", 541), ("holdout", 63)):
            with self.subTest(split=name):
                self.assertEqual(size, len(self.splits[name]))
                self.assertEqual(self.manifest["splits"][name]["sha256"], file_sha256(RELEASE / f"{name}.jsonl"))

    def test_all_inherited_params_categories_context_and_other_replies_unchanged(self) -> None:
        for name, rows in self.splits.items():
            inherited = self.manifest["splits"][name]
            self.assertEqual(
                inherited["preserved_payload_sha256"],
                preserved_payload_sha256(rows[:inherited["original_rows"]]),
            )

    def test_short_user_turns(self) -> None:
        for name, rows in self.splits.items():
            for number, row in enumerate(rows, 1):
                response = json.loads(row["messages"][-1]["content"])
                if response["intent"] == "note_add":
                    continue
                with self.subTest(split=name, row=number):
                    self.assertLessEqual(len(user_texts(row)), 2)
                    for text in user_texts(row):
                        self.assertLessEqual(len(text.split()), 14)
                    if response["intent"] == "calendar_add" and len(response["params"]) > 3:
                        self.assertEqual(2, len(user_texts(row)))

    def test_every_add_reply_is_just_its_title(self) -> None:
        count = 0
        for rows in self.splits.values():
            for row in rows:
                response = json.loads(row["messages"][-1]["content"])
                if response["intent"] != "calendar_add":
                    continue
                count += 1
                title = response["params"].get("title")
                expected = "Название события не указано." if title is None else title + ("" if title.endswith((".", "!", "?")) else ".")
                self.assertEqual(expected, response["reply"])
                self.assertNotIn("Событие создано", response["reply"])
        self.assertEqual(929, count)

    def test_no_exact_context_duplicates_or_cross_split_leakage(self) -> None:
        seen = set()
        for rows in self.splits.values():
            for row in rows:
                signature = message_signature(row)
                self.assertNotIn(signature, seen)
                seen.add(signature)

    def test_holdout_wording_stays_outside_training_and_validation(self) -> None:
        holdout = {normalized_user_prompt(row) for row in self.splits["holdout"]}
        for name in ("train", "validation"):
            self.assertFalse(holdout & {normalized_user_prompt(row) for row in self.splits[name]})

    def test_focused_cases_are_handwritten_and_split_disjoint(self) -> None:
        self.assertEqual(24, len(self.focused))
        for name, rows in self.splits.items():
            added = rows[self.manifest["splits"][name]["original_rows"]:]
            others = {normalized_user_prompt(row) for other, values in self.splits.items() if other != name for row in values}
            self.assertFalse(others & {normalized_user_prompt(row) for row in added})

    def test_original_duration_and_value_coverage_survives(self) -> None:
        additions = [json.loads(row["messages"][-1]["content"]) for row in self.splits["train"]]
        durations = {r["params"]["duration_min"] for r in additions if r["intent"] == "calendar_add" and "duration_min" in r["params"]}
        self.assertTrue(set(range(1, 61)).issubset(durations))
        self.assertTrue({90, 120, 1440, 2880}.issubset(durations))
        intents = {r["intent"] for r in additions}
        self.assertEqual({"chat", "note_add", "calendar_add", "calendar_search", "calendar_sum", "calendar_update", "calendar_delete"}, intents)
        values = {r["params"]["value"] for r in additions if r["intent"] == "calendar_add" and "value" in r["params"]}
        self.assertIn(0, values)
        self.assertTrue(any(value < 0 for value in values))

    def test_requested_haircut_word_order_and_case(self) -> None:
        lower, upper = (self.focused[key] for key in ("T12101", "T12102"))
        self.assertEqual(user_texts(lower)[0], user_texts(upper)[0].lower())
        self.assertEqual(lower["messages"][-1], upper["messages"][-1])
        for case_id in ("T12101", "T12102", "T12103", "E12101", "H12101"):
            response = json.loads(self.focused[case_id]["messages"][-1]["content"])
            self.assertEqual("К парикмахеру", response["params"]["title"])
            self.assertEqual("К парикмахеру.", response["reply"])

    def test_named_temporal_words_are_not_blindly_removed(self) -> None:
        for case_id, title in (("T12111", "Завтра"), ("E12106", "Вечерняя прогулка"), ("H12106", "Планы на завтра")):
            response = json.loads(self.focused[case_id]["messages"][-1]["content"])
            self.assertEqual(title, response["params"]["title"])
            self.assertEqual(title + ".", response["reply"])

    def test_new_version_is_metadata_not_runtime_prompt(self) -> None:
        for rows in self.splits.values():
            for row in rows:
                self.assertEqual("v12.1", row["contract_version"])
                self.assertNotIn("v12.1", row["messages"][0]["content"])

    def test_legacy_contract_is_not_silently_relaxed(self) -> None:
        row = self.focused["T12112"]
        with self.assertRaisesRegex(DatasetContractError, "must put a comma"):
            parse_and_validate_assistant_response(row["messages"][-1]["content"])
        parse_and_validate_assistant_response(row["messages"][-1]["content"], contract_version="v12.1")

    def test_v121_rejects_schedule_and_creation_claim_in_reply(self) -> None:
        row = self.focused["T12112"]
        for reply in ("Чтение завтра.", "Чтение, в восемь часов вечера.", "Событие создано: Чтение.", "Чтение на пятнадцать минут.", "Чтение ценностью пять единиц."):
            response = json.loads(row["messages"][-1]["content"])
            response["reply"] = reply
            with self.subTest(reply=reply), self.assertRaises(DatasetContractError):
                parse_and_validate_assistant_response(json.dumps(response, ensure_ascii=False), contract_version="v12.1")

    def test_absent_title_is_not_invented(self) -> None:
        for case_id in ("T12110", "E12105", "H12105"):
            response = json.loads(self.focused[case_id]["messages"][-1]["content"])
            self.assertNotIn("title", response["params"])
            self.assertEqual("Название события не указано.", response["reply"])

    def test_unknown_contract_tag_is_rejected(self) -> None:
        for version in ("v12.2", "v14", None, 121):
            row = deepcopy(self.focused["T12101"])
            row["contract_version"] = version
            with self.subTest(version=version), self.assertRaises(DatasetContractError):
                normalize_record(row, "bad-version")


if __name__ == "__main__":
    unittest.main()
