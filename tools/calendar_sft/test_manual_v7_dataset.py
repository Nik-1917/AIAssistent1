"""Regression gates for the manually authored calendar SFT v7 additions."""

from __future__ import annotations

from calendar import isleap
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
TRAIN_V7 = DOCS / "calendar_assistant_manual_train_v7.jsonl"
VALIDATION_V7 = DOCS / "calendar_assistant_manual_eval_v7.jsonl"
HOLDOUT = DOCS / "calendar_assistant_manual_holdout.jsonl"

EXPECTED_TRAIN_CATEGORIES = {
    "manual_v7_clock_24h_hour": 24,
    "manual_v7_clock_24h_minute": 24,
    "manual_v7_clock_daypart_equivalent": 24,
    "manual_v7_clock_explicit_date_priority": 8,
    "manual_v7_clock_implicit_date": 12,
    "manual_v7_duration_day_units": 8,
    "manual_v7_offset_day_units": 8,
    "manual_v7_calendar_leap_boundary": 16,
}

EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v7_eval_clock_24h": 24,
    "manual_v7_eval_clock_semantics": 8,
    "manual_v7_eval_day_units": 8,
    "manual_v7_eval_calendar_leap_boundary": 8,
}

PRE_V7_SOURCE_HASHES = {
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
    DOCS / "calendar_assistant_manual_train_v6.jsonl": "2902fe8b2025a534c7715f031f2304b6d8ef17a18795a220fc98bd2cae173e52",
    DOCS / "calendar_assistant_manual_eval_v6.jsonl": "dfc2f7599c98c962e251034ee21b1b9052dd4fbf2ebd27db97ea08972c187eed",
    HOLDOUT: "126b14fe353da1c5e163899e464fd838b685e6f84c30a49894510d6363f2a323",
}

CLOCK_HOUR_FORMS = (
    "ноль часов",
    "один час",
    "два часа",
    "три часа",
    "четыре часа",
    "пять часов",
    "шесть часов",
    "семь часов",
    "восемь часов",
    "девять часов",
    "десять часов",
    "одиннадцать часов",
    "двенадцать часов",
    "тринадцать часов",
    "четырнадцать часов",
    "пятнадцать часов",
    "шестнадцать часов",
    "семнадцать часов",
    "восемнадцать часов",
    "девятнадцать часов",
    "двадцать часов",
    "двадцать один час",
    "двадцать два часа",
    "двадцать три часа",
)

CLOCK_MINUTE_VALUES = (
    5,
    10,
    15,
    20,
    25,
    30,
    35,
    40,
    45,
    50,
    55,
    5,
    10,
    15,
    20,
    25,
    30,
    35,
    40,
    45,
    50,
    55,
    5,
    10,
)

DAYPART_FORMS = (
    "в полночь",
    "в час ночи",
    "в два часа ночи",
    "в три часа ночи",
    "в четыре часа ночи",
    "в пять часов утра",
    "в шесть часов утра",
    "в семь часов утра",
    "в восемь часов утра",
    "в девять часов утра",
    "в десять часов утра",
    "в одиннадцать часов утра",
    "в полдень",
    "в час дня",
    "в два часа дня",
    "в три часа дня",
    "в четыре часа дня",
    "в пять часов вечера",
    "в шесть часов вечера",
    "в семь часов вечера",
    "в восемь часов вечера",
    "в девять часов вечера",
    "в десять часов вечера",
    "в одиннадцать часов вечера",
)


def assistant_payload(row: dict[str, object]) -> dict[str, object]:
    return json.loads(row["messages"][-1]["content"])


def user_text(row: dict[str, object]) -> str:
    return " ".join(
        message["content"] for message in row["messages"] if message["role"] == "user"
    ).casefold()


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
    return datetime.fromisoformat(assistant_payload(row)["params"]["starts_at"])


def temporal_fields(row: dict[str, object]) -> tuple[str, ...]:
    payload = assistant_payload(row)
    intent = payload["intent"]
    params = payload["params"]
    if intent == "calendar_add":
        return (params["starts_at"] if "starts_at" in params else params["date"],)
    if intent in {"calendar_search", "calendar_sum"}:
        return (params["range_start"], params["range_end"])
    if intent == "calendar_update":
        return (params["changes"]["date"],)
    if intent == "calendar_delete":
        target = params["target"]
        return (target["range_start"], target["range_end"])
    raise AssertionError(f"Unexpected leap-boundary intent: {intent!r}")


