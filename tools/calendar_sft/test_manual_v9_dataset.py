"""Regression gates for the manually authored calendar ontology v9 data."""

from __future__ import annotations

from calendar import monthrange
from collections import Counter
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import unittest

from dataset_contract import (
    file_sha256,
    load_jsonl,
    message_signature,
    normalized_user_prompt,
)


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
TRAIN_V9 = DOCS / "calendar_assistant_manual_train_v9.jsonl"
VALIDATION_V9 = DOCS / "calendar_assistant_manual_eval_v9.jsonl"
HOLDOUT = DOCS / "calendar_assistant_manual_holdout.jsonl"

EXPECTED_TRAIN_CATEGORIES = {
    "manual_v9_unit_fact": 4,
    "manual_v9_clock_duration_offset": 4,
    "manual_v9_weekday_order": 7,
    "manual_v9_next_weekday_range": 7,
    "manual_v9_month_length": 12,
    "manual_v9_month_range": 12,
    "manual_v9_month_offset_contrast": 8,
    "manual_v9_period_unit_fact": 2,
    "manual_v9_quarter_range": 4,
    "manual_v9_half_year_range": 2,
    "manual_v9_season_definition": 4,
    "manual_v9_season_range": 8,
    "manual_v9_year_fact": 4,
    "manual_v9_year_range": 2,
    "manual_v9_year_offset_contrast": 2,
    "manual_v9_century_leap": 2,
}

EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v9_eval_unit_and_role": 4,
    "manual_v9_eval_weekday": 4,
    "manual_v9_eval_month": 6,
    "manual_v9_eval_month_offset": 4,
    "manual_v9_eval_quarter_half_year": 3,
    "manual_v9_eval_season": 4,
    "manual_v9_eval_year_leap": 3,
}

