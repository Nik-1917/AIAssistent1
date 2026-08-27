"""Regression tests for calendar temporal ranges."""

from __future__ import annotations

from datetime import datetime, time
from pathlib import Path
import sys
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generate_calendar_training_dataset import (  # noqa: E402
    CLOCK_HOURS_24,
    DAY_UNIT_HOURS,
    hour_duration_minutes,
    implicit_calendar_add_start,
    relative_hour_start,
    search_period,
    time_words_24,
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
