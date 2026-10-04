"""Behavioral counterexamples for V12.65 semantic scoring and training wiring."""
from copy import deepcopy
from itertools import permutations
import json
from pathlib import Path
import tempfile
import unittest

from prepare_v12_65 import SOURCE, OUTPUT, literals, repairs, correct, audit_rows, assemble
from dataset_provenance import verify_dataset_provenance
from v12_65_evaluation import case_from_row, grade, selection_score, VERSION
from v12_65_training import development_cases, CONFIG, selection_steps, variant_row
from evaluate_v12_65 import prepared_inputs, score_cached


def response(reply="Встреча завтра в четверть первого.", time="12:15"):
    return dict(intent="calendar_add", reply=reply,
                params=dict(title="Встреча", starts_at="2026-10-05T" + time, duration_min=30))


def case(expected=None):
    return dict(id="regression", suite="known_failure", system="cегодня 2026-10-04 03:52 день недели воскресенье ответ JSON",
                user="Создай встречу завтра в двенадцать пятнадцать на тридцать минут.",
                expected=expected or response(), clocks=[], clock_phrases=[])


def scored(payload, expected=None):
    return grade(case(expected), json.dumps(payload, ensure_ascii=False))


class SpokenTimeTest(unittest.TestCase):
    def test_observed_hour_number_substituted_for_minutes(self):
        result = scored(response("Встреча завтра в двенадцать минут первого."))
        self.assertTrue(result["params_exact"])
        self.assertTrue(result["reply_style_correct"])
        self.assertFalse(result["reply_clock_correct"])
        self.assertFalse(result["reply_params_consistent"])
        self.assertFalse(result["passed"])

    def test_twelve_minutes_are_valid_for_twelve_twelve(self):
        p = response("Встреча завтра в двенадцать минут первого.", "12:12")
        self.assertTrue(scored(p, p)["passed"])

    def test_correct_quarter_and_adjacent_minutes(self):
        for time, phrase in (("12:14", "в четырнадцать минут первого"),
                             ("12:15", "в четверть первого"), ("12:16", "в шестнадцать минут первого"),
                             ("11:15", "в четверть двенадцатого"), ("00:15", "в четверть первого"),
                             ("23:15", "в четверть двенадцатого")):
            with self.subTest(time=time):
                p = response("Встреча завтра " + phrase + ".", time)
                self.assertTrue(scored(p, p)["passed"])

    def test_empty_legacy_clocks_cannot_disable_check(self):
        result = scored(response("Встреча завтра в четверть одиннадцатого."))
        self.assertFalse(result["passed"])

    def test_reply_matches_expected_but_not_actual_params(self):
        result = scored(response(time="12:12"))
        self.assertTrue(result["reply_clock_correct"])
        self.assertFalse(result["reply_params_consistent"])
        self.assertFalse(result["passed"])

    def test_coherently_wrong_params_and_reply_still_fail(self):
        result = scored(response("Встреча завтра в двенадцать минут первого.", "12:12"))
        self.assertTrue(result["reply_params_consistent"])
        self.assertFalse(result["params_exact"])
        self.assertFalse(result["passed"])

    def test_correct_meaning_wrong_speech_style(self):
        result = scored(response("Встреча завтра в двенадцать часов пятнадцать минут."))
        self.assertTrue(result["reply_clock_correct"])
        self.assertFalse(result["reply_style_correct"])
        self.assertFalse(result["passed"])

    def test_duration_does_not_replace_clock(self):
        self.assertTrue(scored(response("Встреча завтра в четверть первого на тридцать минут."))["passed"])
        self.assertFalse(scored(response("Встреча завтра в половине первого на тридцать минут."))["passed"])

    def test_missing_title_still_requires_known_clock(self):
        expected = response("Название события не указано. Время: в четверть первого.")
        del expected["params"]["title"]
        bad = deepcopy(expected); bad["reply"] = "Название события не указано."
        self.assertTrue(scored(expected, expected)["passed"])
        self.assertFalse(scored(bad, expected)["passed"])

    def test_no_start_time_must_not_get_spoken_start(self):
        expected = dict(intent="calendar_add", reply="Встреча на двенадцать минут.",
                        params=dict(title="Встреча", date="2026-10-05", duration_min=12))
        self.assertTrue(scored(expected, expected)["passed"])
        bad = deepcopy(expected); bad["reply"] = "Встреча в четверть первого на двенадцать минут."
        self.assertFalse(scored(bad, expected)["passed"])

    def test_exact_update_title_with_time_is_opaque(self):
        expected = dict(intent="calendar_update", reply="Событие изменено: Урок в четверть третьего в четверть первого.",
                        params=dict(target=dict(query="Урок в четверть третьего"), changes=dict(time="12:15")))
        self.assertTrue(scored(expected, expected)["passed"])
        bad = deepcopy(expected); bad["reply"] = "Событие изменено: Урок в четверть третьего в двенадцать минут первого."
        self.assertFalse(scored(bad, expected)["passed"])

    def test_interval_end_is_checked(self):
        expected = response("Встреча завтра в четверть первого, окончание в половине первого.")
        expected["params"].pop("duration_min")
        expected["params"]["ends_at"] = "2026-10-05T12:30"
        self.assertTrue(scored(expected, expected)["passed"])
        bad = deepcopy(expected); bad["reply"] = "Встреча завтра в четверть первого, окончание в половине второго."
        self.assertFalse(scored(bad, expected)["passed"])

    def test_correct_derived_end_with_duration(self):
        self.assertTrue(scored(response("Встреча завтра в четверть первого, окончание без четверти час."))["passed"])

    def test_broken_json_and_contract_never_pass(self):
        for raw in ("not JSON", "[]", '{"intent":"calendar_add","reply":12,"params":{}}'):
            with self.subTest(raw=raw):
                self.assertFalse(grade(case(), raw)["passed"])