PRE_V9_SOURCE_HASHES = {
    DOCS / "calendar_assistant_manual_train_v8.jsonl": "1cf109269c35a329934a5f99d2765277b5e7d2a955a0140038a72280b02b2bd8",
    DOCS / "calendar_assistant_manual_eval_v8.jsonl": "c79086b061320d9da513ab0632b147224a3a3d630e32ee0263a579e1a2c340c7",
    HOLDOUT: "126b14fe353da1c5e163899e464fd838b685e6f84c30a49894510d6363f2a323",
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

MONTH_FORMS = (
    ("январь", "января", "январе"),
    ("февраль", "февраля", "феврале"),
    ("март", "марта", "марте"),
    ("апрель", "апреля", "апреле"),
    ("май", "мая", "мае"),
    ("июнь", "июня", "июне"),
    ("июль", "июля", "июле"),
    ("август", "августа", "августе"),
    ("сентябрь", "сентября", "сентябре"),
    ("октябрь", "октября", "октябре"),
    ("ноябрь", "ноября", "ноябре"),
    ("декабрь", "декабря", "декабре"),
)


def assistant_payload(row: dict[str, object]) -> dict[str, object]:
    return json.loads(row["messages"][-1]["content"])


def user_text(row: dict[str, object]) -> str:
    return " ".join(
        message["content"] for message in row["messages"] if message["role"] == "user"
    ).casefold()


def all_conversational_text(row: dict[str, object]) -> str:
    return f"{user_text(row)} {assistant_payload(row)['reply'].casefold()}"


def payload_range(row: dict[str, object]) -> tuple[str, str]:
    params = assistant_payload(row)["params"]
    return params["range_start"], params["range_end"]


class ManualV9DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_V9)
        cls.validation = load_jsonl(VALIDATION_V9)
        cls.holdout = load_jsonl(HOLDOUT)

    def rows(self, source: list[dict[str, object]], category: str) -> list[dict[str, object]]:
        return [row for row in source if row["category"] == category]

    def test_exact_manual_row_counts_and_category_quotas(self) -> None:
        self.assertEqual(84, len(self.train))
        self.assertEqual(28, len(self.validation))
        self.assertEqual(
            EXPECTED_TRAIN_CATEGORIES,
            dict(Counter(row["category"] for row in self.train)),
        )
        self.assertEqual(
            EXPECTED_VALIDATION_CATEGORIES,
            dict(Counter(row["category"] for row in self.validation)),
        )

    def test_v9_rows_are_single_turn_and_manually_categorized(self) -> None:
        for row in self.train + self.validation:
            self.assertTrue(row["category"].startswith("manual_v9_"))
            self.assertEqual(3, len(row["messages"]))
            self.assertNotIn("case_id", row)

    def test_v9_splits_and_holdout_are_disjoint(self) -> None:
        train_signatures = {message_signature(row) for row in self.train}
        validation_signatures = {message_signature(row) for row in self.validation}
        holdout_signatures = {message_signature(row) for row in self.holdout}
        train_prompts = {normalized_user_prompt(row) for row in self.train}
        validation_prompts = {normalized_user_prompt(row) for row in self.validation}
        holdout_prompts = {normalized_user_prompt(row) for row in self.holdout}

        self.assertEqual(len(self.train), len(train_signatures))
        self.assertEqual(len(self.validation), len(validation_signatures))
        self.assertEqual(len(self.train), len(train_prompts))
        self.assertEqual(len(self.validation), len(validation_prompts))
        self.assertFalse(train_signatures & validation_signatures)
        self.assertFalse(train_signatures & holdout_signatures)
        self.assertFalse(validation_signatures & holdout_signatures)
        self.assertFalse(train_prompts & validation_prompts)
        self.assertFalse(train_prompts & holdout_prompts)
        self.assertFalse(validation_prompts & holdout_prompts)

    def test_pre_v9_sources_are_byte_for_byte_unchanged(self) -> None:
        for path, expected_hash in PRE_V9_SOURCE_HASHES.items():
            with self.subTest(path=path):
                self.assertEqual(expected_hash, file_sha256(path))

    def test_every_system_weekday_matches_its_calendar_date(self) -> None:
        pattern = re.compile(
            r"Сегодня дата и время:(\d{4}-\d{2}-\d{2}) \(([^)]+)\) \d{2}:\d{2} Europe/Samara ответ JSON"
        )
        for row in self.train + self.validation:
            content = row["messages"][0]["content"]
            match = pattern.fullmatch(content)
            self.assertIsNotNone(match)
            value = datetime.fromisoformat(match.group(1)).date()
            with self.subTest(date=value):
                self.assertEqual(WEEKDAYS[value.weekday()], match.group(2))

    def test_unit_examples_separate_clock_duration_and_offset(self) -> None:
        facts = " ".join(
            assistant_payload(row)["reply"].casefold()
            for row in self.rows(self.train, "manual_v9_unit_fact")
        )
        self.assertIn("шестьдесят минут", facts)
        self.assertIn("двадцать четыре часа", facts)
        self.assertIn("тысячу четыреста сорок минут", facts)
        self.assertIn("сорока восьми часам", facts)
        self.assertIn("семь последовательных календарных дней", facts)

        rows = self.rows(self.train, "manual_v9_clock_duration_offset")
        self.assertEqual(
            (
                ("2029-09-03T01:00", None),
                ("2029-09-04T09:00", 60),
                ("2029-09-03T15:30", 30),
                ("2029-09-04T08:00", 1440),
            ),
            tuple(
                (
                    assistant_payload(row)["params"]["starts_at"],
                    assistant_payload(row)["params"].get("duration_min"),
                )
                for row in rows
            ),
        )

    def test_weekday_order_and_next_week_ranges_are_exact(self) -> None:
        order_rows = self.rows(self.train, "manual_v9_weekday_order")
        self.assertEqual(7, len(order_rows))
        for weekday, row in zip(WEEKDAYS, order_rows, strict=True):
            with self.subTest(weekday=weekday):
                self.assertIn(weekday, all_conversational_text(row))

        range_rows = self.rows(self.train, "manual_v9_next_weekday_range")
        for weekday, row in enumerate(range_rows):
            expected_start = datetime(2029, 9, 10) + timedelta(days=weekday)
            expected_end = expected_start + timedelta(days=1)
            with self.subTest(weekday=weekday):
                self.assertEqual(
                    (expected_start.isoformat(timespec="minutes"), expected_end.isoformat(timespec="minutes")),
                    payload_range(row),
                )

    def test_all_month_forms_lengths_and_ranges_are_covered(self) -> None:
        length_rows = self.rows(self.train, "manual_v9_month_length")
        self.assertEqual(12, len(length_rows))
        for month, (forms, row) in enumerate(zip(MONTH_FORMS, length_rows, strict=True), start=1):
            text = all_conversational_text(row)
            with self.subTest(month=month):
                for form in forms:
                    self.assertIn(form, text)
                expected_length = monthrange(2030, month)[1]
                if expected_length == 31:
                    self.assertIn("тридцать один", text)
                elif month == 2:
                    self.assertIn("двадцать восемь", text)
                    self.assertIn("двадцать девять", text)
                else:
                    self.assertIn("тридцать дней", text)

        range_rows = self.rows(self.train, "manual_v9_month_range")
        for month, row in enumerate(range_rows, start=1):
            next_year, next_month = (2031, 1) if month == 12 else (2030, month + 1)
            expected = (
                f"2030-{month:02d}-01T00:00",
                f"{next_year}-{next_month:02d}-01T00:00",
            )
            with self.subTest(month=month):
                self.assertEqual(expected, payload_range(row))

    def test_calendar_months_and_fixed_day_offsets_remain_distinct(self) -> None:
        rows = self.rows(self.train, "manual_v9_month_offset_contrast")
        observed = []
        for row in rows[1:]:
            payload = assistant_payload(row)
            if payload["intent"] == "calendar_add":
                observed.append((payload["params"]["starts_at"],))
            else:
                observed.append(payload_range(row))
        self.assertEqual(
            [
                ("2031-02-28T00:00", "2031-03-01T00:00"),
                ("2032-02-29T00:00", "2032-03-01T00:00"),
                ("2031-03-01T00:00", "2031-04-01T00:00"),
                ("2031-02-01T00:00", "2031-03-01T00:00"),
                ("2030-09-30T09:00",),
                ("2031-03-02T00:00", "2031-03-03T00:00"),
                ("2031-03-03T00:00", "2031-03-04T00:00"),
            ],
            observed,
        )

        evaluation = self.rows(self.validation, "manual_v9_eval_month_offset")
        self.assertEqual(
            (
                ("2033-02-28T00:00", "2033-03-01T00:00"),
                ("2033-03-02T00:00", "2033-03-03T00:00"),
                ("2033-04-30T00:00", "2033-05-01T00:00"),
                ("2031-02-28T00:00", "2031-03-01T00:00"),
            ),
            tuple(payload_range(row) for row in evaluation),
        )

    def test_quarter_and_half_year_ranges_are_exact(self) -> None:
        quarters = self.rows(self.train, "manual_v9_quarter_range")
        self.assertEqual(
            (
                ("2030-01-01T00:00", "2030-04-01T00:00"),
                ("2030-04-01T00:00", "2030-07-01T00:00"),
                ("2030-07-01T00:00", "2030-10-01T00:00"),
                ("2030-10-01T00:00", "2031-01-01T00:00"),
            ),
            tuple(payload_range(row) for row in quarters),
        )
        halves = self.rows(self.train, "manual_v9_half_year_range")
        self.assertEqual(
            (
                ("2030-01-01T00:00", "2030-07-01T00:00"),
                ("2030-07-01T00:00", "2031-01-01T00:00"),
            ),
            tuple(payload_range(row) for row in halves),
        )

    def test_season_definitions_and_cross_year_winter_are_exact(self) -> None:
        definitions = self.rows(self.train, "manual_v9_season_definition")
        expected_months = (
            ("март", "апрель", "май"),
            ("июнь", "июль", "август"),
            ("сентябрь", "октябрь", "ноябрь"),
            ("декабрь", "январь", "февраль"),
        )
        for row, months in zip(definitions, expected_months, strict=True):
            reply = assistant_payload(row)["reply"].casefold()
            for month in months:
                self.assertIn(month, reply)

        ranges = self.rows(self.train, "manual_v9_season_range")
        self.assertEqual(
            (
                ("2030-03-01T00:00", "2030-06-01T00:00"),
                ("2030-06-01T00:00", "2030-09-01T00:00"),
                ("2030-09-01T00:00", "2030-12-01T00:00"),
                ("2029-12-01T00:00", "2030-03-01T00:00"),
                ("2031-03-01T00:00", "2031-06-01T00:00"),
                ("2031-06-01T00:00", "2031-09-01T00:00"),
                ("2031-09-01T00:00", "2031-12-01T00:00"),
                ("2030-12-01T00:00", "2031-03-01T00:00"),
            ),
            tuple(payload_range(row) for row in ranges),
        )

    def test_year_lengths_leap_exceptions_and_offsets_are_exact(self) -> None:
        year_ranges = self.rows(self.train, "manual_v9_year_range")
        lengths = []
        for row in year_ranges:
            start, end = (datetime.fromisoformat(value) for value in payload_range(row))
            lengths.append((end - start).days)
        self.assertEqual([365, 366], lengths)

        offsets = self.rows(self.train, "manual_v9_year_offset_contrast")
        self.assertEqual(
            (
                ("2025-02-28T00:00", "2025-03-01T00:00"),
                ("2024-02-29T00:00", "2024-03-01T00:00"),
            ),
            tuple(payload_range(row) for row in offsets),
        )
        centuries = self.rows(self.train, "manual_v9_century_leap")
        self.assertEqual(
            (
                ("2000-02-29T00:00", "2000-03-01T00:00"),
                ("2100-03-01T00:00", "2100-03-02T00:00"),
            ),
            tuple(payload_range(row) for row in centuries),
        )

    def test_forbidden_punctuation_and_impossible_dates_are_absent(self) -> None:
        for path in (TRAIN_V9, VALIDATION_V9):
            content = path.read_text(encoding="utf-8")
            with self.subTest(path=path):
                self.assertNotRegex(content, r"[\u2014\u00ab\u00bb]")
                self.assertNotIn("24:00", content)
                self.assertNotIn("-02-30", content)


if __name__ == "__main__":
    unittest.main()
