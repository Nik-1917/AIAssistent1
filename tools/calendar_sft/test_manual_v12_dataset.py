"""Regression gates for the manually authored v12 value, omission, and duration data."""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import unittest

from dataset_contract import load_jsonl, message_signature, normalized_user_prompt


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
TRAIN_V12 = DOCS / "calendar_assistant_manual_train_v12.jsonl"
VALIDATION_V12 = DOCS / "calendar_assistant_manual_eval_v12.jsonl"
HOLDOUT = DOCS / "calendar_assistant_manual_holdout.jsonl"

EXPECTED_TRAIN_CATEGORIES = {
    "manual_v12_duration_aliases": 27,
    "manual_v12_duration_composite": 40,
    "manual_v12_duration_hour_minus": 29,
    "manual_v12_duration_minute_words": 60,
    "manual_v12_omit_date_only": 5,
    "manual_v12_omit_date_time": 4,
    "manual_v12_omit_time_only": 5,
    "manual_v12_omit_title_date": 5,
    "manual_v12_omit_title_date_time": 4,
    "manual_v12_omit_title_only": 5,
    "manual_v12_omit_title_time": 4,
    "manual_v12_value_add": 9,
    "manual_v12_value_clear": 1,
    "manual_v12_value_update": 2,
}
EXPECTED_VALIDATION_CATEGORIES = {
    "manual_v12_eval_duration_aliases": 9,
    "manual_v12_eval_duration_composite": 10,
    "manual_v12_eval_duration_hour_minus": 10,
    "manual_v12_eval_duration_minute_words": 15,
    "manual_v12_eval_omit_date_only": 1,
    "manual_v12_eval_omit_date_time": 1,
    "manual_v12_eval_omit_time_only": 1,
    "manual_v12_eval_omit_title_date": 1,
    "manual_v12_eval_omit_title_date_time": 1,
    "manual_v12_eval_omit_title_only": 1,
    "manual_v12_eval_omit_title_time": 2,
    "manual_v12_eval_value_clear": 1,
    "manual_v12_eval_value_update": 2,
}
EXPECTED_OMISSION_COUNTS = Counter(
    {
        "title_only": 6,
        "date_only": 6,
        "time_only": 6,
        "title_date": 6,
        "title_time": 6,
        "date_time": 5,
        "title_date_time": 5,
    },
)
EXPECTED_OMISSION_PARAM_KEYS = {
    "title_only": {"title", "date"},
    "date_only": {"date"},
    "time_only": {"starts_at"},
    "title_date": {"title", "date"},
    "title_time": {"title", "starts_at"},
    "date_time": {"starts_at"},
    "title_date_time": {"title", "starts_at"},
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
MINUTE_WORDS = (
    "одну минуту", "две минуты", "три минуты", "четыре минуты", "пять минут",
    "шесть минут", "семь минут", "восемь минут", "девять минут", "десять минут",
    "одиннадцать минут", "двенадцать минут", "тринадцать минут", "четырнадцать минут",
    "пятнадцать минут", "шестнадцать минут", "семнадцать минут", "восемнадцать минут",
    "девятнадцать минут", "двадцать минут", "двадцать одну минуту", "двадцать две минуты",
    "двадцать три минуты", "двадцать четыре минуты", "двадцать пять минут",
    "двадцать шесть минут", "двадцать семь минут", "двадцать восемь минут",
    "двадцать девять минут", "тридцать минут", "тридцать одну минуту",
    "тридцать две минуты", "тридцать три минуты", "тридцать четыре минуты",
    "тридцать пять минут", "тридцать шесть минут", "тридцать семь минут",
    "тридцать восемь минут", "тридцать девять минут", "сорок минут",
    "сорок одну минуту", "сорок две минуты", "сорок три минуты", "сорок четыре минуты",
    "сорок пять минут", "сорок шесть минут", "сорок семь минут", "сорок восемь минут",
    "сорок девять минут", "пятьдесят минут", "пятьдесят одну минуту",
    "пятьдесят две минуты", "пятьдесят три минуты", "пятьдесят четыре минуты",
    "пятьдесят пять минут", "пятьдесят шесть минут", "пятьдесят семь минут",
    "пятьдесят восемь минут", "пятьдесят девять минут", "шестьдесят минут",
)
MINUS_WORDS = (
    "одной минуты", "двух минут", "трёх минут", "четырёх минут", "пяти минут",
    "шести минут", "семи минут", "восьми минут", "девяти минут", "десяти минут",
    "одиннадцати минут", "двенадцати минут", "тринадцати минут", "четырнадцати минут",
    "пятнадцати минут", "шестнадцати минут", "семнадцати минут", "восемнадцати минут",
    "девятнадцати минут", "двадцати минут", "двадцати одной минуты",
    "двадцати двух минут", "двадцати трёх минут", "двадцати четырёх минут",
    "двадцати пяти минут", "двадцати шести минут", "двадцати семи минут",
    "двадцати восьми минут", "двадцати девяти минут",
)
COMPOSITE_DURATIONS = {
    "один час одну минуту": 61,
    "один час две минуты": 62,
    "один час четыре минуты": 64,
    "один час пять минут": 65,
    "один час десять минут": 70,
    "один час пятнадцать минут": 75,
    "один час двадцать минут": 80,
    "один час двадцать пять минут": 85,
    "один час тридцать минут": 90,
    "один час тридцать пять минут": 95,
    "один час сорок пять минут": 105,
    "один час пятьдесят девять минут": 119,
    "два часа одну минуту": 121,
    "два часа две минуты": 122,
    "два часа три минуты": 123,
    "два часа пять минут": 125,
    "два часа десять минут": 130,
    "два часа пятнадцать минут": 135,
    "два часа двадцать минут": 140,
    "два часа двадцать пять минут": 145,
    "два часа тридцать минут": 150,
    "два часа тридцать пять минут": 155,
    "два часа сорок пять минут": 165,
    "два часа пятьдесят девять минут": 179,
    "три часа три минуты": 183,
    "четыре часа четыре минуты": 244,
    "четыре часа двадцать пять минут": 265,
    "пять часов пять минут": 305,
    "шесть часов шесть минут": 366,
    "семь часов семь минут": 427,
    "восемь часов восемь минут": 488,
    "девять часов девять минут": 549,
    "десять часов десять минут": 610,
    "одиннадцать часов одиннадцать минут": 671,
    "двенадцать часов двенадцать минут": 732,
    "тринадцать часов сорок минут": 820,
    "восемнадцать часов восемнадцать минут": 1098,
    "двадцать часов пять минут": 1205,
    "двадцать три часа пятьдесят девять минут": 1439,
    "двадцать четыре часа одну минуту": 1441,
    "двадцать четыре часа тридцать минут": 1470,
    "двадцать пять часов пятнадцать минут": 1515,
    "тридцать часов тридцать минут": 1830,
    "тридцать два часа шестнадцать минут": 1936,
    "тридцать шесть часов сорок пять минут": 2205,
    "сорок часов двадцать минут": 2420,
    "сорок семь часов тридцать минут": 2850,
    "сорок семь часов сорок пять минут": 2865,
    "сорок семь часов пятьдесят минут": 2870,
    "сорок семь часов пятьдесят девять минут": 2879,
}
ALIAS_DURATIONS = {
    "четверть часа": 15,
    "одну четверть часа": 15,
    "пятнадцать минут": 15,
    "четверть одного часа": 15,
    "полчаса": 30,
    "пол часа": 30,
    "тридцать минут": 30,
    "половину часа": 30,
    "три четверти часа": 45,
    "сорок пять минут": 45,
    "три четверти одного часа": 45,
    "три четверти от часа": 45,
    "час без десяти минут": 50,
    "один час без десяти минут": 50,
    "пятьдесят минут": 50,
    "один час минус десять минут": 50,
    "час": 60,
    "один час": 60,
    "шестьдесят минут": 60,
    "целый час": 60,
    "полтора часа": 90,
    "час с половиной": 90,
    "девяносто минут": 90,
    "один час тридцать минут": 90,
    "два часа": 120,
    "сто двадцать минут": 120,
    "два полных часа": 120,
    "ровно два часа": 120,
    "сутки": 1440,
    "одни сутки": 1440,
    "двадцать четыре часа": 1440,
    "один день": 1440,
    "двое суток": 2880,
    "сорок восемь часов": 2880,
    "два дня": 2880,
    "двое полных суток": 2880,
}


def payload(row: dict[str, object]) -> dict[str, object]:
    return json.loads(row["messages"][-1]["content"])


def user_text(row: dict[str, object]) -> str:
    return row["messages"][-2]["content"].casefold()


def duration_value(row: dict[str, object]) -> int:
    response = payload(row)
    if response["intent"] == "calendar_add":
        return response["params"]["duration_min"]
    return response["params"]["changes"]["duration_min"]


def final_duration_phrase(row: dict[str, object]) -> str:
    return user_text(row).rsplit(" на ", 1)[1].rstrip(".")


class ManualV12DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.train = load_jsonl(TRAIN_V12)
        cls.validation = load_jsonl(VALIDATION_V12)
        cls.all_rows = cls.train + cls.validation
        cls.holdout = load_jsonl(HOLDOUT)

    def rows(self, marker: str) -> list[dict[str, object]]:
        return [row for row in self.all_rows if marker in row["category"]]

    def test_exact_manual_row_counts_and_category_quotas(self) -> None:
        self.assertEqual(200, len(self.train))
        self.assertEqual(55, len(self.validation))
        self.assertEqual(EXPECTED_TRAIN_CATEGORIES, dict(Counter(row["category"] for row in self.train)))
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
        for row in self.all_rows:
            matched = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
            self.assertIsNotNone(matched)
            current = datetime.fromisoformat(matched.group(1))
            with self.subTest(category=row["category"], date=current.date()):
                self.assertEqual(WEEKDAYS[current.weekday()], matched.group(2))

    def test_value_examples_cover_add_update_and_clear(self) -> None:
        rows = self.rows("_value_")
        modes = Counter()
        integer_values = []
        self.assertEqual(15, len(rows))
        for row in rows:
            response = payload(row)
            if response["intent"] == "calendar_add":
                modes["add"] += 1
                value = response["params"]["value"]
            elif "value" in response["params"]["changes"]:
                modes["update"] += 1
                value = response["params"]["changes"]["value"]
            else:
                modes["clear"] += 1
                self.assertEqual({"clear_value": True}, response["params"]["changes"])
                continue
            self.assertIs(type(value), int)
            integer_values.append(value)
        self.assertEqual(Counter({"add": 9, "update": 4, "clear": 2}), modes)
        self.assertIn(0, integer_values)
        self.assertTrue(any(value < 0 for value in integer_values))
        self.assertTrue(any(value > 0 for value in integer_values))

    def test_omission_examples_emit_only_supplied_fields_plus_mandatory_date(self) -> None:
        rows = self.rows("_omit_")
        kinds = Counter(row["category"].split("_omit_", 1)[1] for row in rows)
        self.assertEqual(40, len(rows))
        self.assertEqual(EXPECTED_OMISSION_COUNTS, kinds)
        for row in rows:
            response = payload(row)
            kind = row["category"].split("_omit_", 1)[1]
            self.assertEqual("calendar_add", response["intent"])
            self.assertEqual(EXPECTED_OMISSION_PARAM_KEYS[kind], set(response["params"]))
            self.assertNotIn("duration_min", response["params"])
            self.assertNotIn("value", response["params"])
            self.assertFalse(response["reply"].startswith("Событие создано:"))

    def test_implicit_time_only_dates_follow_later_today_else_tomorrow(self) -> None:
        rows = [
            row
            for row in self.rows("_omit_")
            if row["category"].endswith(("time_only", "title_time"))
        ]
        self.assertEqual(12, len(rows))
        for row in rows:
            matched = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
            current = datetime.fromisoformat(f"{matched.group(1)}T{matched.group(3)}")
            target = datetime.fromisoformat(payload(row)["params"]["starts_at"])
            expected_date = current.date() + timedelta(days=target.time() <= current.time())
            self.assertEqual(expected_date, target.date())

    def test_every_minute_from_one_through_sixty_is_supervised(self) -> None:
        train_rows = [row for row in self.train if row["category"] == "manual_v12_duration_minute_words"]
        validation_rows = [
            row for row in self.validation if row["category"] == "manual_v12_eval_duration_minute_words"
        ]
        self.assertEqual(list(range(1, 61)), sorted(duration_value(row) for row in train_rows))
        self.assertEqual([1, 2, 3, 4, 5, 11, 15, 21, 29, 30, 37, 45, 50, 59, 60],
                         sorted(duration_value(row) for row in validation_rows))
        for row in train_rows + validation_rows:
            duration = duration_value(row)
            self.assertIn(MINUTE_WORDS[duration - 1], user_text(row))

    def test_hour_minus_one_through_twenty_nine_uses_sixty_minus_n(self) -> None:
        train_rows = [row for row in self.train if row["category"] == "manual_v12_duration_hour_minus"]
        validation_rows = [
            row for row in self.validation if row["category"] == "manual_v12_eval_duration_hour_minus"
        ]
        self.assertEqual(list(range(31, 60)), sorted(duration_value(row) for row in train_rows))
        self.assertEqual([31, 33, 36, 40, 45, 48, 50, 55, 58, 59],
                         sorted(duration_value(row) for row in validation_rows))
        for row in train_rows + validation_rows:
            matches = [
                n
                for n, phrase in enumerate(MINUS_WORDS, start=1)
                if f"без {phrase}" in user_text(row)
            ]
            self.assertEqual(1, len(matches))
            self.assertEqual(60 - matches[0], duration_value(row))

    def test_composite_hours_and_minutes_are_added(self) -> None:
        rows = self.rows("_duration_composite")
        self.assertEqual(50, len(rows))
        self.assertEqual(155, COMPOSITE_DURATIONS["два часа тридцать пять минут"])
        for row in rows:
            phrase = final_duration_phrase(row)
            self.assertIn(phrase, COMPOSITE_DURATIONS)
            self.assertEqual(COMPOSITE_DURATIONS[phrase], duration_value(row))
            self.assertLessEqual(duration_value(row), 2880)

    def test_duration_aliases_have_three_train_and_one_validation_each(self) -> None:
        train_rows = [row for row in self.train if row["category"] == "manual_v12_duration_aliases"]
        validation_rows = [
            row for row in self.validation if row["category"] == "manual_v12_eval_duration_aliases"
        ]
        self.assertEqual(Counter({15: 3, 30: 3, 45: 3, 50: 3, 60: 3, 90: 3,
                                  120: 3, 1440: 3, 2880: 3}),
                         Counter(duration_value(row) for row in train_rows))
        self.assertEqual(Counter({15: 1, 30: 1, 45: 1, 50: 1, 60: 1, 90: 1,
                                  120: 1, 1440: 1, 2880: 1}),
                         Counter(duration_value(row) for row in validation_rows))
        for row in train_rows + validation_rows:
            phrase = final_duration_phrase(row)
            self.assertIn(phrase, ALIAS_DURATIONS)
            self.assertEqual(ALIAS_DURATIONS[phrase], duration_value(row))

    def test_duration_rows_do_not_invent_value(self) -> None:
        rows = self.rows("_duration_")
        self.assertEqual(200, len(rows))
        for row in rows:
            response = payload(row)
            params = response["params"]
            if response["intent"] == "calendar_add":
                self.assertNotIn("value", params)
            else:
                self.assertNotIn("value", params["changes"])
                self.assertNotIn("clear_value", params["changes"])


if __name__ == "__main__":
    unittest.main()