class DataAndTrainingTest(unittest.TestCase):
    def test_v12_65_reassembly_and_provenance(self):
        report = assemble(check_only=True)
        self.assertEqual(report["version"], "v12.65")
        provenance = verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
        self.assertEqual(provenance["status"], "VERIFIED")

    def test_literal_reference_targets_pass(self):
        for group in ("clock_train", "offset_train", "update_train", "validation", "holdout"):
            for row in literals(group):
                with self.subTest(id=row["case_id"]):
                    self.assertTrue(grade(case_from_row(row), row["messages"][-1]["content"])["passed"])

    def test_offset_does_not_allow_matching_but_wrong_reply_and_params(self):
        row = literals("offset_train")[0]
        c = case_from_row(row)
        bad = json.loads(row["messages"][-1]["content"])
        bad["params"]["starts_at"] = "2026-10-04T12:16"
        bad["reply"] = "Проверить сушилку в шестнадцать минут первого."
        result = grade(c, json.dumps(bad, ensure_ascii=False))
        self.assertTrue(result["reply_params_consistent"])
        self.assertFalse(result["offset_arithmetic_correct"])
        self.assertFalse(result["passed"])

    def test_six_orders_keep_offset_and_duration_roles(self):
        rows = literals("offset_train")[4:10]
        self.assertEqual({r["audit"]["field_order"] for r in rows},
                         {"-".join(p) for p in permutations(("title", "offset", "duration"))})
        params = [json.loads(r["messages"][-1]["content"])["params"] for r in rows]
        self.assertTrue(all(p == params[0] for p in params))
        self.assertEqual(params[0]["duration_min"], 12)
        self.assertEqual(params[0]["starts_at"], "2026-10-04T12:15")

    def test_manual_repairs_preserve_all_command_fields(self):
        count = 0
        for path in SOURCE.glob("*.jsonl"):
            for line in path.read_bytes().splitlines():
                row = json.loads(line); new, change = correct(row, repairs())
                if not change:
                    self.assertEqual(row, new)
                    continue
                count += 1
                a, b = (json.loads(x["messages"][-1]["content"]) for x in (row, new))
                a.pop("reply"); b.pop("reply")
                self.assertEqual(a, b)
                self.assertEqual(row["messages"][:-1], new["messages"][:-1])
        self.assertEqual(count, 105)

    def test_all_reference_splits_have_consistent_known_clocks(self):
        for path in OUTPUT.glob("*.jsonl"):
            rows = [json.loads(line) for line in path.read_bytes().splitlines()]
            self.assertEqual(audit_rows(rows)["failures"], [], path.name)

    def test_development_uses_semantic_grader_and_offset_metadata(self):
        rows = json.loads((OUTPUT / "generation_dev.json").read_text(encoding="utf-8"))
        cases = development_cases(rows)
        self.assertEqual(len(cases), 192)
        self.assertTrue(any("offset_minutes" in c for c in cases))
        holdout_ids = {r["case_id"] for r in literals("holdout")}
        self.assertFalse({c["id"] for c in cases} & holdout_ids)
        self.assertTrue(all(grade(c, json.dumps(c["expected"], ensure_ascii=False))["passed"] for c in cases))
        self.assertEqual(CONFIG["grader_version"], VERSION)

    def test_holdout_cannot_be_used_for_selection(self):
        with self.assertRaisesRegex(ValueError, "holdout"):
            development_cases([literals("holdout")[0]])

    def test_bad_spoken_time_lowers_selection_even_with_exact_params(self):
        c = case(); good = scored(response()); bad = scored(response("Встреча завтра в двенадцать минут первого."))
        self.assertGreater(selection_score([c], [good]), selection_score([c], [bad]))

    def test_selection_reaches_last_step_for_changed_dataset_size(self):
        self.assertEqual(selection_steps(5949), [186, 372, 558, 744, 930, 1116])
        self.assertIn(1119, selection_steps(5960))

    def test_runtime_transport_preserves_literal_request_and_answer(self):
        row = literals("clock_train")[26]
        new = variant_row(row, "android_runtime")
        self.assertEqual(row["messages"][1:], new["messages"][1:])
        self.assertEqual(new["messages"][0]["content"], "cегодня 2026-10-04 03:52 день недели воскресенье ответ JSON")

    def test_holdout_has_distinct_runtime_and_standard_prompt_hashes(self):
        inputs = prepared_inputs()
        self.assertEqual(len(inputs["cases"]), 48)
        self.assertEqual(len({c["prompt_sha256"] for c in inputs["cases"]}), 48)

    def test_cached_response_with_wrong_prompt_binding_is_rejected(self):
        inputs = prepared_inputs(); inputs["cases"] = inputs["cases"][:1]; c = inputs["cases"][0]
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "inputs.json").write_text(json.dumps(inputs), encoding="utf-8")
            (root / (c["id"] + ".json")).write_text(json.dumps(dict(id=c["id"], prompt_sha256="bad",
                model_sha256="a" * 64, output=json.dumps(c["expected"]))), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "binding"):
                score_cached(root / "inputs.json", root)


if __name__ == "__main__":
    unittest.main()
