"""Regression gates for manually authored full-title and full-query v8 data."""

from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import unittest

from dataset_contract import (
    file_sha256,
    load_jsonl,
    message_signature,
    normalized_user_prompt,
)


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
TRAIN_V8 = DOCS / "calendar_assistant_manual_train_v8.jsonl"
VALIDATION_V8 = DOCS / "calendar_assistant_manual_eval_v8.jsonl"
HOLDOUT = DOCS / "calendar_assistant_manual_holdout.jsonl"
SEMANTIC_ACCEPTANCE = DOCS / "calendar_assistant_holdout_semantic_acceptance.json"

EXPECTED_TRAIN_CATEGORIES = {
    "manual_v8_title_semantic_full": 6,
    "manual_v8_search_full_query": 6,
    "manual_v8_sum_full_query": 4,
    "manual_v8_update_full_query": 5,
    "manual_v8_delete_full_query": 4,
}

EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v8_eval_title_semantic_full": 2,
    "manual_v8_eval_search_full_query": 2,
    "manual_v8_eval_sum_full_query": 2,
    "manual_v8_eval_update_full_query": 2,
    "manual_v8_eval_delete_full_query": 2,
}

PRE_V8_SOURCE_HASHES = {
    DOCS / "calendar_assistant_manual_train_v7.jsonl": "5fc4fd5fef6e1e0800abe8269030210f8b0a64c85733bcb1c0d44cc33c3dd5f1",
    DOCS / "calendar_assistant_manual_eval_v7.jsonl": "b5478c742bddc90b959767da0b365e6191a3dddf3f98f6690c74674750403409",
    HOLDOUT: "126b14fe353da1c5e163899e464fd838b685e6f84c30a49894510d6363f2a323",
}


def assistant_payload(row: dict[str, object]) -> dict[str, object]:
    return json.loads(row["messages"][-1]["content"])


def semantic_event_text(payload: dict[str, object]) -> str:
    params = payload["params"]
    if payload["intent"] == "calendar_add":
        return params["title"]
    if payload["intent"] in {"calendar_search", "calendar_sum"}:
        return params["query"]
    return params["target"]["query"]


