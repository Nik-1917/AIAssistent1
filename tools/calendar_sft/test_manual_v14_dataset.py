"""Contract, isolation, and coverage checks for the manual V14 release layer."""

from __future__ import annotations

from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re
import unittest

from dataset_contract import file_sha256, load_jsonl, message_signature, normalized_user_prompt
from prepare_dataset import (
    DEFAULT_HOLDOUT,
    DEFAULT_TRAIN,
    DEFAULT_VALIDATION,
    V14_ALLOWED_INTENTS,
    V14_DURATION_EXPRESSION_RE,
    V14_FORBIDDEN_INTENT_NAMES,
)


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
TRAIN_PATH = DOCS / "calendar_assistant_manual_train_v14.jsonl"
VALIDATION_PATH = DOCS / "calendar_assistant_manual_eval_v14.jsonl"
HOLDOUT_PATH = DOCS / "calendar_assistant_manual_holdout_v14.jsonl"
SEMANTIC_ACCEPTANCE_PATH = DOCS / "calendar_assistant_manual_holdout_v14_semantic_acceptance.json"
SYSTEM_RE = re.compile(
    r"^Сегодня дата и время:(\d{4}-\d{2}-\d{2}) \(([^()]+)\) "
    r"(\d{2}:\d{2}) Europe/Samara ответ JSON$",
)
WEEKDAYS = (
    "понедельник",
    "вторник",
    "среда",
    "четверг",
    "пятница",
    "суббота",
    "воскресенье",
)
EXPECTED_TRAIN_CATEGORIES = {
    "manual_v14_add_complete": 30,
    "manual_v14_add_omission": 20,
    "manual_v14_add_duration": 20,
    "manual_v14_add_value": 15,
    "manual_v14_add_temporal": 15,
    "manual_v14_search_day": 30,
    "manual_v14_search_week": 15,
    "manual_v14_search_month": 20,
    "manual_v14_search_explicit": 15,
    "manual_v14_search_query": 10,
    "manual_v14_sum_day": 15,
    "manual_v14_sum_week": 15,
    "manual_v14_sum_month": 15,
    "manual_v14_sum_explicit": 10,
    "manual_v14_sum_incomplete": 5,
    "manual_v14_refuse_delete": 15,
    "manual_v14_refuse_update": 15,
    "manual_v14_identity": 12,
    "manual_v14_note": 8,
}
EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v14_eval_add": 30,
    "manual_v14_eval_search": 27,
    "manual_v14_eval_sum": 18,
    "manual_v14_eval_refuse": 9,
    "manual_v14_eval_identity": 3,
    "manual_v14_eval_note": 3,
}
EXPECTED_HOLDOUT_CATEGORIES = {
    "manual_v14_holdout_add": 18,
    "manual_v14_holdout_search": 16,
    "manual_v14_holdout_sum": 12,
    "manual_v14_holdout_refuse": 8,
    "manual_v14_holdout_identity": 3,
    "manual_v14_holdout_note": 3,
}
EXPECTED_INTENTS = {
    "train": {"calendar_add": 100, "calendar_search": 90, "calendar_sum": 60, "chat": 42, "note_add": 8},
    "validation": {"calendar_add": 30, "calendar_search": 27, "calendar_sum": 18, "chat": 12, "note_add": 3},
    "holdout": {"calendar_add": 18, "calendar_search": 16, "calendar_sum": 12, "chat": 11, "note_add": 3},
}


def response(row: dict) -> dict:
    return json.loads(row["messages"][-1]["content"])


