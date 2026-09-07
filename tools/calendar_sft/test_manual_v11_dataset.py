"""Regression gates for the manually authored H004/H008/H011 v11 data."""

from __future__ import annotations

import calendar
from collections import Counter
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import unittest

from dataset_contract import load_jsonl, message_signature, normalized_user_prompt


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
TRAIN_V11 = DOCS / "calendar_assistant_manual_train_v11.jsonl"
VALIDATION_V11 = DOCS / "calendar_assistant_manual_eval_v11.jsonl"
HOLDOUT = DOCS / "calendar_assistant_manual_holdout.jsonl"

EXPECTED_TRAIN_CATEGORIES = {
    "manual_v11_h004_leap_day": 15,
    "manual_v11_h008_implicit_date": 15,
    "manual_v11_h011_duration_twenty": 15,
}
EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v11_eval_h004_leap_day": 5,
    "manual_v11_eval_h008_implicit_date": 5,
    "manual_v11_eval_h011_duration_twenty": 5,
}
WEEKDAYS = (
    "понедельник",
    "вторник",
    "среда",
    "четверг",
    "пятница",
    "суббота",
    "воскресенье",
)
SYSTEM_RE = re.compile(
    r"Сегодня дата и время:(\d{4}-\d{2}-\d{2}) \(([^)]+)\) "
    r"(\d{2}:\d{2}) Europe/Samara ответ JSON"
)


def payload(row: dict[str, object]) -> dict[str, object]:
    return json.loads(row["messages"][-1]["content"])


def current_datetime(row: dict[str, object]) -> datetime:
    matched = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
    if matched is None:
        raise AssertionError("invalid system message")
    return datetime.fromisoformat(f"{matched.group(1)}T{matched.group(3)}")


def starts_at(row: dict[str, object]) -> datetime:
    return datetime.fromisoformat(payload(row)["params"]["starts_at"])


class ManualV11DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_V11)
        cls.validation = load_jsonl(VALIDATION_V11)
        cls.holdout = load_jsonl(HOLDOUT)

    def rows(self, case_id: str) -> list[dict[str, object]]:
        return [
            row
            for row in self.train + self.validation
            if case_id.casefold() in row["category"].casefold()
        ]

    def test_exact_manual_row_counts_and_category_quotas(self) -> None:
        self.assertEqual(45, len(self.train))
        self.assertEqual(15, len(self.validation))
        self.assertEqual(
            EXPECTED_TRAIN_CATEGORIES,
            dict(Counter(row["category"] for row in self.train)),
        )
        self.assertEqual(
            EXPECTED_VALIDATION_CATEGORIES,
            dict(Counter(row["category"] for row in self.validation)),
        )

    def test_splits_and_holdout_are_disjoint(self) -> None:
        train_signatures = {message_signature(row) for row in self.train}
        validation_signatures = {message_signature(row) for row in self.validation}
        train_prompts = {normalized_user_prompt(row) for row in self.train}
        validation_prompts = {normalized_user_prompt(row) for row in self.validation}
        holdout_prompts = {normalized_user_prompt(row) for row in self.holdout}

        self.assertEqual(len(self.train), len(train_signatures))
        self.assertEqual(len(self.validation), len(validation_signatures))
        self.assertEqual(len(self.train), len(train_prompts))
        self.assertEqual(len(self.validation), len(validation_prompts))
        self.assertFalse(train_signatures & validation_signatures)
        self.assertFalse(train_prompts & validation_prompts)
        self.assertFalse(train_prompts & holdout_prompts)
        self.assertFalse(validation_prompts & holdout_prompts)

    def test_system_weekdays_match_dates(self) -> None:
        for row in self.train + self.validation:
            matched = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
            self.assertIsNotNone(matched)
            value = datetime.fromisoformat(matched.group(1))
            with self.subTest(date=value.date()):
                self.assertEqual(WEEKDAYS[value.weekday()], matched.group(2))

    def test_h004_crosses_february_28_correctly(self) -> None:
        rows = self.rows("h004")
        leap_count = 0
        ordinary_count = 0
        self.assertEqual(20, len(rows))
        for row in rows:
            current = current_datetime(row)
            target = starts_at(row)
            self.assertEqual((2, 28), (current.month, current.day))
            self.assertLessEqual(current.year, 2080)
            self.assertEqual((current + timedelta(days=1)).date(), target.date())
            if calendar.isleap(current.year):
                leap_count += 1
                self.assertEqual((2, 29), (target.month, target.day))
            else:
                ordinary_count += 1
                self.assertEqual((3, 1), (target.month, target.day))
        self.assertEqual((14, 6), (leap_count, ordinary_count))

    def test_h008_uses_today_only_for_a_future_clock(self) -> None:
        rows = self.rows("h008")
        date_counts = Counter()
        self.assertEqual(20, len(rows))
        for row in rows:
            current = current_datetime(row)
            target = starts_at(row)
            user = row["messages"][-2]["content"].casefold()
            self.assertNotIn("сегодня", user)
            self.assertNotIn("завтра", user)
            expected = current.date() + timedelta(days=target.time() <= current.time())
            self.assertEqual(expected, target.date())
            date_counts["today" if target.date() == current.date() else "tomorrow"] += 1
        self.assertEqual(Counter({"tomorrow": 13, "today": 7}), date_counts)

    def test_h011_keeps_twenty_distinct_from_eighty_and_one_twenty(self) -> None:
        rows = self.rows("h011")
        durations = Counter()
        expected_by_phrase = {
            "двадцать минут": 20,
            "час двадцать минут": 80,
            "один час двадцать минут": 80,
            "два часа": 120,
            "сто двадцать минут": 120,
        }
        self.assertEqual(20, len(rows))
        for row in rows:
            self.assertEqual(
                (current_datetime(row) + timedelta(days=1)).date(),
                starts_at(row).date(),
            )
            user = row["messages"][-2]["content"].casefold()
            phrase = user.rsplit(" на ", 1)[1].rstrip(".")
            duration = payload(row)["params"]["duration_min"]
            self.assertEqual(expected_by_phrase[phrase], duration)
            durations[duration] += 1
        self.assertEqual(Counter({20: 13, 120: 4, 80: 3}), durations)


if __name__ == "__main__":
    unittest.main()
