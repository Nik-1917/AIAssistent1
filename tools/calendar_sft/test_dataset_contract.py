"""Focused regression tests for calendar-assistant reply rules."""

from __future__ import annotations

import json
import unittest

from dataset_contract import DatasetContractError, normalize_record, parse_and_validate_assistant_response


def response(reply: str) -> str:
    return json.dumps(
        {
            "intent": "calendar_add",
            "reply": reply,
            "params": {
                "title": "Встреча",
                "starts_at": "2027-02-04T09:30",
                "duration_min": 30,
            },
        },
        ensure_ascii=False,
    )


class ReplyContractTest(unittest.TestCase):
    def test_complete_calendar_reply_accepts_words_for_time(self) -> None:
        parsed = parse_and_validate_assistant_response(
            response("Событие создано: Встреча завтра в девять часов тридцать минут утра."),
        )

        self.assertEqual("calendar_add", parsed["intent"])

    def test_reply_rejects_single_digit_clock(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "spell event times"):
            parse_and_validate_assistant_response(response("Событие создано: Встреча завтра в 7:05."))

    def test_reply_rejects_numeric_year(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not contain a year"):
            parse_and_validate_assistant_response(response("Событие создано: Встреча четвёртого февраля 2027 года."))

    def test_user_request_rejects_numeric_clock(self) -> None:
        record = {
            "category": "calendar_add",
            "messages": [
                {"role": "system", "content": "Сегодня дата и время:2027-02-03 (среда) 14:30 Europe/Samara ответ JSON"},
                {"role": "user", "content": "Запиши встречу завтра в 12:00 на час."},
                {"role": "assistant", "content": response("Событие создано: Встреча завтра в двенадцать ноль ноль.")},
            ],
        }

        with self.assertRaisesRegex(DatasetContractError, "must spell clock times in words"):
            normalize_record(record, "test")

    def test_user_request_rejects_spaced_numeric_clock(self) -> None:
        record = {
            "category": "calendar_add",
            "messages": [
                {"role": "system", "content": "Сегодня дата и время:2027-02-03 (среда) 14:30 Europe/Samara ответ JSON"},
                {"role": "user", "content": "Запиши встречу завтра в 11 20 на час."},
                {"role": "assistant", "content": response("Событие создано: Встреча завтра в одиннадцать часов двадцать минут утра.")},
            ],
        }

        with self.assertRaisesRegex(DatasetContractError, "must spell clock times in words"):
            normalize_record(record, "test")


if __name__ == "__main__":
    unittest.main()
