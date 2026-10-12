"""Regression checks of handwritten reply scope, four-action boundary and parameter arithmetic."""
from collections import Counter
from copy import deepcopy
import json
import unittest

from prepare_v12_67 import assemble, verify_preserved_sources
from evaluate_v12_67 import case_digest, prepared_inputs, score_cached
from v12_67_contract import (DatasetContractError, INTENTS, load_jsonl,
                            parse_and_validate_assistant_response)
from v12_67_evaluation import VERSION, case_from_row, grade, selection_score, summary
from v12_67_training import (CONFIG, DATA, PROVIDER, ROOT, development_cases,
                            frozen_paths, read, selection_steps, system_text, variant_row)


def raw(payload):
    return json.dumps(payload, ensure_ascii=False)


class V1267Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = assemble(check_only=True)
        cls.rows = {name: load_jsonl(DATA / (name + ".jsonl")) for name in cls.manifest["artifacts"]}
        cls.train = cls.rows["train"]
        cls.add = next(json.loads(row["messages"][-1]["content"]) for row in cls.train
                       if json.loads(row["messages"][-1]["content"])["intent"] == "calendar_add")

    def test_reassembly_and_history_are_exact(self):
        self.assertEqual(self.manifest["protected_files_checked"], verify_preserved_sources())
        self.assertGreaterEqual(self.manifest["protected_files_checked"], 663)
        self.assertEqual(6486, self.manifest["total_rows"])
        self.assertEqual(818, self.manifest["excluded_rows"])
        self.assertEqual(5601, self.manifest["changed_user_or_assistant_messages"])
        self.assertEqual(5681, self.manifest["reply_design"]["changed_replies"])
        retention = read(DATA / "retention_index.json")
        for name, rows in self.rows.items():
            original = [json.loads(line) for line in (ROOT / "docs/calendar_sft_v12_66" / (name + ".jsonl")).read_text(encoding="utf-8").splitlines()]
            for row, position in zip(rows, retention[name]["positions"]):
                source = original[position["source_line"] - 1]
                self.assertEqual(source["messages"][:-1], row["messages"][:-1])
                before = json.loads(source["messages"][-1]["content"])
                after = json.loads(row["messages"][-1]["content"])
                if before["intent"] == "calendar_add":
                    self.assertNotEqual(before.pop("reply"), after.pop("reply"))
                    self.assertEqual(before, after)
                else:
                    self.assertEqual(source["messages"], row["messages"])
                self.assertEqual(source["category"], row["category"])
        old_dev = read(ROOT / "docs/calendar_sft_v12_66/generation_dev.json")
        for row, position in zip(read(DATA / "generation_dev.json"), retention["generation_dev"]["positions"]):
            before = old_dev[position["source_line"] - 1]["messages"]
            self.assertEqual(before[:-1], row["messages"][:-1])
            old, new = json.loads(before[-1]["content"]), json.loads(row["messages"][-1]["content"])
            if old["intent"] == "calendar_add":
                self.assertNotEqual(old.pop("reply"), new.pop("reply"))
            else:
                self.assertEqual(before, row["messages"])
            self.assertEqual(old, new)

    def test_every_target_is_valid_and_sum_is_retained(self):
        actions = set()
        for rows in self.rows.values():
            actions.update(json.loads(row["messages"][-1]["content"])["intent"] for row in rows)
        self.assertEqual(set(INTENTS), actions)
        self.assertEqual(dict(calendar_add=4749, calendar_search=354, calendar_sum=88, chat=202),
                         self.manifest["artifacts"]["train"]["intents"])
        self.assertEqual(32, self.manifest["artifacts"]["validation"]["intents"]["calendar_sum"])
        self.assertEqual(dict(calendar_add=522, calendar_search=70, calendar_sum=32, chat=61),
                         self.manifest["artifacts"]["validation"]["intents"])

    def test_launch_freeze_includes_nested_handwritten_literals_and_baseline(self):
        frozen = set(frozen_paths())
        manual = ROOT / "docs/calendar_v12_67_manual"
        files = {path for path in manual.rglob("*") if path.is_file()}
        self.assertTrue(files.issubset(frozen))
        self.assertIn(manual / "reply_literals/010.txt", frozen)
        self.assertIn(manual / "reply_baseline_20261010/snapshot.json", frozen)

    def test_reviewed_chat_claims_are_excluded_without_keyword_deletion(self):
        registry = read(ROOT / "docs/calendar_v12_67_manual/exclusions.json")
        counts = {name: sum(item["reason"] == "scope_instruction" for item in registry[name]["exclude"])
                  for name in ("train", "validation")}
        self.assertEqual(dict(train=20, validation=4), counts)
        retained_source_lines = {item["source_line"] for item in read(DATA / "retention_index.json")["train"]["positions"]}
        self.assertTrue({1092, 1362, 1445}.isdisjoint(retained_source_lines))
        # A word inside an event name does not turn creation into another action.
        self.assertTrue(any(json.loads(row["messages"][-1]["content"])["params"].get("title") == "Обновление списка"
                            for row in self.train))

    def test_all_clock_grid_positions_still_identify_the_same_time(self):
        grid = read(DATA / "clock_coverage_index.json")
        self.assertEqual(1440, len(grid))
        for clock, cell in grid.items():
            row = self.train[cell["train_line"] - 1]
            self.assertEqual(cell["case_id"], row["case_id"])
            params = json.loads(row["messages"][-1]["content"])["params"]
            self.assertEqual(clock, params.get("starts_at", params.get("time"))[-5:])

    def test_development_is_independent_of_training_and_rejects_holdout(self):
        rows = read(DATA / "generation_dev.json")
        self.assertEqual(106, len(rows))
        pairs = {(row["messages"][0]["content"], row["messages"][1]["content"]) for row in self.train}
        self.assertTrue(all((row["messages"][0]["content"], row["messages"][1]["content"]) not in pairs for row in rows))
        cases = development_cases(rows)
        self.assertEqual(212, len(cases))
        results = [grade(case, raw(case["expected"])) for case in cases]
        self.assertEqual([1.0, 1.0, 1.0, 1.0], selection_score(cases, results))
        cases[0]["split"] = "holdout"
        with self.assertRaises(ValueError):
            selection_score(cases, results)

    def test_unknown_actions_and_invalid_json_never_enter_the_contract(self):
        payload = deepcopy(self.add)
        payload["intent"] = "calendar_future_action"
        with self.assertRaises(DatasetContractError):
            parse_and_validate_assistant_response(raw(payload))
        payload["intent"] = []
        with self.assertRaises(DatasetContractError):
            parse_and_validate_assistant_response(raw(payload))
        for text in ('{"intent":"chat","intent":"chat","reply":"x","params":{}}',
                     '{"intent":"chat","reply":"x","params":{"value":NaN}}',
                     '{"intent":"chat","reply":"x","params":{}} trailing'):
            with self.subTest(text=text), self.assertRaises(DatasetContractError):
                parse_and_validate_assistant_response(text)

    def test_creation_type_and_boundary_guards(self):
        for key, value in (("value", True), ("value", 1.5), ("value", 2 ** 63),
                           ("duration_min", 0), ("duration_min", 2 ** 31),
                           ("title", None), ("unexpected", "x")):
            payload = deepcopy(self.add)
            payload["params"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(DatasetContractError):
                parse_and_validate_assistant_response(raw(payload))

    def test_range_and_app_computed_total_guards(self):
        payload = next(json.loads(row["messages"][-1]["content"]) for row in self.train
                       if json.loads(row["messages"][-1]["content"])["intent"] == "calendar_sum")
        incorrect = deepcopy(payload)
        incorrect["reply"] = "100"
        with self.assertRaises(DatasetContractError):
            parse_and_validate_assistant_response(raw(incorrect))
        incorrect = deepcopy(payload)
        incorrect["params"].pop("range_end")
        with self.assertRaises(DatasetContractError):
            parse_and_validate_assistant_response(raw(incorrect))
        incorrect = deepcopy(payload)
        incorrect["params"]["range_end"] = incorrect["params"]["range_start"]
        with self.assertRaises(DatasetContractError):
            parse_and_validate_assistant_response(raw(incorrect))

    def test_title_reply_replaces_clock_wording_and_rejects_extra_schedule(self):
        grid = read(DATA / "clock_coverage_index.json")
        row = self.train[grid["12:15"]["train_line"] - 1]
        case = case_from_row(row, "clock_regression")
        self.assertTrue(grade(case, raw(case["expected"]))["passed"])
        payload = deepcopy(case["expected"])
        self.assertTrue(payload["reply"].startswith("Новое событие: "))
        self.assertIsNone(grade(case, raw(payload))["reply_clock_correct"])
        for suffix in (" Завтра.", " В четверть первого.", " 12:15.", " На час.", " Значение пять."):
            incorrect = deepcopy(payload)
            incorrect["reply"] += suffix
            result = grade(case, raw(incorrect))
            self.assertTrue(result["params_exact"])
            self.assertFalse(result["reply_style_correct"])
            self.assertFalse(result["reply_params_consistent"])
            self.assertFalse(result["passed"])
        payload["reply"] = "Новое событие: Другое название."
        self.assertFalse(grade(case, raw(payload))["reply_title_correct"])

    def test_unnamed_creation_does_not_invent_a_title(self):
        row = next(row for row in self.train if json.loads(row["messages"][-1]["content"])["intent"] == "calendar_add"
                   and "title" not in json.loads(row["messages"][-1]["content"])["params"])
        case = case_from_row(row, "unnamed_regression")
        payload = deepcopy(case["expected"])
        self.assertEqual("Создаём событие без названия.", payload["reply"])
        self.assertTrue(grade(case, raw(payload))["passed"])
        payload["reply"] = "Новое событие: Событие."
        self.assertFalse(grade(case, raw(payload))["passed"])

    def test_literal_title_can_contain_date_time_or_punctuation(self):
        row = next(row for row in self.train if json.loads(row["messages"][-1]["content"])["params"].get("title") == "Пять часов")
        case = case_from_row(row, "literal_title_regression")
        self.assertTrue(grade(case, raw(case["expected"]))["passed"])
        # Novel in-memory grading probes, never authored or written dataset rows.
        for title, reply in (("Планы на 2027-05-06 в 12:15", "Новое событие: Планы на 2027-05-06 в 12:15."),
                             ("Разбор «Плана» — часть 2", "Новое событие: Разбор «Плана» — часть 2.")):
            probe = deepcopy(case)
            probe["expected"]["params"]["title"] = title
            probe["expected"]["reply"] = reply
            self.assertTrue(grade(probe, raw(probe["expected"]))["passed"])

    def test_multiple_events_keep_the_required_warning_without_time(self):
        row = next(row for row in self.train if "не обработан" in json.loads(row["messages"][-1]["content"])["reply"])
        case = case_from_row(row, "multiple_event_regression")
        payload = deepcopy(case["expected"])
        self.assertTrue(grade(case, raw(payload))["passed"])
        payload["reply"] = payload["reply"].split(". ", 1)[0] + "."
        result = grade(case, raw(payload))
        self.assertTrue(result["params_exact"])
        self.assertFalse(result["reply_warning_correct"])
        self.assertFalse(result["passed"])

    def test_annotated_relative_arithmetic_is_checked_independently(self):
        cases = development_cases(read(DATA / "generation_dev.json"))
        case = next(case for case in cases if "offset_minutes" in case)
        self.assertTrue(grade(case, raw(case["expected"]))["offset_arithmetic_correct"])
        payload = deepcopy(case["expected"])
        payload["params"]["starts_at"] = "2026-01-01T00:00"
        self.assertFalse(grade(case, raw(payload))["offset_arithmetic_correct"])

    def test_notes_are_exact_and_title_case_tolerance_remains(self):
        row = next(row for row in self.train if json.loads(row["messages"][-1]["content"])["params"].get("title"))
        case = case_from_row(row, "notes_regression")
        # This is an in-memory grader probe, never an authored dataset row.
        case["expected"]["params"]["notes"] = "x"
        payload = deepcopy(case["expected"])
        payload["params"]["title"] = payload["params"]["title"].swapcase()
        self.assertTrue(grade(case, raw(payload))["params_case_insensitive"])
        payload["params"]["notes"] += "!"
        self.assertFalse(grade(case, raw(payload))["params_case_insensitive"])

    def test_android_prompt_uses_actual_provider_contract_without_rewriting_literals(self):
        row = self.train[0]
        original = deepcopy(row)
        result = variant_row(row, "android_runtime")
        self.assertEqual(original, row)
        self.assertEqual(original["messages"][1:], result["messages"][1:])
        self.assertTrue(result["messages"][0]["content"].startswith("cегодня "))
        for action in INTENTS:
            self.assertIn(action + ":", result["messages"][0]["content"])
        provider = PROVIDER.read_text(encoding="utf-8")
        self.assertIn('ответ JSON\\n$CALENDAR_CONTRACT', provider)
        with self.assertRaises(ValueError):
            system_text(row["messages"][0]["content"], "unknown")

    def test_cached_answers_are_bound_to_cases_transport_and_grader(self):
        pack = prepared_inputs(["regression_holdout"])
        cached = dict(binding=deepcopy(pack["binding"]), model_sha256="a" * 64,
                      answers=[dict(case_id=case["id"], case_sha256=case_digest(case), raw_output=raw(case["expected"]))
                               for case in pack["cases"]])
        result = score_cached(pack, cached)
        self.assertEqual([], result["report"]["failed_ids"])
        cached["answers"].reverse()
        with self.assertRaises(ValueError):
            score_cached(pack, cached)
        cached["binding"]["grader_version"] = "other"
        with self.assertRaises(ValueError):
            score_cached(pack, cached)

    def test_confusion_matrix_and_selection_schedule_cover_four_actions(self):
        cases = development_cases(read(DATA / "generation_dev.json"))
        report = summary([grade(case, raw(case["expected"])) for case in cases])
        self.assertEqual(set(INTENTS), set(report["intent_confusion"]["matrix"]))
        self.assertTrue(all(report["intent_confusion"]["support"].values()))
        steps = selection_steps(len(self.train))
        self.assertEqual(CONFIG["epochs"] * CONFIG["development_evaluations_per_epoch"], len(steps))
        self.assertEqual(1014, steps[-1])
        self.assertEqual(VERSION, CONFIG["grader_version"])


if __name__ == "__main__":
    unittest.main()
