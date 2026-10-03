"""Counterexamples for offsets, field roles, and previously accepted bad replies."""
from copy import deepcopy
import json
import unittest
from pathlib import Path
import tempfile

from prepare_v12_64 import amount, elapsed_minutes, literals, audit_literal
from v12_64_evaluation import case_from_row, grade


class OffsetAuditTest(unittest.TestCase):
    def test_word_digits_fractions_and_reversed_units(self):
        cases = {"через минуту": 1, "через одну минуту": 1, "через 61 минуту": 61,
                 "через двадцать одну минуту": 21, "через сто двадцать минут": 120,
                 "через час и пять минут": 65, "через 2 часа 7 минут": 127,
                 "через пять минут и два часа": 125, "минут через пять": 5,
                 "часа через два": 120, "через полчаса": 30, "через половину часа": 30,
                 "через четверть часа": 15, "через полтора часа": 90,
                 "через два с половиной часа": 150}
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(expected, elapsed_minutes(text))

    def test_unknown_incomplete_and_duplicate_units_fail_closed(self):
        for text in ("через ноль минут", "минут через ноль", "на минуту", "через минуту чай",
                     "через пять", "через два часа три часа", "через полторы минуты"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                elapsed_minutes(text)
        with self.assertRaises(ValueError):
            amount("пять двадцать")

    def test_mutating_an_offset_label_is_rejected(self):
        row = deepcopy(literals("minutes_train")[0])
        answer = json.loads(row["messages"][-1]["content"])
        answer["params"]["starts_at"] = "2026-10-03T11:16"
        row["messages"][-1]["content"] = json.dumps(answer, ensure_ascii=False)
        with self.assertRaisesRegex(ValueError, "incorrect handwritten target"):
            audit_literal(row, "через минуту")

    def test_offset_cannot_become_duration_or_value(self):
        row = deepcopy(literals("order_train")[0])
        for field, bad in (("duration_min", 2), ("value", 2)):
            with self.subTest(field=field):
                changed = deepcopy(row)
                answer = json.loads(changed["messages"][-1]["content"])
                answer["params"][field] = bad
                changed["messages"][-1]["content"] = json.dumps(answer, ensure_ascii=False)
                with self.assertRaisesRegex(ValueError, "duration/value"):
                    audit_literal(changed, "через две минуты")

    def test_month_year_leap_and_common_february_boundaries(self):
        rows = {r["case_id"]: json.loads(r["messages"][-1]["content"])["params"]
                for r in literals("minutes_train") + literals("contrast_train")}
        for key, target in (("M04", "2027-01-01T00:03"), ("M08", "2027-03-01T00:07"),
                            ("M31", "2028-02-29T00:16"), ("R05", "2028-03-01T00:00"),
                            ("R03", "2027-02-01T01:40")):
            self.assertEqual(target, rows["V1264" + key]["starts_at"])

    def test_all_literals_have_independently_validated_targets(self):
        from prepare_v12_64 import MANUAL
        rows = [r for p in MANUAL.glob("*.txt") for r in literals(p.stem)]
        self.assertEqual(len(rows), len({r["case_id"] for r in rows}))
        self.assertGreaterEqual(len(rows), 309)


class ReplyGradingTest(unittest.TestCase):
    def setUp(self):
        self.case = {"id": "legacy", "suite": "legacy", "system": "test", "user": "test",
                     "clocks": [], "clock_phrases": [], "expected": {"intent": "calendar_add",
                     "params": {"title": "Будем пить чай", "starts_at": "2026-10-03T11:15"}}}

    def score(self, reply, params=None):
        answer = {"intent": "calendar_add", "reply": reply,
                  "params": params or self.case["expected"]["params"]}
        return grade(self.case, json.dumps(answer, ensure_ascii=False))

    def test_empty_legacy_clock_list_does_not_skip_reply_check(self):
        wrong = self.score("Будем пить чай в четверть одиннадцатого.")
        self.assertTrue(wrong["params_exact"])
        self.assertFalse(wrong["reply_clock_correct"])
        self.assertFalse(wrong["passed"])
        self.assertTrue(self.score("Будем пить чай в четверть двенадцатого.")["passed"])

    def test_full_spoken_time_is_semantically_correct_but_style_is_separate(self):
        result = self.score("Будем пить чай в одиннадцать часов пятнадцать минут.")
        self.assertTrue(result["reply_clock_correct"])
        self.assertFalse(result["reply_style_correct"])
        self.assertFalse(result["passed"])

    def test_canonical_wrong_hour_is_a_meaning_error(self):
        result = self.score("Будем пить чай в четверть десятого.")
        self.assertFalse(result["reply_clock_correct"])
        self.assertTrue(result["reply_style_correct"])

    def test_no_rounding_31_37_49(self):
        for minute, reply in ((31, "в половине первого"), (37, "в половине первого"), (49, "в четверть первого")):
            self.case["expected"]["params"]["starts_at"] = f"2026-10-03T12:{minute}"
            self.assertFalse(self.score("Будем пить чай " + reply + ".")["reply_clock_correct"])

    def test_explicit_24_hour_clock_cannot_hide_behind_modulo(self):
        self.case["expected"]["params"]["starts_at"] = "2026-10-03T14:00"
        self.assertFalse(self.score("Будем пить чай в два часа утра.")["reply_clock_correct"])
        self.assertTrue(self.score("Будем пить чай в четырнадцать часов.")["passed"])

    def test_unknown_time_and_extra_clock_are_rejected(self):
        self.assertFalse(self.score("Будем пить чай в четверть двенадцатого, в половине двенадцатого.")["passed"])
        self.case["expected"]["params"] = {"title": "Будем пить чай", "date": "2026-10-03"}
        self.assertFalse(self.score("Будем пить чай в четверть двенадцатого.")["passed"])
        self.assertTrue(self.score("Будем пить чай сегодня.")["passed"])

    def test_title_clock_is_opaque(self):
        self.case["expected"]["params"]["title"] = "В два часа"
        self.assertTrue(self.score("В два часа в четверть двенадцатого.")["passed"])

    def test_wrong_date_fails_even_with_correct_reply(self):
        params = {**self.case["expected"]["params"], "starts_at": "2026-10-04T11:15"}
        result = self.score("Будем пить чай в четверть двенадцатого.", params)
        self.assertTrue(result["reply_clock_correct"])
        self.assertFalse(result["passed"])

    def test_offset_arithmetic_is_a_separate_metric(self):
        row = literals("minutes_train")[0]
        case = case_from_row(row)
        self.assertTrue(grade(case, row["messages"][-1]["content"])["offset_arithmetic_correct"])
        a = json.loads(row["messages"][-1]["content"])
        a["params"]["starts_at"] = "2026-10-03T11:16"
        self.assertFalse(grade(case, json.dumps(a, ensure_ascii=False))["offset_arithmetic_correct"])

    def test_interval_checks_both_endpoints_and_computed_end(self):
        self.case["expected"]["params"].update(starts_at="2026-10-03T23:59", ends_at="2026-10-04T00:15")
        good = "Будем пить чай, начало без одной минуты двенадцать, окончание в четверть первого."
        self.assertTrue(self.score(good)["passed"])
        self.assertFalse(self.score(good.replace("четверть первого", "четверть второго"))["passed"])
        del self.case["expected"]["params"]["ends_at"]
        self.case["expected"]["params"]["duration_min"] = 16
        self.assertTrue(self.score(good)["passed"])

    def test_update_time_is_also_checked(self):
        self.case["expected"] = {"intent": "calendar_update", "params": {"target": {"query": "Осмотр"}, "changes": {"time": "16:00"}}}
        raw = {**self.case["expected"], "reply": "Событие изменено: Осмотр в четырнадцать часов."}
        self.assertFalse(grade(self.case, json.dumps(raw, ensure_ascii=False))["passed"])

    def test_corrupt_json_and_non_string_reply_do_not_crash(self):
        for raw in ("not json", "null", "[]", '{"intent":"calendar_add","reply":[],"params":{}}'):
            self.assertFalse(grade(self.case, raw)["passed"])


class EvaluationBindingTest(unittest.TestCase):
    def test_android_prompts_use_real_system_spelling_and_no_gold_reply(self):
        from evaluate_v12_64 import prepared_inputs
        cases = prepared_inputs()["cases"]
        self.assertEqual(96, len(cases))
        android = cases[1]
        self.assertTrue(android["system"].startswith("cегодня 2029-12-31 23:58 день недели"))
        self.assertIn("ответ JSON\n<|im_end|>", android["prompt"])
        self.assertTrue(android["prompt"].endswith("<|im_start|>assistant\n"))
        self.assertNotIn(android["expected"]["reply"], android["prompt"])

    def test_cached_wrong_prompt_binding_is_rejected(self):
        from evaluate_v12_64 import score_cached
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            case = {"id": "bound", "prompt_sha256": "correct"}
            inputs = folder / "inputs.json"
            inputs.write_text(json.dumps({"cases": [case]}), encoding="utf-8")
            response = folder / "bound.json"
            response.write_text(json.dumps({"id": "bound", "prompt_sha256": "wrong"}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "prompt binding"):
                score_cached(inputs, folder)

    def test_cached_responses_from_different_models_are_rejected(self):
        from evaluate_v12_64 import score_cached
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            rows = literals("holdout")[:2]
            cases = []
            for i, row in enumerate(rows):
                case = case_from_row(row)
                case["prompt_sha256"] = "bound"
                cases.append(case)
                (folder / (case["id"] + ".json")).write_text(json.dumps({
                    "id": case["id"], "prompt_sha256": "bound", "model_sha256": str(i),
                    "output": row["messages"][-1]["content"]}), encoding="utf-8")
            inputs = folder / "inputs.json"
            inputs.write_text(json.dumps({"cases": cases}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "one model"):
                score_cached(inputs, folder)

    def test_frozen_dataset_reassembly_and_provenance(self):
        from prepare_v12_64 import assemble, OUTPUT
        from dataset_provenance import verify_dataset_provenance
        report = assemble(check_only=True)
        self.assertEqual({"train": 229, "validation": 32, "holdout": 48}, report["additions"])
        self.assertEqual("VERIFIED", verify_dataset_provenance(
            OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")["status"])

    def test_all_new_reference_replies_pass_the_current_grader(self):
        from prepare_v12_64 import MANUAL
        for path in MANUAL.glob("*.txt"):
            for row in literals(path.stem):
                with self.subTest(case=row["case_id"]):
                    result = grade(case_from_row(row), row["messages"][-1]["content"])
                    self.assertTrue(result["passed"], result)


if __name__ == "__main__":
    unittest.main()
