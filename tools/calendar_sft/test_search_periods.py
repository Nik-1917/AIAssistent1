"""Regression tests for calendar temporal ranges."""

from __future__ import annotations

from datetime import datetime, time, timedelta
import json
from pathlib import Path
import sys
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generate_calendar_training_dataset import (  # noqa: E402
    CLOCK_HOURS_24,
    COMMON_YEAR_MONTH_DAYS,
    DAY_UNIT_HOURS,
    HALF_YEAR_MONTHS,
    MONTHS_GENITIVE,
    MONTHS_NOMINATIVE,
    MONTHS_PREPOSITIONAL,
    QUARTER_MONTHS,
    SEARCH_PERIOD_KINDS,
    SEASON_MONTHS,
    WEEKDAYS,
    WEEKDAYS_AFTER_V,
    add_years,
    calendar_month_days,
    calendar_year_days,
    current_season_bounds,
    half_year_bounds,
    hour_duration_minutes,
    implicit_calendar_add_start,
    is_gregorian_leap_year,
    make_searches,
    next_season_bounds,
    next_weekday_bounds,
    quarter_bounds,
    relative_hour_start,
    search_period,
    season_bounds,
    time_words_24,
    year_bounds,
)


class SearchPeriodTests(unittest.TestCase):
    anchor = datetime(2026, 8, 24, 14, 30)

    def test_today_starts_at_the_supplied_current_time(self) -> None:
        phrase, start, end = search_period(self.anchor, "today")

        self.assertEqual("сегодня", phrase)
        self.assertEqual(datetime(2026, 8, 24, 14, 30), start)
        self.assertEqual(datetime(2026, 8, 25, 0, 0), end)

    def test_this_week_ends_at_the_next_monday(self) -> None:
        anchor = datetime(2026, 8, 26, 14, 30)
        phrase, start, end = search_period(anchor, "this_week")

        self.assertEqual("на этой неделе", phrase)
        self.assertEqual(datetime(2026, 8, 26, 14, 30), start)
        self.assertEqual(datetime(2026, 8, 31, 0, 0), end)

    def test_future_relative_days_use_midnight_boundaries(self) -> None:
        expected = {
            "tomorrow": ("завтра", datetime(2026, 8, 25, 0, 0), datetime(2026, 8, 26, 0, 0)),
            "after_tomorrow": ("послезавтра", datetime(2026, 8, 26, 0, 0), datetime(2026, 8, 27, 0, 0)),
            "third_day": ("послепослезавтра", datetime(2026, 8, 27, 0, 0), datetime(2026, 8, 28, 0, 0)),
        }

        for kind, value in expected.items():
            with self.subTest(kind=kind):
                self.assertEqual(value, search_period(self.anchor, kind))

    def test_future_relative_days_obey_gregorian_leap_boundaries(self) -> None:
        cases = (
            (
                datetime(2024, 2, 28, 14, 30),
                "tomorrow",
                ("завтра", datetime(2024, 2, 29, 0, 0), datetime(2024, 3, 1, 0, 0)),
            ),
            (
                datetime(2023, 2, 28, 14, 30),
                "tomorrow",
                ("завтра", datetime(2023, 3, 1, 0, 0), datetime(2023, 3, 2, 0, 0)),
            ),
            (
                datetime(2032, 2, 27, 14, 30),
                "after_tomorrow",
                ("послезавтра", datetime(2032, 2, 29, 0, 0), datetime(2032, 3, 1, 0, 0)),
            ),
            (
                datetime(2000, 2, 28, 14, 30),
                "tomorrow",
                ("завтра", datetime(2000, 2, 29, 0, 0), datetime(2000, 3, 1, 0, 0)),
            ),
            (
                datetime(2100, 2, 28, 14, 30),
                "tomorrow",
                ("завтра", datetime(2100, 3, 1, 0, 0), datetime(2100, 3, 2, 0, 0)),
            ),
        )

        for anchor, kind, expected in cases:
            with self.subTest(anchor=anchor, kind=kind):
                self.assertEqual(expected, search_period(anchor, kind))

    def test_search_query_variants_are_independent_of_period_kind(self) -> None:
        period_kind_count = len(SEARCH_PERIOD_KINDS)
        rows = make_searches(
            self.anchor,
            ("Проверка пожарных кранов",),
            total=period_kind_count * 3,
        )

        for kind_index in range(period_kind_count):
            queries = {
                json.loads(
                    rows[kind_index + period_kind_count * cycle]["messages"][-1]["content"]
                )["params"]["query"]
                for cycle in range(3)
            }
            with self.subTest(kind_index=kind_index):
                self.assertEqual({"", "Проверка пожарных кранов"}, queries)

        four_days_index = SEARCH_PERIOD_KINDS.index("in_four_days")
        for index in (
            four_days_index,
            four_days_index + period_kind_count,
            four_days_index + period_kind_count * 2,
        ):
            self.assertIn("через четыре дня", rows[index]["messages"][1]["content"])

    def test_weekday_vocabulary_and_next_week_ranges_are_complete(self) -> None:
        self.assertEqual(
            ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"),
            WEEKDAYS,
        )
        self.assertEqual(
            ("в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу", "в воскресенье"),
            WEEKDAYS_AFTER_V,
        )

        anchor = datetime(2029, 9, 3, 14, 0).date()
        for weekday in range(7):
            start, end = next_weekday_bounds(anchor, weekday)
            with self.subTest(weekday=weekday):
                self.assertEqual(datetime(2029, 9, 10 + weekday).date(), start)
                self.assertEqual(start + timedelta(days=1), end)

    def test_month_vocabulary_and_lengths_cover_the_full_year(self) -> None:
        self.assertEqual(12, len(MONTHS_NOMINATIVE))
        self.assertEqual(12, len(MONTHS_GENITIVE))
        self.assertEqual(12, len(MONTHS_PREPOSITIONAL))
        self.assertEqual(
            (31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31),
            COMMON_YEAR_MONTH_DAYS,
        )
        self.assertEqual(COMMON_YEAR_MONTH_DAYS, tuple(calendar_month_days(2031, month) for month in range(1, 13)))
        self.assertEqual(29, calendar_month_days(2032, 2))
        self.assertEqual(28, calendar_month_days(2100, 2))

    def test_quarter_and_half_year_boundaries_are_calendar_ranges(self) -> None:
        self.assertEqual(
            {
                1: (1, 2, 3),
                2: (4, 5, 6),
                3: (7, 8, 9),
                4: (10, 11, 12),
            },
            QUARTER_MONTHS,
        )
        self.assertEqual((datetime(2030, 1, 1).date(), datetime(2030, 4, 1).date()), quarter_bounds(2030, 1))
        self.assertEqual((datetime(2030, 10, 1).date(), datetime(2031, 1, 1).date()), quarter_bounds(2030, 4))
        self.assertEqual((1, 2, 3, 4, 5, 6), HALF_YEAR_MONTHS[1])
        self.assertEqual((7, 8, 9, 10, 11, 12), HALF_YEAR_MONTHS[2])
        self.assertEqual((datetime(2030, 1, 1).date(), datetime(2030, 7, 1).date()), half_year_bounds(2030, 1))
        self.assertEqual((datetime(2030, 7, 1).date(), datetime(2031, 1, 1).date()), half_year_bounds(2030, 2))

    def test_meteorological_seasons_include_winter_year_rollover(self) -> None:
        self.assertEqual(
            {
                "spring": (3, 4, 5),
                "summer": (6, 7, 8),
                "autumn": (9, 10, 11),
                "winter": (12, 1, 2),
            },
            SEASON_MONTHS,
        )
        self.assertEqual(
            (datetime(2030, 3, 1).date(), datetime(2030, 6, 1).date()),
            season_bounds(2030, "spring"),
        )
        self.assertEqual(
            (datetime(2030, 12, 1).date(), datetime(2031, 3, 1).date()),
            season_bounds(2030, "winter"),
        )
        self.assertEqual(
            ("winter", datetime(2029, 12, 1).date(), datetime(2030, 3, 1).date()),
            current_season_bounds(datetime(2030, 1, 15).date()),
        )
        self.assertEqual(
            (datetime(2030, 12, 1).date(), datetime(2031, 3, 1).date()),
            next_season_bounds(datetime(2030, 3, 15).date(), "winter"),
        )

    def test_calendar_year_and_fixed_day_offsets_remain_distinct(self) -> None:
        self.assertFalse(is_gregorian_leap_year(2031))
        self.assertTrue(is_gregorian_leap_year(2032))
        self.assertTrue(is_gregorian_leap_year(2000))
        self.assertFalse(is_gregorian_leap_year(2100))
        self.assertEqual(365, calendar_year_days(2031))
        self.assertEqual(366, calendar_year_days(2032))
        self.assertEqual(datetime(2025, 2, 28).date(), add_years(datetime(2024, 2, 29).date(), 1))
        self.assertEqual(
            (datetime(2032, 1, 1).date(), datetime(2033, 1, 1).date()),
            year_bounds(2032),
        )
        anchor = datetime(2023, 3, 1, 10, 0)
        self.assertEqual(
            ("через год", datetime(2024, 3, 1, 0, 0), datetime(2024, 3, 2, 0, 0)),
            search_period(anchor, "in_one_year"),
        )
        self.assertEqual(
            (
                "через триста шестьдесят пять дней",
                datetime(2024, 2, 29, 0, 0),
                datetime(2024, 3, 1, 0, 0),
            ),
            search_period(anchor, "in_365_days"),
        )

    def test_implicit_add_time_later_than_now_uses_today(self) -> None:
        self.assertEqual(
            datetime(2026, 8, 24, 18, 0),
            implicit_calendar_add_start(self.anchor, datetime(2026, 8, 24, 18, 0).time()),
        )

    def test_implicit_add_time_equal_to_now_uses_tomorrow(self) -> None:
        self.assertEqual(
            datetime(2026, 8, 25, 14, 30),
            implicit_calendar_add_start(self.anchor, datetime(2026, 8, 24, 14, 30).time()),
        )

    def test_implicit_add_time_earlier_than_now_rolls_over_the_year(self) -> None:
        anchor = datetime(2026, 12, 31, 23, 30)

        self.assertEqual(
            datetime(2027, 1, 1, 23, 20),
            implicit_calendar_add_start(anchor, datetime(2026, 12, 31, 23, 20).time()),
        )

    def test_implicit_add_tomorrow_obeys_gregorian_leap_boundaries(self) -> None:
        event_time = time(8, 0)
        expected = {
            datetime(2024, 2, 28, 12, 0): datetime(2024, 2, 29, 8, 0),
            datetime(2023, 2, 28, 12, 0): datetime(2023, 3, 1, 8, 0),
            datetime(2000, 2, 28, 12, 0): datetime(2000, 2, 29, 8, 0),
            datetime(2100, 2, 28, 12, 0): datetime(2100, 3, 1, 8, 0),
        }

        for anchor, start in expected.items():
            with self.subTest(anchor=anchor):
                self.assertEqual(start, implicit_calendar_add_start(anchor, event_time))

    def test_clock_vocabulary_contains_exactly_00_through_23(self) -> None:
        self.assertEqual(set(range(24)), set(CLOCK_HOURS_24))
        self.assertEqual("ноль часов", CLOCK_HOURS_24[0])
        self.assertEqual("шесть часов", CLOCK_HOURS_24[6])
        self.assertEqual("восемнадцать часов", CLOCK_HOURS_24[18])
        self.assertEqual("двадцать три часа", CLOCK_HOURS_24[23])

    def test_clock_words_are_resolved_before_the_date_boundary(self) -> None:
        morning = time(6, 40)
        evening = time(18, 40)

        self.assertEqual("в шесть часов сорок минут", time_words_24(morning))
        self.assertEqual("в восемнадцать часов сорок минут", time_words_24(evening))
        self.assertEqual(
            datetime(2026, 8, 25, 6, 40),
            implicit_calendar_add_start(self.anchor, morning),
        )
        self.assertEqual(
            datetime(2026, 8, 24, 18, 40),
            implicit_calendar_add_start(self.anchor, evening),
        )

    def test_day_units_have_fixed_hour_and_minute_equivalents(self) -> None:
        self.assertEqual(24, DAY_UNIT_HOURS["сутки"])
        self.assertEqual(24, DAY_UNIT_HOURS["одни сутки"])
        self.assertEqual(48, DAY_UNIT_HOURS["двое суток"])
        self.assertEqual(1440, hour_duration_minutes(24))
        self.assertEqual(2880, hour_duration_minutes(48))

    def test_relative_hour_offsets_cross_date_month_and_year_boundaries(self) -> None:
        self.assertEqual(
            datetime(2028, 3, 1, 22, 15),
            relative_hour_start(datetime(2028, 2, 29, 22, 15), 24),
        )
        self.assertEqual(
            datetime(2029, 1, 1, 20, 40),
            relative_hour_start(datetime(2028, 12, 30, 20, 40), 48),
        )

    def test_hour_duration_and_offset_reject_non_positive_values(self) -> None:
        for value in (0, -1, True):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    hour_duration_minutes(value)
                with self.assertRaises(ValueError):
                    relative_hour_start(self.anchor, value)

    def test_one_month_searches_the_target_calendar_day(self) -> None:
        anchor = datetime(2030, 8, 28, 14, 30)

        self.assertEqual(
            ("через месяц", datetime(2030, 9, 28, 0, 0), datetime(2030, 9, 29, 0, 0)),
            search_period(anchor, "in_one_month"),
        )

    def test_one_month_clamps_shorter_target_month(self) -> None:
        anchor = datetime(2031, 1, 31, 14, 30)

        self.assertEqual(
            ("через месяц", datetime(2031, 2, 28, 0, 0), datetime(2031, 3, 1, 0, 0)),
            search_period(anchor, "in_one_month"),
        )

    def test_next_month_and_two_months_remain_complete_months(self) -> None:
        anchor = datetime(2030, 8, 28, 14, 30)

        self.assertEqual(
            ("в следующем месяце", datetime(2030, 9, 1, 0, 0), datetime(2030, 10, 1, 0, 0)),
            search_period(anchor, "next_month"),
        )
        self.assertEqual(
            ("через два месяца", datetime(2030, 10, 1, 0, 0), datetime(2030, 11, 1, 0, 0)),
            search_period(anchor, "in_two_months"),
        )

    def test_sum_periods_cover_past_week_month_and_year(self) -> None:
        self.assertEqual(
            ("вчера", datetime(2026, 8, 23, 0, 0), datetime(2026, 8, 24, 0, 0)),
            search_period(self.anchor, "yesterday"),
        )
        self.assertEqual(
            ("на прошлой неделе", datetime(2026, 8, 17, 0, 0), datetime(2026, 8, 24, 0, 0)),
            search_period(self.anchor, "previous_week"),
        )
        self.assertEqual(
            ("в этом месяце", datetime(2026, 8, 1, 0, 0), datetime(2026, 9, 1, 0, 0)),
            search_period(self.anchor, "current_month"),
        )
        self.assertEqual(
            ("в этом году", datetime(2026, 1, 1, 0, 0), datetime(2027, 1, 1, 0, 0)),
            search_period(self.anchor, "current_year"),
        )

if __name__ == "__main__":
    unittest.main()