class ManualV7DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_V7)
        cls.validation = load_jsonl(VALIDATION_V7)
        cls.holdout = load_jsonl(HOLDOUT)

    def rows(self, source: list[dict[str, object]], category: str) -> list[dict[str, object]]:
        return [row for row in source if row["category"] == category]

    def test_exact_manual_row_counts_and_category_quotas(self) -> None:
        self.assertEqual(124, len(self.train))
        self.assertEqual(48, len(self.validation))
        self.assertEqual(EXPECTED_TRAIN_CATEGORIES, dict(Counter(row["category"] for row in self.train)))
        self.assertEqual(
            EXPECTED_VALIDATION_CATEGORIES,
            dict(Counter(row["category"] for row in self.validation)),
        )

    def test_v7_rows_are_single_turn_and_manually_categorized(self) -> None:
        for row in self.train + self.validation:
            self.assertTrue(row["category"].startswith("manual_v7_"))
            self.assertEqual(3, len(row["messages"]))
            self.assertNotIn("case_id", row)

    def test_v7_splits_and_holdout_are_disjoint(self) -> None:
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

    def test_pre_v7_sources_are_byte_for_byte_unchanged(self) -> None:
        for path, expected_hash in PRE_V7_SOURCE_HASHES.items():
            with self.subTest(path=path):
                self.assertEqual(expected_hash, file_sha256(path))

    def test_every_24_hour_pronunciation_maps_to_its_fixed_hour(self) -> None:
        for source, category in (
            (self.train, "manual_v7_clock_24h_hour"),
            (self.train, "manual_v7_clock_24h_minute"),
            (self.validation, "manual_v7_eval_clock_24h"),
        ):
            rows = self.rows(source, category)
            self.assertEqual(24, len(rows))
            for expected_hour, (form, row) in enumerate(zip(CLOCK_HOUR_FORMS, rows)):
                start = event_start(row)
                params = assistant_payload(row)["params"]
                with self.subTest(category=category, hour=expected_hour):
                    self.assertIn(f"в {form}", user_text(row))
                    self.assertIn(form, assistant_payload(row)["reply"].casefold())
                    self.assertEqual(expected_hour, start.hour)
                    if category == "manual_v7_clock_24h_minute":
                        self.assertEqual(CLOCK_MINUTE_VALUES[expected_hour], start.minute)
                        self.assertIn(" на ", user_text(row))
                        self.assertIn("duration_min", params)
                    else:
                        self.assertEqual(0, start.minute)
                        self.assertNotIn("duration_min", params)

    def test_daypart_aliases_cover_the_same_24_hours(self) -> None:
        rows = self.rows(self.train, "manual_v7_clock_daypart_equivalent")
        self.assertEqual(24, len(rows))
        for expected_hour, (form, row) in enumerate(zip(DAYPART_FORMS, rows)):
            with self.subTest(hour=expected_hour):
                self.assertIn(form, user_text(row))
                self.assertIn(
                    CLOCK_HOUR_FORMS[expected_hour],
                    assistant_payload(row)["reply"].casefold(),
                )
                self.assertEqual(expected_hour, event_start(row).hour)
                self.assertNotIn("duration_min", assistant_payload(row)["params"])

    def test_explicit_dates_never_reinterpret_the_resolved_hour(self) -> None:
        rows = self.rows(self.train, "manual_v7_clock_explicit_date_priority")
        expected = (
            "2026-10-14T06:40",
            "2026-10-14T18:40",
            "2026-10-12T06:00",
            "2026-10-12T18:00",
            "2026-11-28T06:20",
            "2026-11-28T18:20",
            "2028-02-01T06:40",
            "2028-02-01T18:40",
        )
        self.assertEqual(expected, tuple(event_start(row).isoformat(timespec="minutes") for row in rows))

    def test_implicit_date_comparison_changes_only_the_date(self) -> None:
        rows = self.rows(self.train, "manual_v7_clock_implicit_date")
        expected_times = (
            (6, 0),
            (18, 0),
            (13, 20),
            (14, 0),
            (17, 45),
            (23, 0),
            (22, 0),
            (23, 0),
            (9, 10),
            (9, 15),
            (1, 0),
            (18, 0),
        )
        self.assertEqual(len(expected_times), len(rows))
        for row, (expected_hour, expected_minute) in zip(rows, expected_times):
            now = system_now(row)
            start = event_start(row)
            expected_date = now.date() if start.time() > now.time() else now.date() + timedelta(days=1)
            with self.subTest(user=user_text(row)):
                self.assertEqual((expected_hour, expected_minute), (start.hour, start.minute))
                self.assertEqual(expected_date, start.date())

    def test_validation_semantics_retain_fixed_clock_values(self) -> None:
        rows = self.rows(self.validation, "manual_v7_eval_clock_semantics")
        expected = (
            "2027-05-20T06:00",
            "2027-05-19T18:00",
            "2027-05-20T11:50",
            "2027-05-19T12:00",
            "2029-01-01T06:40",
            "2029-01-01T18:40",
            "2029-09-03T18:00",
            "2028-12-31T18:00",
        )
        self.assertEqual(expected, tuple(event_start(row).isoformat(timespec="minutes") for row in rows))

    def test_duration_day_units_map_to_integer_minutes(self) -> None:
        train_rows = self.rows(self.train, "manual_v7_duration_day_units")
        expected_train = (60, 1080, 1380, 1440, 2880, 1440, 1440, 2880)
        self.assertEqual(
            expected_train,
            tuple(assistant_payload(row)["params"]["duration_min"] for row in train_rows),
        )

        validation_rows = self.rows(self.validation, "manual_v7_eval_day_units")[:4]
        self.assertEqual(
            (1440, 2880, 1440, 2880),
            tuple(assistant_payload(row)["params"]["duration_min"] for row in validation_rows),
        )

    def test_relative_hour_and_day_units_preserve_elapsed_offsets(self) -> None:
        train_rows = self.rows(self.train, "manual_v7_offset_day_units")
        for row, hours in zip(train_rows, (1, 18, 23, 24, 48, 24, 24, 48)):
            with self.subTest(user=user_text(row)):
                self.assertEqual(timedelta(hours=hours), event_start(row) - system_now(row))
                self.assertNotIn("duration_min", assistant_payload(row)["params"])

        validation_rows = self.rows(self.validation, "manual_v7_eval_day_units")[4:]
        for row, hours in zip(validation_rows, (24, 48, 24, 48)):
            with self.subTest(user=user_text(row)):
                self.assertEqual(timedelta(hours=hours), event_start(row) - system_now(row))

    def test_gregorian_leap_boundaries_are_exact_and_balanced(self) -> None:
        train_rows = self.rows(self.train, "manual_v7_calendar_leap_boundary")
        validation_rows = self.rows(
            self.validation,
            "manual_v7_eval_calendar_leap_boundary",
        )
        expected_train = (
            ("calendar_add", ("2024-02-29T07:30",)),
            ("calendar_add", ("2023-03-01T07:30",)),
            ("calendar_add", ("2032-02-29T18:00",)),
            ("calendar_add", ("2031-03-01T18:00",)),
            ("calendar_add", ("2020-03-01",)),
            ("calendar_add", ("2019-03-02",)),
            ("calendar_add", ("2000-02-29T09:00",)),
            ("calendar_add", ("2100-03-01T09:00",)),
            ("calendar_search", ("2048-02-29T00:00", "2048-03-01T00:00")),
            ("calendar_search", ("2047-03-01T00:00", "2047-03-02T00:00")),
            ("calendar_sum", ("2024-03-01T00:00", "2024-03-02T00:00")),
            ("calendar_sum", ("2023-03-01T00:00", "2023-03-02T00:00")),
            ("calendar_update", ("2052-02-29",)),
            ("calendar_update", ("2051-03-01",)),
            ("calendar_delete", ("2060-02-29T00:00", "2060-03-01T00:00")),
            ("calendar_delete", ("2059-03-01T00:00", "2059-03-02T00:00")),
        )
        expected_validation = (
            ("calendar_add", ("2036-02-29T14:00",)),
            ("calendar_add", ("2035-03-01T14:00",)),
            ("calendar_add", ("2035-03-01T08:00",)),
            ("calendar_add", ("2036-03-01T08:00",)),
            ("calendar_search", ("2040-02-29T00:00", "2040-03-01T00:00")),
            ("calendar_sum", ("2039-03-01T00:00", "2039-03-02T00:00")),
            ("calendar_update", ("2044-02-29",)),
            ("calendar_delete", ("2043-03-01T00:00", "2043-03-02T00:00")),
        )

        self.assertEqual(
            expected_train,
            tuple((assistant_payload(row)["intent"], temporal_fields(row)) for row in train_rows),
        )
        self.assertEqual(
            expected_validation,
            tuple(
                (assistant_payload(row)["intent"], temporal_fields(row))
                for row in validation_rows
            ),
        )
        self.assertEqual(8, sum(isleap(system_now(row).year) for row in train_rows))
        self.assertEqual(
            4,
            sum(isleap(system_now(row).year) for row in validation_rows),
        )
        for row in train_rows + validation_rows:
            reply = assistant_payload(row)["reply"].casefold()
            user = user_text(row)
            with self.subTest(user=user):
                if "послезавтра" in user:
                    self.assertIn("послезавтра", reply)
                elif "через два дня" in user:
                    self.assertIn("через два дня", reply)
                else:
                    self.assertIn("завтра", user)
                    self.assertIn("завтра", reply)

    def test_24_00_is_never_used_as_a_clock_value(self) -> None:
        for row in self.train + self.validation:
            self.assertNotIn("24:00", row["messages"][-1]["content"])
            self.assertNotRegex(user_text(row), r"\bв двадцать четыре часа\b")


if __name__ == "__main__":
    unittest.main()