class ManualV14DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_PATH)
        cls.validation = load_jsonl(VALIDATION_PATH)
        cls.holdout = load_jsonl(HOLDOUT_PATH)
        cls.groups = {
            "train": cls.train,
            "validation": cls.validation,
            "holdout": cls.holdout,
        }
        cls.all_rows = cls.train + cls.validation + cls.holdout

    def test_exact_split_sizes_category_quotas_and_intents(self) -> None:
        self.assertEqual(300, len(self.train))
        self.assertEqual(90, len(self.validation))
        self.assertEqual(60, len(self.holdout))
        self.assertEqual(EXPECTED_TRAIN_CATEGORIES, dict(Counter(row["category"] for row in self.train)))
        self.assertEqual(
            EXPECTED_VALIDATION_CATEGORIES,
            dict(Counter(row["category"] for row in self.validation)),
        )
        self.assertEqual(
            EXPECTED_HOLDOUT_CATEGORIES,
            dict(Counter(row["category"] for row in self.holdout)),
        )
        for name, rows in self.groups.items():
            counts = dict(Counter(response(row)["intent"] for row in rows))
            self.assertEqual(EXPECTED_INTENTS[name], counts)

    def test_holdout_ids_and_semantic_policy_are_bound_to_exact_bytes(self) -> None:
        self.assertEqual(
            [f"V14H{index:03d}" for index in range(1, 61)],
            [row["case_id"] for row in self.holdout],
        )
        self.assertTrue(all("case_id" not in row for row in self.train + self.validation))
        policy = json.loads(SEMANTIC_ACCEPTANCE_PATH.read_text(encoding="utf-8"))
        self.assertEqual(1, policy["format_version"])
        self.assertEqual({}, policy["cases"])
        self.assertEqual(file_sha256(HOLDOUT_PATH), policy["holdout_sha256"])

    def test_v14_sources_are_active_and_generated_candidates_are_not(self) -> None:
        self.assertIn(TRAIN_PATH, DEFAULT_TRAIN)
        self.assertIn(VALIDATION_PATH, DEFAULT_VALIDATION)
        self.assertEqual((HOLDOUT_PATH,), DEFAULT_HOLDOUT)
        active_paths = DEFAULT_TRAIN + DEFAULT_VALIDATION + DEFAULT_HOLDOUT
        self.assertTrue(all("calendar_assistant_candidates" not in path.as_posix() for path in active_paths))

    def test_splits_are_isolated(self) -> None:
        signatures = {
            name: {message_signature(row) for row in rows}
            for name, rows in self.groups.items()
        }
        prompts = {
            name: {normalized_user_prompt(row) for row in rows}
            for name, rows in self.groups.items()
        }
        for name, rows in self.groups.items():
            self.assertEqual(len(rows), len(signatures[name]), name)
        names = tuple(self.groups)
        for index, left in enumerate(names):
            for right in names[index + 1 :]:
                self.assertFalse(signatures[left] & signatures[right], f"{left}/{right} signature")
                self.assertFalse(prompts[left] & prompts[right], f"{left}/{right} prompt")

    def test_system_weekdays_match_dates(self) -> None:
        for row in self.all_rows:
            matched = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
            self.assertIsNotNone(matched)
            current = datetime.fromisoformat(matched.group(1))
            self.assertEqual(WEEKDAYS[current.weekday()], matched.group(2))

    def test_only_v14_intents_and_no_forbidden_names_remain(self) -> None:
        for row in self.all_rows:
            self.assertIn(response(row)["intent"], V14_ALLOWED_INTENTS)
            serialized = json.dumps(row, ensure_ascii=False)
            for forbidden in V14_FORBIDDEN_INTENT_NAMES:
                self.assertNotIn(forbidden, serialized)

    def test_mutation_requests_are_non_action_chat(self) -> None:
        rows = [row for row in self.all_rows if "refuse" in row["category"]]
        self.assertEqual(47, len(rows))
        for row in rows:
            payload = response(row)
            self.assertEqual("chat", payload["intent"])
            self.assertEqual({}, payload["params"])
            self.assertNotIn("?", payload["reply"])
            self.assertFalse(payload["reply"].startswith("Событие "))

    def test_value_and_duration_are_explicit_calendar_add_fields_only(self) -> None:
        for row in self.all_rows:
            payload = response(row)
            params = payload["params"]
            user_text = " ".join(message["content"] for message in row["messages"][1:-1])
            if payload["intent"] != "calendar_add":
                self.assertFalse({"value", "duration_min"} & set(params))
                continue
            if "value" in params:
                self.assertIn("ценност", user_text.casefold())
            if "duration_min" in params:
                self.assertIsNotNone(V14_DURATION_EXPRESSION_RE.search(user_text))

    def test_calendar_sum_passes_only_query_and_range(self) -> None:
        rows = [row for row in self.all_rows if response(row)["intent"] == "calendar_sum"]
        self.assertEqual(90, len(rows))
        for row in rows:
            payload = response(row)
            self.assertTrue(set(payload["params"]).issubset({"query", "range_start", "range_end"}))
            self.assertNotRegex(payload["reply"], r"\d")

    def test_critical_holdout_temporal_results(self) -> None:
        cases = {row["case_id"]: response(row) for row in self.holdout}
        self.assertEqual("2032-02-29T15:00", cases["V14H001"]["params"]["starts_at"])
        self.assertEqual("2027-02-04T11:10", cases["V14H002"]["params"]["starts_at"])
        self.assertEqual("2030-01-02T09:00", cases["V14H003"]["params"]["starts_at"])
        self.assertEqual(155, cases["V14H007"]["params"]["duration_min"])
        self.assertEqual(31, cases["V14H008"]["params"]["duration_min"])
        self.assertEqual(1440, cases["V14H017"]["params"]["duration_min"])
        self.assertEqual(2880, cases["V14H018"]["params"]["duration_min"])
        for case_id in ("V14H028", "V14H043"):
            self.assertEqual("2031-02-28T00:00", cases[case_id]["params"]["range_start"])
            self.assertEqual("2031-03-01T00:00", cases[case_id]["params"]["range_end"])
        for case_id in ("V14H029", "V14H044"):
            self.assertEqual("2031-03-01T00:00", cases[case_id]["params"]["range_start"])
            self.assertEqual("2031-04-01T00:00", cases[case_id]["params"]["range_end"])


if __name__ == "__main__":
    unittest.main()
