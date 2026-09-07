"""Contract and coverage checks for the manually authored V13 layer."""

from __future__ import annotations

import calendar
from collections import Counter
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import unittest

from dataset_contract import load_jsonl, message_signature, normalized_user_prompt
from prepare_dataset import DEFAULT_HOLDOUT, DEFAULT_TRAIN, DEFAULT_VALIDATION


ROOT = Path(__file__).resolve().parents[2]
TRAIN_PATH = ROOT / "docs" / "calendar_assistant_manual_train_v13.jsonl"
VALIDATION_PATH = ROOT / "docs" / "calendar_assistant_manual_eval_v13.jsonl"
NEGATIVE_HOLDOUT_PATH = ROOT / "docs" / "calendar_assistant_negative_holdout_v13.jsonl"
STANDARD_HOLDOUT_PATH = ROOT / "docs" / "calendar_assistant_manual_holdout.jsonl"
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
    "manual_v13_recoverable_noise": 28,
    "manual_v13_incomplete_command": 20,
    "manual_v13_contradictory_request": 18,
    "manual_v13_impossible_datetime": 22,
    "manual_v13_unsupported_operation": 16,
    "manual_v13_schema_resistance": 16,
    "manual_v13_gibberish": 20,
    "manual_v13_h004_leap_regression": 16,
    "manual_v13_h054_sum_today_regression": 12,
    "manual_v13_h056_sum_one_month_regression": 12,
    "manual_v13_omission_regression": 12,
    "manual_v13_clear_value_regression": 8,
}
EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v13_eval_recoverable_noise": 8,
    "manual_v13_eval_incomplete_command": 6,
    "manual_v13_eval_contradictory_request": 5,
    "manual_v13_eval_impossible_datetime": 7,
    "manual_v13_eval_unsupported_operation": 5,
    "manual_v13_eval_schema_resistance": 5,
    "manual_v13_eval_gibberish": 4,
    "manual_v13_eval_h004_leap_regression": 4,
    "manual_v13_eval_h054_sum_today_regression": 4,
    "manual_v13_eval_h056_sum_one_month_regression": 4,
    "manual_v13_eval_omission_regression": 4,
    "manual_v13_eval_clear_value_regression": 4,
}
EXPECTED_NEGATIVE_HOLDOUT_CATEGORIES = {
    "negative_holdout_recoverable_noise": 6,
    "negative_holdout_incomplete_command": 6,
    "negative_holdout_contradictory_request": 6,
    "negative_holdout_impossible_datetime": 7,
    "negative_holdout_unsupported_operation": 5,
    "negative_holdout_schema_resistance": 5,
    "negative_holdout_gibberish": 5,
}
STRICT_CHAT_MARKERS = (
    "contradictory_request",
    "impossible_datetime",
    "unsupported_operation",
    "gibberish",
)


def response(row: dict) -> dict:
    return json.loads(row["messages"][-1]["content"])


def anchor(row: dict) -> datetime:
    matched = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
    if matched is None:
        raise AssertionError("invalid V13 system prompt")
    return datetime.fromisoformat(f"{matched.group(1)}T{matched.group(3)}")


def add_one_month(day):
    year = day.year + day.month // 12
    month = day.month % 12 + 1
    target_day = min(day.day, calendar.monthrange(year, month)[1])
    return day.replace(year=year, month=month, day=target_day)


