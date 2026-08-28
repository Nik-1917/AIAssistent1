"""Focused regression tests for calendar-assistant reply rules."""

from __future__ import annotations

import json
import unittest

from dataset_contract import DatasetContractError, normalize_record, parse_and_validate_assistant_response
from prepare_dataset import MODEL_MANIFEST


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
    def test_preparer_uses_the_verified_qwen3_source_lock(self) -> None:
        manifest = json.loads(MODEL_MANIFEST.read_text(encoding="utf-8"))

        self.assertEqual("clean_room_qwen3_source_lock.json", MODEL_MANIFEST.name)
        self.assertEqual("Qwen/Qwen3-4B-Instruct-2507", manifest["source"]["repository"])
        self.assertEqual("VERIFIED", manifest["license"]["status"])

    def test_complete_calendar_reply_accepts_words_for_time(self) -> None:
        parsed = parse_and_validate_assistant_response(
            response("Событие создано: Встреча завтра, в девять часов тридцать минут утра."),
        )

        self.assertEqual("calendar_add", parsed["intent"])

    def test_complete_add_accepts_h050_reply_without_action_prefix(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_add",
                    "reply": "Поезд до Сызрани послезавтра, в шесть часов сорок минут утра.",
                    "params": {
                        "title": "Поезд до Сызрани",
                        "starts_at": "2027-02-05T06:40",
                        "duration_min": 55,
                        "value": 30,
                    },
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual("calendar_add", parsed["intent"])
        self.assertEqual(30, parsed["params"]["value"])

    def test_partial_add_still_rejects_the_created_action_prefix(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not begin"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Событие создано: Проверка отчёта относится к сегодняшней дате.",
                        "params": {
                            "title": "Проверка отчёта",
                            "date": "2027-02-03",
                        },
                    },
                    ensure_ascii=False,
                ),
            )

    def test_reply_rejects_single_digit_clock(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "spell event times"):
            parse_and_validate_assistant_response(response("Событие создано: Встреча завтра, в 7:05."))

    def test_reply_rejects_numeric_year(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not contain a year"):
            parse_and_validate_assistant_response(response("Событие создано: Встреча четвёртого февраля 2027 года."))

    def test_complete_calendar_reply_requires_a_comma_before_time(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must put a comma"):
            parse_and_validate_assistant_response(
                response("Событие создано: Встреча завтра в девять часов тридцать минут утра."),
            )

    def test_user_request_rejects_numeric_clock(self) -> None:
        record = {
            "category": "calendar_add",
            "messages": [
                {"role": "system", "content": "Сегодня дата и время:2027-02-03 (среда) 14:30 Europe/Samara ответ JSON"},
                {"role": "user", "content": "Запиши встречу завтра в 12:00 на час."},
                {"role": "assistant", "content": response("Событие создано: Встреча завтра, в двенадцать ноль ноль.")},
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
                {"role": "assistant", "content": response("Событие создано: Встреча завтра, в одиннадцать часов двадцать минут утра.")},
            ],
        }

        with self.assertRaisesRegex(DatasetContractError, "must spell clock times in words"):
            normalize_record(record, "test")

    def test_partial_add_keeps_a_known_date_without_a_time(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_add",
                    "reply": "Проверка отчёта седьмого декабря, длительность два часа.",
                    "params": {
                        "title": "Проверка отчёта",
                        "date": "2030-12-07",
                        "duration_min": 120,
                    },
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual("2030-12-07", parsed["params"]["date"])

    def test_add_rejects_a_known_time_without_an_inferred_date(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "explicit or inferred date"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Проверка отчёта в шесть часов тридцать минут вечера, длительность два часа.",
                        "params": {
                            "title": "Проверка отчёта",
                            "time": "18:30",
                            "duration_min": 120,
                        },
                    },
                    ensure_ascii=False,
                ),
            )

    def test_add_rejects_missing_date_when_exact_time_is_unknown(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "explicit or inferred date"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Проверка отчёта.",
                        "params": {"title": "Проверка отчёта"},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_add_accepts_known_fields_with_an_inferred_today_date(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_add",
                    "reply": "Проверка отчёта относится к сегодняшней дате.",
                    "params": {"title": "Проверка отчёта", "date": "2030-12-06"},
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual(
            {"title": "Проверка отчёта", "date": "2030-12-06"},
            parsed["params"],
        )

    def test_add_rejects_mixing_full_and_partial_start_fields(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not be combined"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Событие создано: Проверка отчёта.",
                        "params": {
                            "title": "Проверка отчёта",
                            "starts_at": "2030-12-07T18:30",
                            "date": "2030-12-07",
                            "duration_min": 120,
                        },
                    },
                    ensure_ascii=False,
                ),
            )

    def test_reply_rejects_a_clarification_question(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not ask"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Уточните длительность события.",
                        "params": {"title": "Проверка отчёта"},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_reply_rejects_indirect_clarification_without_question_mark(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not ask"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_search",
                        "reply": "Скажите, за какую неделю проверить события.",
                        "params": {"query": "событие"},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_how_to_instruction_is_not_treated_as_clarification(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "chat",
                    "reply": "Скажите: покажи события на следующей неделе.",
                    "params": {},
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual("chat", parsed["intent"])

    def test_chat_may_explain_which_search_inputs_are_supported(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "chat",
                    "reply": "Да, назовите слово из названия и период поиска.",
                    "params": {},
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual("chat", parsed["intent"])

    def test_calendar_reply_rejects_a_direct_request_for_a_missing_field(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not request"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Назовите время события.",
                        "params": {"title": "Проверка отчёта"},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_reply_rejects_excluded_generic_fallbacks(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "excluded generic"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Данные события распознаны.",
                        "params": {"title": "Проверка отчёта"},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_reply_rejects_a_long_dash(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "forbidden text punctuation"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Проверка отчёта \u2014 сегодня.",
                        "params": {"title": "Проверка отчёта", "date": "2030-12-06"},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_response_params_reject_angle_quotes(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "forbidden text punctuation"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Проверка отчёта относится к сегодняшней дате.",
                        "params": {
                            "title": "\u00abПроверка отчёта\u00bb",
                            "date": "2030-12-06",
                        },
                    },
                    ensure_ascii=False,
                ),
            )

    def test_user_text_rejects_angle_quotes(self) -> None:
        record = {
            "category": "calendar_add",
            "messages": [
                {
                    "role": "system",
                    "content": "Сегодня дата и время:2030-12-06 (пятница) 14:30 Europe/Samara ответ JSON",
                },
                {"role": "user", "content": "Добавь \u00abПроверку отчёта\u00bb."},
                {
                    "role": "assistant",
                    "content": json.dumps(
                        {
                            "intent": "calendar_add",
                            "reply": "Проверка отчёта относится к сегодняшней дате.",
                            "params": {"title": "Проверка отчёта", "date": "2030-12-06"},
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
        }

        with self.assertRaisesRegex(DatasetContractError, "forbidden text punctuation"):
            normalize_record(record, "test")

    def test_add_accepts_a_signed_integer_value(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_add",
                    "reply": "Событие создано: Выезд завтра, в девять часов утра.",
                    "params": {
                        "title": "Выезд",
                        "starts_at": "2030-12-07T09:00",
                        "duration_min": 60,
                        "value": -12,
                    },
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual(-12, parsed["params"]["value"])

    def test_add_rejects_a_fractional_value(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "expected an integer"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Событие создано: Выезд завтра, в девять часов утра.",
                        "params": {
                            "title": "Выезд",
                            "starts_at": "2030-12-07T09:00",
                            "duration_min": 60,
                            "value": 12.5,
                        },
                    },
                    ensure_ascii=False,
                ),
            )

    def test_add_rejects_a_boolean_value(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "expected an integer"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Распознана ценность события.",
                        "params": {"date": "2030-12-06", "value": True},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_add_rejects_currency_fields(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "unsupported calendar_add field"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_add",
                        "reply": "Событие создано: Выезд завтра, в девять часов утра.",
                        "params": {
                            "title": "Выезд",
                            "starts_at": "2030-12-07T09:00",
                            "duration_min": 60,
                            "value": 12,
                            "currency": "RUB",
                        },
                    },
                    ensure_ascii=False,
                ),
            )

    def test_update_accepts_setting_and_clearing_value(self) -> None:
        set_value = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_update",
                    "reply": "Событие изменено: ценность выезда равна двенадцати единицам.",
                    "params": {"target": {"query": "выезд"}, "changes": {"value": 12}},
                },
                ensure_ascii=False,
            ),
        )
        clear_value = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_update",
                    "reply": "Событие изменено: ценность выезда удалена.",
                    "params": {"target": {"query": "выезд"}, "changes": {"clear_value": True}},
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual(12, set_value["params"]["changes"]["value"])
        self.assertTrue(clear_value["params"]["changes"]["clear_value"])

    def test_update_rejects_setting_and_clearing_value_together(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not combine value"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_update",
                        "reply": "Событие изменено: ценность выезда обновлена.",
                        "params": {
                            "target": {"query": "выезд"},
                            "changes": {"value": 12, "clear_value": True},
                        },
                    },
                    ensure_ascii=False,
                ),
            )

    def test_update_accepts_known_parts_without_an_executable_target(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_update",
                    "reply": "Распознан запрос на изменение без названного события.",
                    "params": {"target": {}, "changes": {}},
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual({"target": {}, "changes": {}}, parsed["params"])

    def test_delete_accepts_an_omitted_target_without_claiming_deletion(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_delete",
                    "reply": "Распознана команда удаления без названного события.",
                    "params": {"target": {}},
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual({}, parsed["params"]["target"])

    def test_calendar_sum_accepts_a_relative_period_range(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_sum",
                    "reply": "Ценность выездов за прошлый месяц.",
                    "params": {
                        "query": "выезд",
                        "range_start": "2030-11-01T00:00",
                        "range_end": "2030-12-01T00:00",
                    },
                },
                ensure_ascii=False,
            ),
        )

        self.assertEqual("calendar_sum", parsed["intent"])

    def test_calendar_sum_accepts_an_unknown_period_without_inventing_fields(self) -> None:
        parsed = parse_and_validate_assistant_response(
            json.dumps(
                {
                    "intent": "calendar_sum",
                    "reply": "Ценность выездов.",
                    "params": {"query": "выезд"},
                },
                ensure_ascii=False,
            ),
        )

        self.assertNotIn("range_start", parsed["params"])

    def test_calendar_sum_rejects_one_range_boundary(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must be paired"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_sum",
                        "reply": "Ценность выездов за прошлый месяц.",
                        "params": {"query": "выезд", "range_start": "2030-11-01T00:00"},
                    },
                    ensure_ascii=False,
                ),
            )

    def test_calendar_sum_rejects_a_claimed_numeric_result(self) -> None:
        with self.assertRaisesRegex(DatasetContractError, "must not state"):
            parse_and_validate_assistant_response(
                json.dumps(
                    {
                        "intent": "calendar_sum",
                        "reply": "Итого 42 единицы.",
                        "params": {"query": "выезд"},
                    },
                    ensure_ascii=False,
                ),
            )


if __name__ == "__main__":
    unittest.main()
