"""V12.66 data preservation, intent separation and temporal regressions."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from dataset_provenance import verify_dataset_provenance
from prepare_v12_66 import (SOURCE, OUTPUT, MANUAL, REMINDER, LOOKUP, answer, read,
                           assemble, literals, routing_audit, repair_inputs, inherited_audit)
from prepare_v12_65 import audit_rows
from v12_66_evaluation import VERSION, INTENTS, case_from_row, grade, summary, selection_score
from v12_66_training import CONFIG, development_cases, selection_steps, variant_row
from evaluate_v12_66 import prepared_inputs, score_cached


def rows(path):
    return [json.loads(line) for line in path.read_bytes().splitlines()]


def literal(key, group="add_train"):
    return next(r for r in literals(group) if r["case_id"] == "V1266" + key)


def scored(row, payload=None):
    return grade(case_from_row(row), json.dumps(payload or answer(row), ensure_ascii=False))


class PreservationTest(unittest.TestCase):
    def test_frozen_sources_reassembly_and_provenance(self):
        report = assemble(check_only=True)
        self.assertEqual(report["version"], "v12.66")
        self.assertEqual(report["additions"], dict(train=60, validation=20, holdout=28))
        self.assertEqual(sum(x["rows"] for x in report["artifacts"].values()), 7304)
        self.assertEqual(verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl",
                                                 OUTPUT / "validation.jsonl")["status"], "VERIFIED")

    def test_all_inherited_answers_preserved_and_only_four_inputs_reworded(self):
        source = {p.stem: rows(p) for p in SOURCE.glob("*.jsonl")}
        repaired, changes = repair_inputs(source)
        self.assertEqual(inherited_audit(source, repaired, changes), dict(
            unchanged_rows=7192, reworded_inputs=4, assistant_messages_preserved=7196,
            inherited_chat_rows_preserved=291, inherited_lookup_rows_preserved=171, rows_deleted=0))
        modified = {(c["split"], c["line"]) for c in changes}
        for split in source:
            original_bytes = (SOURCE / (split + ".jsonl")).read_bytes().splitlines()
            current_bytes = (OUTPUT / (split + ".jsonl")).read_bytes().splitlines()
            for n, raw in enumerate(original_bytes, 1):
                if (split, n) not in modified:
                    self.assertEqual(current_bytes[n - 1], raw, f"{split}:{n}")

    def test_date_time_questions_retain_intent_context_and_answer(self):
        for change in read(MANUAL / "input_repairs.json"):
            before = rows(SOURCE / (change["split"] + ".jsonl"))[change["line"] - 1]
            after = rows(OUTPUT / (change["split"] + ".jsonl"))[change["line"] - 1]
            self.assertEqual(answer(after)["intent"], "chat")
            self.assertEqual(before["messages"][0], after["messages"][0])
            self.assertEqual(before["messages"][-1], after["messages"][-1])
            self.assertEqual(after["messages"][1]["content"], change["after"])

    def test_reminder_verbs_are_reserved_for_add_across_all_splits(self):
        for path in OUTPUT.glob("*.jsonl"):
            audit = routing_audit(rows(path))
            self.assertEqual(audit["violations"], [], path.name)
            self.assertLessEqual(set(audit["reminder_verbs"]), {"calendar_add"})
        rogue = deepcopy(literal("V17", "validation"))
        rogue["messages"][1]["content"] = "Напомни текущее время."
        self.assertEqual(len(routing_audit([rogue])["violations"]), 1)

    def test_noun_reminder_in_delete_is_preserved(self):
        kept = [r for r in rows(OUTPUT / "train.jsonl") if answer(r)["intent"] == "calendar_delete"
                and "напоминание" in r["messages"][1]["content"].lower()]
        self.assertEqual(len(kept), 7)
        self.assertTrue(all(not REMINDER.search(r["messages"][1]["content"]) for r in kept))

    def test_find_search_and_compound_updates_remain(self):
        old = [r for r in rows(SOURCE / "train.jsonl") if LOOKUP.search(r["messages"][1]["content"])]
        new = rows(OUTPUT / "train.jsonl")
        self.assertTrue(all(r in new for r in old))
        self.assertEqual({answer(r)["intent"] for r in old}, {"calendar_search", "calendar_update", "chat"})

    def test_grid_and_earlier_time_targets_are_preserved(self):
        self.assertEqual((SOURCE / "clock_coverage_index.json").read_bytes(),
                         (OUTPUT / "clock_coverage_index.json").read_bytes())
        for path in OUTPUT.glob("*.jsonl"):
            self.assertEqual(audit_rows(rows(path))["failures"], [], path.name)


class RoutingTest(unittest.TestCase):
    def test_every_new_literal_passes_contract_and_clock_grader(self):
        for group in ("add_train", "search_train", "validation", "holdout"):
            for row in literals(group):
                with self.subTest(id=row["case_id"]):
                    self.assertTrue(scored(row)["passed"])

    def test_polite_question_and_missing_fields_still_create(self):
        for key in ("A07", "A22", "A23", "A24", "A26"):
            row = literal(key)
            self.assertEqual(answer(row)["intent"], "calendar_add")
            self.assertTrue(scored(row)["passed"])
        self.assertEqual(answer(literal("A24"))["params"], {"date": "2026-10-04"})
        self.assertNotIn("title", answer(literal("A23"))["params"])
        self.assertNotIn("starts_at", answer(literal("A18"))["params"])

    def test_wrong_add_search_and_chat_intents_fail_and_enter_matrix(self):
        records = []
        for row, substitute in ((literal("A01"), literal("S01", "search_train")),
                                (literal("S01", "search_train"), literal("A01")),
                                (literal("V17", "validation"), literal("A01"))):
            record = scored(row, answer(substitute))
            self.assertTrue(record["contract_valid"])
            self.assertFalse(record["intent_match"])
            self.assertFalse(record["passed"])
            records.append(record)
        matrix = summary(records)["intent_confusion"]["matrix"]
        self.assertEqual(matrix["calendar_add"]["calendar_search"], 1)
        self.assertEqual(matrix["calendar_search"]["calendar_add"], 1)
        self.assertEqual(matrix["chat"]["calendar_add"], 1)
        self.assertEqual(set(matrix), set(INTENTS))

    def test_invalid_intent_has_own_matrix_column(self):
        case = case_from_row(literal("A01"))
        for raw in ("broken", "[]", '{"intent":[],"params":{},"reply":"text"}'):
            record = grade(case, raw)
            self.assertFalse(record["passed"])
            self.assertEqual(summary([record])["intent_confusion"]["matrix"]["calendar_add"]["invalid"], 1)

    def test_known_twelve_fifteen_reply_bug_still_fails(self):
        row = literal("A01")
        wrong = answer(row)
        wrong["reply"] = "Позвонить переплётчику завтра в двенадцать минут первого."
        record = scored(row, wrong)
        self.assertTrue(record["intent_match"])
        self.assertTrue(record["params_exact"])
        self.assertFalse(record["reply_clock_correct"])
        self.assertFalse(record["reply_params_consistent"])
        self.assertFalse(record["passed"])

    def test_relative_minute_and_duration_value_orders(self):
        first, second = literal("A27"), literal("A28")
        self.assertEqual(answer(first)["params"], answer(second)["params"])
        self.assertEqual(answer(first)["params"], dict(title="Замер стекла", starts_at="2026-10-04T12:15",
                                                       duration_min=12, value=4))
        self.assertTrue(scored(first)["offset_arithmetic_correct"])
        bad = answer(first)
        bad["params"]["starts_at"] = "2026-10-04T12:16"
        bad["reply"] = "Замер стекла в шестнадцать минут первого на двенадцать минут, значение четыре."
        result = scored(first, bad)
        self.assertTrue(result["reply_params_consistent"])
        self.assertFalse(result["offset_arithmetic_correct"])
        self.assertFalse(result["passed"])

    def test_search_intervals_across_year_and_leap_day(self):
        self.assertEqual(answer(literal("S16", "search_train"))["params"]["range_end"], "2027-01-02T00:00")
        self.assertEqual(answer(literal("H04", "holdout"))["params"]["range_end"], "2032-03-01T00:00")
        self.assertEqual(answer(literal("S11", "search_train"))["params"]["range_start"], "2026-10-04T12:14")
        self.assertEqual(answer(literal("S24", "search_train"))["params"], {"query": ""})


class TrainingTest(unittest.TestCase):
    def test_development_preserves_old_cases_and_excludes_holdout(self):
        dev = read(OUTPUT / "generation_dev.json")
        self.assertEqual(dev[:96], read(SOURCE / "generation_dev.json"))
        cases = development_cases(dev)
        self.assertEqual(len(cases), 232)
        self.assertEqual({c["expected"]["intent"] for c in cases}, set(INTENTS))
        self.assertTrue(all(grade(c, json.dumps(c["expected"], ensure_ascii=False))["passed"] for c in cases))
        self.assertEqual(CONFIG["grader_version"], VERSION)
        for row in literals("holdout"):
            with self.assertRaisesRegex(ValueError, "holdout"):
                development_cases([row])

    def test_selection_penalizes_wrong_intent_and_wrong_clock(self):
        row = literal("A01")
        case = case_from_row(row)
        good = scored(row)
        wrong_intent = scored(row, answer(literal("S01", "search_train")))
        wrong_time = answer(row)
        wrong_time["reply"] = "Позвонить переплётчику завтра в двенадцать минут первого."
        self.assertGreater(selection_score([case], [good]), selection_score([case], [wrong_intent]))
        self.assertGreater(selection_score([case], [good]), selection_score([case], [scored(row, wrong_time)]))
        old = deepcopy(good); old["grader_version"] = "v12.65-reply-consistency-1"
        with self.assertRaisesRegex(ValueError, "version"):
            selection_score([case], [old])
        case["split"] = "holdout"
        with self.assertRaisesRegex(ValueError, "holdout"):
            selection_score([case], [good])

    def test_macro_intent_is_not_dominated_by_add_count(self):
        add = literal("A01"); lookup = literal("S01", "search_train")
        records = [scored(add) for _ in range(9)] + [scored(lookup, answer(add))]
        self.assertEqual(summary(records)["intent_confusion"]["macro_accuracy"], 0.5)

    def test_schedule_includes_all_epoch_ends(self):
        self.assertEqual(selection_steps(6009), [188, 376, 564, 752, 940, 1128])

    def test_android_transport_keeps_literal_user_and_reply(self):
        row = literal("A01")
        converted = variant_row(row, "android_runtime")
        self.assertEqual(row["messages"][1:], converted["messages"][1:])
        self.assertEqual(converted["messages"][0]["content"],
                         "cегодня 2026-10-04 10:00 день недели воскресенье ответ JSON")

    def test_holdout_prompts_are_bound_to_dataset_and_response_hash(self):
        inputs = prepared_inputs()
        self.assertEqual(len(inputs["cases"]), 56)
        self.assertEqual(len({c["prompt_sha256"] for c in inputs["cases"]}), 56)
        inputs["cases"] = inputs["cases"][:1]
        case = inputs["cases"][0]
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "inputs.json").write_text(json.dumps(inputs), encoding="utf-8")
            (root / (case["id"] + ".json")).write_text(json.dumps(dict(id=case["id"], prompt_sha256="wrong",
                model_sha256="a" * 64, output=json.dumps(case["expected"]))), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "binding"):
                score_cached(root / "inputs.json", root)


if __name__ == "__main__":
    unittest.main()