class ManualV8DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_V8)
        cls.validation = load_jsonl(VALIDATION_V8)
        cls.holdout = load_jsonl(HOLDOUT)

    def test_exact_manual_row_counts_and_category_quotas(self) -> None:
        self.assertEqual(25, len(self.train))
        self.assertEqual(10, len(self.validation))
        self.assertEqual(
            EXPECTED_TRAIN_CATEGORIES,
            dict(Counter(row["category"] for row in self.train)),
        )
        self.assertEqual(
            EXPECTED_VALIDATION_CATEGORIES,
            dict(Counter(row["category"] for row in self.validation)),
        )

    def test_v8_rows_are_single_turn_and_manually_categorized(self) -> None:
        for row in self.train + self.validation:
            self.assertTrue(row["category"].startswith("manual_v8_"))
            self.assertEqual(3, len(row["messages"]))
            self.assertNotIn("case_id", row)

    def test_v8_splits_and_holdout_are_disjoint(self) -> None:
        train_signatures = {message_signature(row) for row in self.train}
        validation_signatures = {message_signature(row) for row in self.validation}
        holdout_signatures = {message_signature(row) for row in self.holdout}
        train_prompts = {normalized_user_prompt(row) for row in self.train}
        validation_prompts = {normalized_user_prompt(row) for row in self.validation}
        holdout_prompts = {normalized_user_prompt(row) for row in self.holdout}

        self.assertEqual(len(self.train), len(train_signatures))
        self.assertEqual(len(self.validation), len(validation_signatures))
        self.assertFalse(train_signatures & validation_signatures)
        self.assertFalse(train_signatures & holdout_signatures)
        self.assertFalse(validation_signatures & holdout_signatures)
        self.assertFalse(train_prompts & validation_prompts)
        self.assertFalse(train_prompts & holdout_prompts)
        self.assertFalse(validation_prompts & holdout_prompts)

    def test_pre_v8_sources_are_byte_for_byte_unchanged(self) -> None:
        for path, expected_hash in PRE_V8_SOURCE_HASHES.items():
            with self.subTest(path=path):
                self.assertEqual(expected_hash, file_sha256(path))

    def test_all_named_event_fields_keep_complete_phrases(self) -> None:
        forbidden_metadata = ("сегодня", "завтра", "послезавтра", " минут", " часов")
        for row in self.train + self.validation:
            payload = assistant_payload(row)
            event_text = semantic_event_text(payload).casefold()
            with self.subTest(category=row["category"], event_text=event_text):
                self.assertGreaterEqual(len(event_text.split()), 3)
                for token in forbidden_metadata:
                    self.assertNotIn(token, event_text)

    def test_title_rows_include_natural_semantic_reformulations(self) -> None:
        titles = {
            assistant_payload(row)["params"]["title"]
            for row in self.train + self.validation
            if assistant_payload(row)["intent"] == "calendar_add"
        }
        self.assertIn("Поздравление с днём рождения сестре", titles)
        self.assertIn("Обсуждение плана ремонта серверной", titles)
        self.assertIn("Подготовка отчёта по расходу материалов", titles)

    def test_reviewed_temporal_values_are_exact(self) -> None:
        train_payloads = [assistant_payload(row) for row in self.train]
        validation_payloads = [assistant_payload(row) for row in self.validation]

        self.assertEqual(
            (
                "2027-05-20T09:00",
                "2027-05-21T19:00",
                "2028-12-31T10:00",
                "2029-04-17T15:00",
                "2029-09-04T08:00",
                "2026-10-14T18:00",
            ),
            tuple(
                payload["params"]["starts_at"]
                for payload in train_payloads
                if payload["intent"] == "calendar_add"
            ),
        )
        self.assertEqual(
            ("2027-05-20T10:00", "2029-01-01T11:00"),
            tuple(
                payload["params"]["starts_at"]
                for payload in validation_payloads
                if payload["intent"] == "calendar_add"
            ),
        )

        train_ranges = tuple(
            (payload["params"]["range_start"], payload["params"]["range_end"])
            for payload in train_payloads
            if payload["intent"] in {"calendar_search", "calendar_sum"}
        )
        self.assertEqual(
            (
                ("2027-05-20T00:00", "2027-05-21T00:00"),
                ("2027-05-24T00:00", "2027-05-31T00:00"),
                ("2029-01-01T00:00", "2029-01-02T00:00"),
                ("2029-04-18T00:00", "2029-04-19T00:00"),
                ("2029-09-01T00:00", "2029-10-01T00:00"),
                ("2026-12-27T00:00", "2026-12-28T00:00"),
                ("2027-05-19T11:50", "2027-05-20T00:00"),
                ("2028-12-18T00:00", "2028-12-25T00:00"),
                ("2029-05-01T00:00", "2029-06-01T00:00"),
                ("2029-10-03T00:00", "2029-10-04T00:00"),
            ),
            train_ranges,
        )

        validation_ranges = tuple(
            (payload["params"]["range_start"], payload["params"]["range_end"])
            for payload in validation_payloads
            if payload["intent"] in {"calendar_search", "calendar_sum"}
        )
        self.assertEqual(
            (
                ("2029-04-18T00:00", "2029-04-19T00:00"),
                ("2029-09-10T00:00", "2029-09-17T00:00"),
                ("2026-10-12T13:20", "2026-10-13T00:00"),
                ("2026-12-01T00:00", "2027-01-01T00:00"),
            ),
            validation_ranges,
        )

    def test_semantic_acceptance_is_bound_to_frozen_holdout(self) -> None:
        policy = json.loads(SEMANTIC_ACCEPTANCE.read_text(encoding="utf-8"))

        self.assertEqual(file_sha256(HOLDOUT), policy["holdout_sha256"])
        self.assertEqual({"H003", "H017"}, set(policy["cases"]))


if __name__ == "__main__":
    unittest.main()
