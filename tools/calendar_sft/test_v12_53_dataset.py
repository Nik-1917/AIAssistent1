"""Clock meaning, reply-only edits and preservation checks for V12.53."""

from collections import Counter
from copy import deepcopy
import json
import unittest

from dataset_contract import DatasetContractError, file_sha256, load_jsonl, normalize_record
from dataset_provenance import verify_dataset_provenance
from prepare_v12_52 import read_minutes_of_hour
from prepare_v12_53 import (
    MANUAL, OUTPUT, REPLY_EDITS, SOURCE, SOURCE_HASHES, apply_reply_edits,
    has_clock_daypart, literal_row, read_compact_clock, read_json,
    read_spoken_clock, validate_example, validate_inputs, validate_manual, word_order,
)


class V1253DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manual = read_json(MANUAL)
        cls.cases = {e["id"]: e for e in cls.manual["examples"]}

    def test_all_hours_minutes_splits_and_word_orders(self):
        added, outputs = validate_inputs()
        self.assertEqual({"train": 265, "validation": 73}, {s: len(rs) for s, rs in added.items()})
        self.assertEqual(
            {"train": 2401, "validation": 720, "holdout": 36,
             "calendar_holdout_v12_2": 8, "regression_holdout": 63},
            {s: len(rs) for s, rs in outputs.items()},
        )
        for letter, minutes, per_order in (
            ("T", set(range(5, 60, 5)), 44), ("E", {5, 30, 55}, 12),
        ):
            rows = [e for e in self.manual["examples"] if e["id"].startswith("V1253" + letter)]
            for hour in range(24):
                actual = {
                    int(e["assistant"]["params"]["starts_at"][14:16]) for e in rows
                    if int(e["assistant"]["params"]["starts_at"][11:13]) == hour
                }
                self.assertEqual(minutes | ({0} if hour == 0 else set()), actual)
            orders = Counter(word_order(e) for e in rows if not e["id"].endswith("0000"))
            self.assertEqual({k: per_order for k in ("EDT", "ETD", "DET", "DTE", "TED", "TDE")}, orders)
        # The event may precede the verb; it is not located by the verb alone.
        self.assertEqual("ETD", word_order(self.cases["V1253T0130"]))

    def test_cardinal_current_hour_differs_from_ordinal_upcoming_hour(self):
        for phrase, expected in (
            ("час пять", (1, 5)), ("час десять", (1, 10)), ("час пятнадцать", (1, 15)),
            ("один час пять минут", (1, 5)), ("два двадцать пять", (2, 25)),
            ("пять тридцать пять", (5, 35)), ("двадцать пять", (20, 5)),
            ("двадцать один двадцать пять", (21, 25)), ("двадцать два сорок", (22, 40)),
            ("двадцать три пятьдесят пять", (23, 55)),
            ("ДВАДЦАТЬ ТРИ ЧАСА ПЯТЬДЕСЯТ ПЯТЬ МИНУТ", (23, 55)),
            ("ноль пять", (0, 5)), ("ноль часов", (0, 0)),
        ):
            with self.subTest(phrase=phrase):
                self.assertEqual(expected, read_compact_clock(phrase))
        self.assertEqual("00:05", read_minutes_of_hour("пять минут первого ночи")[3])
        self.assertEqual("12:05", read_minutes_of_hour("пять минут первого дня")[3])
        self.assertEqual("2027-09-01T01:05", self.cases["V1253T0105"]["assistant"]["params"]["starts_at"])
        self.assertEqual("2027-09-01T20:05", self.cases["V1253T2005"]["assistant"]["params"]["starts_at"])
        self.assertEqual("2027-09-01T21:25", self.cases["V1253T2125"]["assistant"]["params"]["starts_at"])
        self.assertEqual("2029-01-01T23:55", self.cases["V1253E2355"]["assistant"]["params"]["starts_at"])

    def test_duration_or_malformed_hour_is_not_a_compact_clock(self):
        for phrase in (
            "на двадцать пять минут", "через десять минут", "двадцать пять минут",
            "час", "двадцать четыре пять", "два шестьдесят", "час час пять",
            "один часов пять минут", "двадцать два час пять минут",
            "ноль часов слово", "двадцать один пятьдесят пять минут",
        ):
            with self.subTest(phrase=phrase), self.assertRaises(ValueError):
                read_compact_clock(phrase)

    def test_reply_has_correct_twelve_hour_meaning_without_daypart(self):
        for phrase, minute, expected in (
            ("в четверть третьего", 15, (2, 15)),
            ("в половине третьего", 30, (2, 30)),
            ("без четверти семь", 45, (6, 45)),
            ("без четверти час", 45, (0, 45)),
            ("в пять минут первого", 5, (0, 5)),
            ("в пятьдесят пять минут двенадцатого", 55, (11, 55)),
            ("в ноль часов", 0, (0, 0)),
        ):
            self.assertEqual(expected, read_spoken_clock(phrase, minute))
        for phrase, minute in (
            ("в четверть третьего дня", 15), ("в половине третьего ночи", 30),
            ("без четверти семь вечера", 45), ("в пять минут третьего утра", 5),
            ("в половине третьего", 15), ("в четверть третьего", 20),
        ):
            with self.assertRaises(ValueError):
                read_spoken_clock(phrase, minute)

    def test_wrong_hour_minute_and_extra_midnight_date_shift_are_rejected(self):
        for case_id, wrong in (
            ("V1253T0105", "2027-09-01T00:05"),
            ("V1253T2005", "2027-09-01T02:05"),
            ("V1253T2125", "2027-09-01T01:25"),
            ("V1253T2125", "2027-09-01T21:15"),
            ("V1253T0000", "2027-09-02T00:00"),
            ("V1253T2355", "2027-09-02T23:55"),
            ("V1253E0000", "2029-01-02T00:00"),
        ):
            example = deepcopy(self.cases[case_id])
            example["assistant"]["params"]["starts_at"] = wrong
            with self.subTest(case_id=case_id), self.assertRaisesRegex(ValueError, "wrong date or clock time"):
                validate_example(example)

    def test_duration_is_independent_and_title_may_contain_na(self):
        example = deepcopy(self.cases["V1253T0630"])
        self.assertIn("тренировку на дорожке", example["user"])
        self.assertEqual(40, example["assistant"]["params"]["duration_min"])
        validate_example(example)
        example["assistant"]["params"]["duration_min"] = 30
        with self.assertRaisesRegex(ValueError, "wrong explicit duration"):
            validate_example(example)

    def test_wrong_reply_minute_daypart_and_title_are_rejected(self):
        for reply in (
            "Осмотр панели завтра, в десять минут второго, на двадцать минут.",
            "Осмотр панели завтра, в пять минут второго ночи, на двадцать минут.",
            "Осмотр панели завтра, в пять минут второго, на десять минут.",
            "Проверка завтра, в пять минут второго, на двадцать минут.",
        ):
            example = deepcopy(self.cases["V1253T0105"])
            example["assistant"]["reply"] = reply
            with self.subTest(reply=reply), self.assertRaises(ValueError):
                validate_example(example)

    def test_missing_combination_duplicate_id_and_wrong_weekday_are_rejected(self):
        manual = deepcopy(self.manual)
        manual["examples"].pop()
        with self.assertRaisesRegex(ValueError, "expected 264"):
            validate_manual(manual)
        manual = deepcopy(self.manual)
        manual["examples"].append(deepcopy(manual["examples"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate manual ID"):
            validate_manual(manual)
        example = deepcopy(self.cases["V1253T0105"])
        example["system"] = example["system"].replace("вторник", "понедельник")
        with self.assertRaisesRegex(ValueError, "wrong system weekday"):
            validate_example(example)

    def test_new_conversations_survive_serialization_literally(self):
        for example in self.manual["examples"]:
            row = validate_example(example)
            self.assertEqual(example["system"], row["messages"][0]["content"])
            self.assertEqual(example["user"], row["messages"][1]["content"])
            self.assertEqual(example["assistant"], json.loads(row["messages"][-1]["content"]))

    def test_only_selected_inherited_reply_fields_change_and_sources_are_frozen(self):
        _, outputs = validate_inputs()
        changed = Counter()
        for split, digest in SOURCE_HASHES.items():
            self.assertEqual(digest, file_sha256(SOURCE / f"{split}.jsonl"))
            old_lines = (SOURCE / f"{split}.jsonl").read_bytes().splitlines(keepends=True)
            new_lines = (OUTPUT / f"{split}.jsonl").read_bytes().splitlines(keepends=True)
            self.assertEqual(outputs[split], load_jsonl(OUTPUT / f"{split}.jsonl"))
            for index, old_line in enumerate(old_lines):
                old, new = json.loads(old_line), json.loads(new_lines[index])
                if old == new:
                    self.assertEqual(old_line, new_lines[index])
                    continue
                changed[split] += 1
                old_response = json.loads(old["messages"][-1]["content"])
                new_response = json.loads(new["messages"][-1]["content"])
                old_response["reply"] = new_response["reply"]
                self.assertEqual(old_response, new_response)
                old["messages"][-1]["content"] = new["messages"][-1]["content"]
                self.assertEqual(old, new)
        self.assertEqual({"train": 260, "validation": 74, "regression_holdout": 3}, changed)

    def test_dayparts_remain_in_inputs_and_exact_afternoon_time_is_retained(self):
        rows = {r.get("case_id"): r for r in load_jsonl(OUTPUT / "train.jsonl")}
        row = rows["V1252T0330"]
        self.assertIn("третьего дня", row["messages"][1]["content"])
        response = json.loads(row["messages"][-1]["content"])
        self.assertEqual("2027-04-13T14:30", response["params"]["starts_at"])
        self.assertEqual("Упаковка демонстрационных деталей завтра, в половине третьего, на сорок минут.", response["reply"])
        row = rows["V1253T1415"]
        self.assertEqual("2027-09-01T14:15", json.loads(row["messages"][-1]["content"])["params"]["starts_at"])
        self.assertIn("в четверть третьего,", row["messages"][-1]["content"])

    def test_clock_audit_preserves_date_phrases_and_exact_titles(self):
        for reply in (
            "Через три дня будет встреча.", "За два дня до поездки.",
            "Для определения дня недели нужен год.", "Итоги дня и планы вечера.",
        ):
            self.assertFalse(has_clock_daypart({"reply": reply, "params": {}}))
        title = "Четверть третьего дня"
        self.assertFalse(has_clock_daypart({"reply": title + " завтра.", "params": {"title": title}}))
        for reply in (
            "Начало в четверть третьего дня.", "Начало в полтретьего дня.",
            "Начало в пол-одиннадцатого вечера.", "Начало в половине первого ночи.",
            "Начало без четверти семь вечера.", "Начало в десять часов утра.",
            "Начало в три часа двадцать минут дня.", "Начало в пять минут третьего дня.",
        ):
            with self.subTest(reply=reply):
                self.assertTrue(has_clock_daypart({"reply": reply, "params": {}}))
        for split in SOURCE_HASHES:
            for row in load_jsonl(OUTPUT / f"{split}.jsonl"):
                self.assertFalse(has_clock_daypart(json.loads(row["messages"][-1]["content"])))

    def test_manual_edit_register_rejects_extra_language_changes_and_unused_edits(self):
        sources = {s: load_jsonl(SOURCE / f"{s}.jsonl") for s in SOURCE_HASHES}
        edits = read_json(REPLY_EDITS)
        edits["by_case_id"]["V125T01"] = "Время исправлено."
        with self.assertRaisesRegex(ValueError, "more than a daypart"):
            apply_reply_edits(sources, edits)
        edits = read_json(REPLY_EDITS)
        edits["by_case_id"]["MISSING"] = "Новый ответ."
        with self.assertRaisesRegex(ValueError, "unused manual reply edit"):
            apply_reply_edits(sources, edits)

    def test_reply_style_does_not_rewrite_user_titles_or_numeric_params(self):
        example = deepcopy(self.cases["V1253T1415"])
        example["user"] = "Запиши «Итоги дня — 2033» завтра в 14:15 на четверть часа."
        example["assistant"]["params"]["title"] = "Итоги дня — 2033"
        row = normalize_record(literal_row(example), "scope")
        self.assertEqual(example["user"], row["messages"][1]["content"])
        self.assertEqual(example["assistant"]["params"], json.loads(row["messages"][-1]["content"])["params"])
        example["assistant"]["reply"] = "Встреча в 14:15."
        with self.assertRaises(DatasetContractError):
            normalize_record(literal_row(example), "scope")

    def test_output_hashes_and_provenance_match_manual_sources(self):
        manifest = read_json(OUTPUT / "manifest.json")
        self.assertEqual(file_sha256(MANUAL), manifest["manual_sha256"])
        self.assertEqual(file_sha256(REPLY_EDITS), manifest["reply_edits_manual_sha256"])
        for split in SOURCE_HASHES:
            self.assertEqual(file_sha256(OUTPUT / f"{split}.jsonl"), manifest["artifacts"][split]["sha256"])
        result = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual("aiassistent1-assistant-sft-v12.53", result["dataset_id"])
        self.assertEqual("NOT_RUN", manifest["training"])
        self.assertEqual("NOT_RUN", manifest["model_evaluation"])


if __name__ == "__main__":
    unittest.main()
