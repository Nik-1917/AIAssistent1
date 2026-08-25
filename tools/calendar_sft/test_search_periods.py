"""Regression tests for temporal ranges in generated calendar searches."""

from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import sys
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generate_calendar_training_dataset import make_searches, search_period  # noqa: E402


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

    def test_posleposlezavtra_has_a_search_reply(self) -> None:
        row = make_searches(self.anchor, ("Проба",), total=1, start_index=3)[0]
        response = json.loads(row["messages"][-1]["content"])

        self.assertEqual("Проверяю все события послепослезавтра.", response["reply"])
        self.assertEqual("2026-08-27T00:00", response["params"]["range_start"])
        self.assertEqual("2026-08-28T00:00", response["params"]["range_end"])


if __name__ == "__main__":
    unittest.main()