class ManualV13DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_PATH)
        cls.validation = load_jsonl(VALIDATION_PATH)
        cls.negative_holdout = load_jsonl(NEGATIVE_HOLDOUT_PATH)
        cls.standard_holdout = load_jsonl(STANDARD_HOLDOUT_PATH)
        cls.supervised = cls.train + cls.validation
        cls.all_v13 = cls.supervised + cls.negative_holdout

    def rows(self, marker: str) -> list[dict]:
        return [row for row in self.all_v13 if marker in row["category"]]

    def test_exact_split_sizes_and_category_quotas(self) -> None:
        self.assertEqual(200, len(self.train))
        self.assertEqual(60, len(self.validation))
        self.assertEqual(40, len(self.negative_holdout))
        self.assertEqual(EXPECTED_TRAIN_CATEGORIES, dict(Counter(row["category"] for row in self.train)))
        self.assertEqual(
            EXPECTED_VALIDATION_CATEGORIES,
            dict(Counter(row["category"] for row in self.validation)),
        )
        self.assertEqual(
            EXPECTED_NEGATIVE_HOLDOUT_CATEGORIES,
            dict(Counter(row["category"] for row in self.negative_holdout)),
        )

    def test_negative_holdout_ids_are_stable(self) -> None:
        self.assertEqual(
            [f"N{index:03d}" for index in range(1, 41)],
            [row["case_id"] for row in self.negative_holdout],
        )
        self.assertTrue(all("case_id" not in row for row in self.supervised))

    def test_all_splits_are_prompt_disjoint(self) -> None:
        groups = {
            "train": self.train,
            "validation": self.validation,
            "negative_holdout": self.negative_holdout,
            "standard_holdout": self.standard_holdout,
        }
        signatures = {
            name: {message_signature(row) for row in rows}
            for name, rows in groups.items()
        }
        prompts = {
            name: {normalized_user_prompt(row) for row in rows}
            for name, rows in groups.items()
        }
        for name, rows in groups.items():
            self.assertEqual(len(rows), len(signatures[name]), name)
            self.assertEqual(len(rows), len(prompts[name]), name)
        names = tuple(groups)
        for index, left in enumerate(names):
            for right in names[index + 1 :]:
                self.assertFalse(signatures[left] & signatures[right], f"{left}/{right} signature")
                self.assertFalse(prompts[left] & prompts[right], f"{left}/{right} prompt")

    def test_negative_holdout_is_not_a_training_or_default_holdout_source(self) -> None:
        self.assertIn(TRAIN_PATH, DEFAULT_TRAIN)
        self.assertIn(VALIDATION_PATH, DEFAULT_VALIDATION)
        self.assertNotIn(NEGATIVE_HOLDOUT_PATH, DEFAULT_TRAIN)
        self.assertNotIn(NEGATIVE_HOLDOUT_PATH, DEFAULT_VALIDATION)
        self.assertNotIn(NEGATIVE_HOLDOUT_PATH, DEFAULT_HOLDOUT)

    def test_system_weekdays_match_dates(self) -> None:
        for row in self.all_v13:
            matched = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
            self.assertIsNotNone(matched)
            current = datetime.fromisoformat(matched.group(1))
            with self.subTest(category=row["category"], date=current.date()):
                self.assertEqual(WEEKDAYS[current.weekday()], matched.group(2))

    def test_unrecoverable_inputs_use_non_action_chat(self) -> None:
        rows = [
            row
            for row in self.all_v13
            if any(marker in row["category"] for marker in STRICT_CHAT_MARKERS)
        ]
        self.assertEqual(120, len(rows))
        for row in rows:
            payload = response(row)
            self.assertEqual("chat", payload["intent"])
            self.assertEqual({}, payload["params"])
            self.assertNotIn("?", payload["reply"])

    def test_recoverable_noise_preserves_executable_intent(self) -> None:
        rows = self.rows("recoverable_noise")
        self.assertEqual(42, len(rows))
        self.assertTrue(all(response(row)["intent"] != "chat" for row in rows))

    def test_schema_resistance_keeps_one_object_and_valid_commands(self) -> None:
        rows = self.rows("schema_resistance")
        self.assertEqual(26, len(rows))
        intents = Counter(response(row)["intent"] for row in rows)
        self.assertEqual(14, intents["chat"])
        self.assertEqual(12, sum(count for intent, count in intents.items() if intent != "chat"))
        for row in rows:
            payload = response(row)
            self.assertEqual({"intent", "reply", "params"}, set(payload))
            self.assertNotIn("null", row["messages"][-1]["content"])

    def test_h004_examples_land_on_february_twenty_ninth(self) -> None:
        rows = self.rows("h004_leap_regression")
        self.assertEqual(20, len(rows))
        for row in rows:
            target = datetime.fromisoformat(response(row)["params"]["starts_at"])
            self.assertEqual((2, 29), (target.month, target.day))
            self.assertTrue(calendar.isleap(target.year))
            self.assertLessEqual(target.year, 2080)

    def test_h054_today_ranges_start_at_current_time(self) -> None:
        rows = self.rows("h054_sum_today_regression")
        self.assertEqual(16, len(rows))
        for row in rows:
            payload = response(row)
            current = anchor(row)
            self.assertEqual("calendar_sum", payload["intent"])
            self.assertEqual(current, datetime.fromisoformat(payload["params"]["range_start"]))
            expected_end = datetime.combine(current.date() + timedelta(days=1), datetime.min.time())
            self.assertEqual(expected_end, datetime.fromisoformat(payload["params"]["range_end"]))

    def test_h056_one_month_ranges_cover_one_clamped_target_day(self) -> None:
        rows = self.rows("h056_sum_one_month_regression")
        self.assertEqual(16, len(rows))
        for row in rows:
            payload = response(row)
            target = add_one_month(anchor(row).date())
            start = datetime.fromisoformat(payload["params"]["range_start"])
            end = datetime.fromisoformat(payload["params"]["range_end"])
            self.assertEqual(target, start.date())
            self.assertEqual(timedelta(days=1), end - start)

    def test_omission_rows_do_not_invent_optional_fields(self) -> None:
        rows = self.rows("omission_regression")
        self.assertEqual(16, len(rows))
        for row in rows:
            payload = response(row)
            self.assertEqual("calendar_add", payload["intent"])
            self.assertTrue(set(payload["params"]).issubset({"title", "date", "starts_at"}))
            self.assertFalse({"duration_min", "value", "time"} & set(payload["params"]))
            self.assertFalse(payload["reply"].startswith("Событие создано:"))

    def test_clear_value_rows_never_emit_null_or_numeric_value(self) -> None:
        rows = self.rows("clear_value_regression")
        self.assertEqual(12, len(rows))
        for row in rows:
            payload = response(row)
            self.assertEqual("calendar_update", payload["intent"])
            self.assertEqual({"clear_value": True}, payload["params"]["changes"])
            self.assertNotIn("null", row["messages"][-1]["content"])


if __name__ == "__main__":
    unittest.main()
