"""Regression gates for the manually authored calendar SFT v6 additions."""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import unittest

from dataset_contract import file_sha256, load_jsonl, message_signature


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
TRAIN_V6 = DOCS / "calendar_assistant_manual_train_v6.jsonl"
VALIDATION_V6 = DOCS / "calendar_assistant_manual_eval_v6.jsonl"
HOLDOUT = DOCS / "calendar_assistant_manual_holdout.jsonl"

EXPECTED_TRAIN_CATEGORIES = {
    "manual_v6_action_reply_schema": 4,
    "manual_v6_add_date_only_title": 8,
    "manual_v6_add_daypart_unknown_time": 8,
    "manual_v6_add_daypartless_literal": 8,
    "manual_v6_add_duration_only": 8,
    "manual_v6_add_explicit_today": 8,
    "manual_v6_add_implicit_earlier": 10,
    "manual_v6_add_implicit_equal": 10,
    "manual_v6_add_implicit_later": 12,
    "manual_v6_add_negative_zero_value": 6,
    "manual_v6_add_no_invention": 8,
    "manual_v6_add_time_only": 8,
    "manual_v6_add_title_only": 8,
    "manual_v6_add_value": 8,
    "manual_v6_chat_how_to": 10,
    "manual_v6_complete_reply_schema": 4,
    "manual_v6_delete_empty_target": 6,
    "manual_v6_delete_last_in_range": 6,
    "manual_v6_delete_named_target": 6,
    "manual_v6_duration_long_hours": 8,
    "manual_v6_duration_minutes": 8,
    "manual_v6_duration_mixed": 8,
    "manual_v6_executable_command": 10,
    "manual_v6_fractional_value_omitted": 6,
    "manual_v6_partial_reply_schema": 4,
    "manual_v6_search_all": 6,
    "manual_v6_search_query": 6,
    "manual_v6_sum_day": 8,
    "manual_v6_sum_empty_period": 4,
    "manual_v6_sum_month": 8,
    "manual_v6_sum_offset": 8,
    "manual_v6_sum_week": 8,
    "manual_v6_sum_without_period": 8,
    "manual_v6_sum_year": 8,
    "manual_v6_update_clear_value": 8,
    "manual_v6_update_empty_changes": 6,
    "manual_v6_update_named_target": 6,
    "manual_v6_update_source_range": 6,
    "manual_v6_update_value": 8,
}

EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v6_eval_add_implicit_date": 8,
    "manual_v6_eval_add_partial_fields": 8,
    "manual_v6_eval_chat_vs_command": 4,
    "manual_v6_eval_daypartless": 4,
    "manual_v6_eval_delete_target": 4,
    "manual_v6_eval_duration": 8,
    "manual_v6_eval_no_invention": 4,
    "manual_v6_eval_search_query_all": 4,
    "manual_v6_eval_sum_days_weeks": 6,
    "manual_v6_eval_sum_months_offsets": 6,
    "manual_v6_eval_sum_year_without_period": 4,
    "manual_v6_eval_update_target": 4,
    "manual_v6_eval_value_add": 4,
    "manual_v6_eval_value_update_clear": 4,
}

PRE_V6_SOURCE_HASHES = {
    DOCS / "calendar_assistant_train_seed.jsonl": "b5076afe7c67a4ba45ddf28eda0de845248622d38604f3bfafe8354e0d5e224d",
    DOCS / "calendar_assistant_eval_seed.jsonl": "8798f38a00bd1542802dfbcf8af8e859fec24e1af9cb67c03f488e9d37ca60bc",
    DOCS
    / "calendar_assistant_candidates"
    / "calendar_assistant_train_candidates.jsonl": "33f83d7d2eae5bdf1919e22a69d0aeea7e17e18da8b0b9961545bba5836f9336",
    DOCS
    / "calendar_assistant_candidates"
    / "calendar_assistant_eval_candidates.jsonl": "d7f4e65e1c93d04d84708577f147af73539553a42467b489a57bcb1751e15292",
    DOCS / "calendar_assistant_manual_train_v5.jsonl": "ae85ea79a1957cdfa75d03b52fe36aebbb42095411fd2b13950d91f6e9d142d3",
    DOCS / "calendar_assistant_manual_eval_v5.jsonl": "73e0ef8feb4f5e4112981bfd1450abeb56412d32c0ad12208c43d0f69d8c35af",
    HOLDOUT: "126b14fe353da1c5e163899e464fd838b685e6f84c30a49894510d6363f2a323",
}


def normalized_user_text(row: dict[str, object]) -> str:
    messages = row["messages"]
    user_text = " ".join(message["content"] for message in messages[1:-1])
    return re.sub(r"[^\w]+", " ", user_text.casefold()).strip()


def assistant_payload(row: dict[str, object]) -> dict[str, object]:
    return json.loads(row["messages"][-1]["content"])


def system_now(row: dict[str, object]) -> datetime:
    content = row["messages"][0]["content"]
    match = re.fullmatch(
        r"Сегодня дата и время:(\d{4}-\d{2}-\d{2}) \([^)]+\) (\d{2}:\d{2}) Europe/Samara ответ JSON",
        content,
    )
    if match is None:
        raise AssertionError(f"Unexpected system message: {content!r}")
    return datetime.fromisoformat(f"{match.group(1)}T{match.group(2)}")


def event_start(row: dict[str, object]) -> datetime:
    payload = assistant_payload(row)
    return datetime.fromisoformat(payload["params"]["starts_at"])


