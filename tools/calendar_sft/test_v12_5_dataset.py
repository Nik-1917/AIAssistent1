"""Regression checks for literal time examples and reply-only presentation."""

from copy import deepcopy
import json
import unittest

from dataset_contract import DatasetContractError, normalize_record, parse_and_validate_assistant_response
from prepare_v12_5 import (
    COUNT_SOURCES, MANUAL, OUTPUT, SOURCE, SOURCE_HASHES,
    count_reference, read_clock_phrase, read_json, validate_inputs, validate_manual,
)


class V125DatasetTest(unittest.TestCase):
    def setUp(self):
        self.manual = read_json(MANUAL)

    def test_matches_audited_reference_without_importing_v14_rows(self):
        added, outputs = validate_inputs()
        self.assertEqual({"train": 2, "validation": 2}, {s: count_reference(p) for s, p in COUNT_SOURCES.items()})
        self.assertEqual({"train": 6, "validation": 6}, {s: len(rows) for s, rows in added.items()})
        self.assertEqual(1956, len(outputs["train"]))
        self.assertEqual(587, len(outputs["validation"]))

    def test_handwritten_dates_and_durations_against_independent_expectations(self):
        expected = {
            "V125T01": ("2027-04-13T08:15", 20), "V125T02": ("2028-06-14T17:15", 60),
            "V125T03": ("2028-03-01T02:30", 30), "V125T04": ("2030-07-10T22:30", 15),
            "V125T05": ("2028-01-01T11:30", 45), "V125T06": ("2031-12-31T23:30", 30),
            "V125E01": ("2029-03-01T03:15", 15), "V125E02": ("2030-09-14T15:15", 30),
            "V125E03": ("2028-03-01T07:30", 20), "V125E04": ("2029-11-19T12:30", 45),
            "V125E05": ("2031-09-05T00:30", 30), "V125E06": ("2027-08-09T20:30", 60),
        }
        for example in self.manual["examples"]:
            row = example["row"]
            params = json.loads(row["messages"][-1]["content"])["params"]
            self.assertEqual(expected[row["case_id"]], (params["starts_at"], params["duration_min"]))

    def test_recognizes_spelling_and_noon_midnight_distinctions(self):
        for phrase, expected in (
            ("четверть первого дня", ("quarter", 13, "12:15")),
            ("четверть первого ночи", ("quarter", 1, "00:15")),
            ("в пол-одиннадцатого вечера", ("pol", 23, "22:30")),
            ("в пол восьмого утра", ("pol", 8, "07:30")),
            ("половина двенадцатого дня", ("polovina", 12, "11:30")),
            ("половина двенадцатого ночи", ("polovina", 0, "23:30")),
        ):
            self.assertEqual(expected, read_clock_phrase(phrase))
        for duration in ("четверть часа", "полчаса", "половина часа"):
            with self.assertRaises(ValueError):
                read_clock_phrase(duration)

    def test_rejects_hour_or_duration_corruption_in_the_handwritten_answer(self):
        for field, wrong in (("starts_at", "2027-04-13T09:15"), ("duration_min", 15)):
            manual = deepcopy(self.manual)
            message = manual["examples"][0]["row"]["messages"][-1]
            response = json.loads(message["content"])
            response["params"][field] = wrong
            message["content"] = json.dumps(response, ensure_ascii=False)
            with self.assertRaisesRegex(ValueError, "wrong date, clock time or duration"):
                validate_manual(manual)

    def test_rejects_mislabeled_clock_and_reduced_quota(self):
        manual = deepcopy(self.manual)
        manual["examples"][0]["next_hour_24"] = 10
        with self.assertRaisesRegex(ValueError, "contradicts"):
            validate_manual(manual)
        manual = deepcopy(self.manual)
        manual["examples"].pop()
        with self.assertRaisesRegex(ValueError, "two examples per form"):
            validate_manual(manual)

    def test_prepared_data_preserves_every_inherited_byte(self):
        for split in SOURCE_HASHES:
            original = (SOURCE / f"{split}.jsonl").read_bytes()
            prepared = (OUTPUT / f"{split}.jsonl").read_bytes()
            if split in ("train", "validation"):
                self.assertTrue(prepared.startswith(original))
                self.assertEqual(6, len(prepared[len(original):].splitlines()))
            else:
                self.assertEqual(original, prepared)


class V125ReplyScopeTest(unittest.TestCase):
    def setUp(self):
        self.row = deepcopy(read_json(MANUAL)["examples"][0]["row"])

    def test_presentation_does_not_rewrite_user_text_or_parameters(self):
        title = "Отчёт «План — 2032» в 09:15?"
        user = 'Запиши ' + title + ' завтра в 08:15 на двадцать минут.'
        self.row["messages"][1]["content"] = user
        response = json.loads(self.row["messages"][-1]["content"])
        response["params"]["title"] = title
        original = deepcopy(response["params"])
        self.row["messages"][-1]["content"] = json.dumps(response, ensure_ascii=False)
        normalized = normalize_record(self.row, "scope")
        self.assertEqual(user, normalized["messages"][1]["content"])
        self.assertEqual(original, json.loads(normalized["messages"][-1]["content"])["params"])

    def test_nested_target_and_changes_do_not_inherit_reply_style(self):
        response = {
            "intent": "calendar_update", "reply": "Событие изменено: новое время в половине седьмого вечера.",
            "params": {"target": {"query": "План «2032» — 09:15"},
                       "changes": {"title": "Проект «2033» — 08:30", "time": "18:30"}},
        }
        result = parse_and_validate_assistant_response(json.dumps(response, ensure_ascii=False), contract_version="v12.5")
        self.assertEqual(response["params"], result["params"])

    def test_reply_still_rejects_digits_years_and_forbidden_punctuation(self):
        response = json.loads(self.row["messages"][-1]["content"])
        for reply in ("Встреча в 08:15.", "Встреча в 2032 году.", "Встреча — в четверть девятого утра."):
            response["reply"] = reply
            with self.assertRaises(DatasetContractError):
                parse_and_validate_assistant_response(json.dumps(response, ensure_ascii=False), contract_version="v12.5")

    def test_legacy_title_only_contract_is_preserved(self):
        response = self.row["messages"][-1]["content"]
        parse_and_validate_assistant_response(response, contract_version="v12.5")
        with self.assertRaisesRegex(DatasetContractError, "only the title"):
            parse_and_validate_assistant_response(response, contract_version="v12.1")

    def test_new_contract_excludes_removed_intent_and_false_creation_claim(self):
        with self.assertRaises(DatasetContractError):
            parse_and_validate_assistant_response(
                '{"intent":"note_add","reply":"Сохраняю заметку.","params":{"text":"текст"}}',
                contract_version="v12.5",
            )
        response = json.loads(self.row["messages"][-1]["content"])
        response["reply"] = "Событие создано: встреча в четверть девятого утра."
        with self.assertRaisesRegex(DatasetContractError, "unexecuted"):
            parse_and_validate_assistant_response(json.dumps(response, ensure_ascii=False), contract_version="v12.5")


if __name__ == "__main__":
    unittest.main()