class ManualV6DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_V6)
        cls.validation = load_jsonl(VALIDATION_V6)
        cls.holdout = load_jsonl(HOLDOUT)

    def test_exact_manual_row_counts_and_category_quotas(self) -> None:
        self.assertEqual(288, len(self.train))
        self.assertEqual(72, len(self.validation))
        self.assertEqual(EXPECTED_TRAIN_CATEGORIES, dict(Counter(row["category"] for row in self.train)))
        self.assertEqual(
            EXPECTED_VALIDATION_CATEGORIES,
            dict(Counter(row["category"] for row in self.validation)),
        )

    def test_v6_rows_are_single_turn_and_manually_categorized(self) -> None:
        for row in self.train + self.validation:
            self.assertTrue(row["category"].startswith("manual_v6_"))
            self.assertEqual(3, len(row["messages"]))
            self.assertNotIn("case_id", row)

    def test_v6_system_user_signatures_are_disjoint(self) -> None:
        train_signatures = {message_signature(row) for row in self.train}
        validation_signatures = {message_signature(row) for row in self.validation}
        holdout_signatures = {message_signature(row) for row in self.holdout}

        self.assertEqual(len(self.train), len(train_signatures))
        self.assertEqual(len(self.validation), len(validation_signatures))
        self.assertFalse(train_signatures & validation_signatures)
        self.assertFalse(train_signatures & holdout_signatures)
        self.assertFalse(validation_signatures & holdout_signatures)

    def test_holdout_user_prompts_are_not_copied_with_a_new_system_time(self) -> None:
        train_text = {normalized_user_text(row) for row in self.train}
        validation_text = {normalized_user_text(row) for row in self.validation}
        holdout_text = {normalized_user_text(row) for row in self.holdout}

        self.assertFalse(train_text & holdout_text)
        self.assertFalse(validation_text & holdout_text)
        self.assertFalse(train_text & validation_text)

    def test_pre_v6_sources_are_byte_for_byte_unchanged(self) -> None:
        for path, expected_hash in PRE_V6_SOURCE_HASHES.items():
            with self.subTest(path=path):
                self.assertEqual(expected_hash, file_sha256(path))

    def test_implicit_exact_time_uses_strict_today_tomorrow_boundary(self) -> None:
        relations = {
            "manual_v6_add_implicit_later": "later",
            "manual_v6_add_implicit_equal": "equal",
            "manual_v6_add_implicit_earlier": "earlier",
        }
        for row in self.train:
            relation = relations.get(row["category"])
            if relation is None:
                continue

            now = system_now(row)
            start = event_start(row)
            with self.subTest(category=row["category"], user=row["messages"][1]["content"]):
                if relation == "later":
                    self.assertEqual(now.date(), start.date())
                    self.assertGreater(start.time(), now.time())
                elif relation == "equal":
                    self.assertEqual(now.date() + timedelta(days=1), start.date())
                    self.assertEqual(start.time(), now.time())
                else:
                    self.assertEqual(now.date() + timedelta(days=1), start.date())
                    self.assertLess(start.time(), now.time())

    def test_explicit_today_overrides_the_implicit_time_boundary(self) -> None:
        rows = [row for row in self.train if row["category"] == "manual_v6_add_explicit_today"]
        self.assertEqual(8, len(rows))
        for row in rows:
            with self.subTest(user=row["messages"][1]["content"]):
                self.assertEqual(system_now(row).date(), event_start(row).date())

    def test_validation_implicit_dates_follow_the_same_rule(self) -> None:
        rows = [
            row for row in self.validation if row["category"] == "manual_v6_eval_add_implicit_date"
        ]
        self.assertEqual(8, len(rows))
        for row in rows:
            now = system_now(row)
            start = event_start(row)
            user = row["messages"][1]["content"].casefold()
            expected_date = now.date()
            if "сегодня" not in user and start.time() <= now.time():
                expected_date += timedelta(days=1)
            with self.subTest(user=user):
                self.assertEqual(expected_date, start.date())

    def test_fractional_values_are_omitted_instead_of_converted(self) -> None:
        train_rows = [
            row for row in self.train if row["category"] == "manual_v6_fractional_value_omitted"
        ]
        validation_rows = [
            row for row in self.validation if row["category"] == "manual_v6_eval_no_invention"
        ]
        rows = train_rows + [
            row for row in validation_rows if "половин" in row["messages"][1]["content"].casefold()
        ]
        self.assertEqual(7, len(rows))
        for row in rows:
            payload = assistant_payload(row)
            params = payload["params"]
            with self.subTest(user=row["messages"][1]["content"]):
                self.assertNotIn("value", params)
                if payload["intent"] == "calendar_update":
                    self.assertEqual({}, params["changes"])

    def test_empty_update_and_delete_commands_remain_explicitly_empty(self) -> None:
        for row in self.train:
            payload = assistant_payload(row)
            with self.subTest(category=row["category"], user=row["messages"][1]["content"]):
                if row["category"] == "manual_v6_update_empty_changes":
                    self.assertEqual({}, payload["params"]["changes"])
                elif row["category"] == "manual_v6_delete_empty_target":
                    self.assertEqual({}, payload["params"]["target"])

    def test_v6_assistant_never_asks_and_uses_no_forbidden_characters_or_null(self) -> None:
        forbidden = {"\u2014", "\u00ab", "\u00bb"}
        for row in self.train + self.validation:
            for message in row["messages"]:
                with self.subTest(category=row["category"], role=message["role"]):
                    self.assertTrue(forbidden.isdisjoint(message["content"]))
            assistant = row["messages"][-1]["content"]
            reply = assistant_payload(row)["reply"]
            self.assertNotIn("?", reply)
            self.assertNotIn("null", assistant)


if __name__ == "__main__":
    unittest.main()
