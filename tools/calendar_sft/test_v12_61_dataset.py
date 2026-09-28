"""Semantic release checks and rejection tests for the manually authored data."""
from collections import Counter
from copy import deepcopy
import json
import unittest

import prepare_v12_61 as data


def answer(row):
    return json.loads(row["messages"][-1]["content"])


class V1261DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs, cls.changes, cls.excluded = data.assemble()
        cls.speech = data.speech_rows()
        cls.cases = {r["case_id"]: r for rows in cls.speech.values() for r in rows}

    def test_all_clock_cells_are_real_training_rows(self):
        index = data.clock_coverage_index(self.outputs["train"])
        self.assertEqual(set(index), {f"{h:02d}:{m:02d}" for h in range(24) for m in range(60)})
        self.assertEqual(Counter(r["origin"] for r in index.values()), {"new_literal": 1200, "inherited": 240})
        for clock, item in index.items():
            row = self.outputs["train"][item["train_line"] - 1]
            self.assertEqual(row["case_id"], item["case_id"])
            self.assertTrue(answer(row)["params"]["starts_at"].endswith("T" + clock))

    def test_missing_or_mislabelled_clock_is_rejected(self):
        train = deepcopy(self.outputs["train"])
        row = next(r for r in train if r.get("case_id") == "V1261T0001")
        train.remove(row)
        with self.assertRaisesRegex(ValueError, "coverage"):
            data.clock_coverage_index(train)
        payload = answer(row)
        payload["params"]["starts_at"] = "2026-10-01T00:02"
        row["messages"][-1]["content"] = json.dumps(payload, ensure_ascii=False)
        train.append(row)
        with self.assertRaises(ValueError):
            data.clock_coverage_index(train)

    def test_literal_clock_grid_has_six_orders_and_varied_contexts_within_hours(self):
        report = data.grid_surface_report(self.outputs["train"])
        self.assertEqual(set(report["word_orders"]), {"title-date-clock", "title-clock-date", "date-title-clock", "date-clock-title", "clock-title-date", "clock-date-title"})
        self.assertEqual(sum(report["word_orders"].values()), 1200)
        self.assertEqual(set(report["distinct_contexts_per_hour"].values()), {24})
        self.assertEqual(set(report["date_words"]), {"сегодня", "завтра", "послезавтра"})

    def test_original_examples_are_accounted_for_and_only_queries_change(self):
        ledger = {(c["source"], c["line"]): c for c in self.changes}
        changed = 0
        for split in data.read_json(data.SOURCE / "manifest.json")["artifacts"]:
            records = self.outputs[split] + [r["record"] for r in self.excluded if r["source"] == split]
            if split == "validation":
                records = records + self.outputs["context_transfer_eval"]
            records = [r for r in records if not r.get("case_id", "").startswith("V1261")]
            by_prompt = {tuple(m["content"] for m in r["messages"][:-1]): r for r in records}
            originals = [json.loads(line) for line in (data.SOURCE / (split + ".jsonl")).read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(records), len(originals))
            self.assertEqual(len(by_prompt), len(originals))
            for line, old in enumerate(originals, 1):
                new = deepcopy(by_prompt[tuple(m["content"] for m in old["messages"][:-1])])
                old_answer, new_answer = answer(old), answer(new)
                correction = ledger.get((split, line))
                if correction:
                    target = new_answer["params"].get("target", new_answer["params"])
                    self.assertEqual(target["query"], correction["new_query"])
                    target["query"] = correction["old_query"]
                    changed += 1
                self.assertEqual(new_answer, old_answer)
                new["messages"][-1]["content"] = old["messages"][-1]["content"]
                self.assertEqual(new, old)
        self.assertEqual(changed, 192)

    def test_all_multiturn_examples_are_retained_outside_active_splits(self):
        self.assertEqual(Counter(r["source"] for r in self.excluded), {"train": 94, "validation": 29, "regression_holdout": 3})
        for rows in self.outputs.values():
            for row in rows:
                self.assertEqual([m["role"] for m in row["messages"]], ["system", "user", "assistant"])
        for item in self.excluded:
            self.assertGreater(sum(m["role"] == "user" for m in item["record"]["messages"]), 1)

    def test_added_history_is_rejected(self):
        outputs = deepcopy(self.outputs)
        outputs["train"][0]["messages"].insert(1, {"role": "user", "content": "Предыдущее сообщение."})
        with self.assertRaises(ValueError):
            data.validate_partitions(outputs)

    def test_validation_leak_is_rejected_even_with_different_system(self):
        outputs = deepcopy(self.outputs)
        leaked = deepcopy(outputs["train"][0])
        leaked["case_id"] = "INTENTIONAL_LEAK_TEST"
        leaked["messages"][0]["content"] = data.read_json(data.MANUAL / "contexts.json")["E"]
        outputs["validation"].append(leaked)
        with self.assertRaisesRegex(ValueError, "training phrasing"):
            data.validate_partitions(outputs)
        self.assertEqual(len(self.outputs["context_transfer_eval"]), 132)

    def test_conflicting_duplicate_prompt_is_rejected(self):
        outputs = deepcopy(self.outputs)
        duplicate = deepcopy(outputs["train"][0])
        duplicate["case_id"] = "INTENTIONAL_CONFLICT_TEST"
        payload = answer(duplicate)
        payload["reply"] = "Другая формулировка ответа."
        duplicate["messages"][-1]["content"] = json.dumps(payload, ensure_ascii=False)
        outputs["train"].append(duplicate)
        with self.assertRaises(ValueError):
            data.validate_partitions(outputs)

    def test_compact_clock_and_reply_edge_forms(self):
        for phrase, expected in (("час ноль один", (1, 1)), ("двадцать пять", (20, 5)), ("двадцать один двадцать одна", (21, 21)), ("двадцать три пятьдесят девять", (23, 59)), ("ноль ноль", (0, 0))):
            self.assertEqual(data.compact_clock(phrase), expected)
        self.assertEqual(data.reply_clock("Смена в двадцать один час."), (9, 0))
        self.assertEqual(data.reply_clock("Смена без одной минуты двенадцать."), (11, 59))
        self.assertEqual(data.reply_clock("Встреча без двадцати минут час."), (0, 40))
        for phrase in ("Встреча в без двадцати минут час.", "Встреча без двадцать минут час."):
            with self.assertRaises(ValueError):
                data.reply_clock(phrase)
        with self.assertRaises(ValueError):
            data.compact_clock("двадцать три пятьдесят девять девять")

    def test_relative_clock_uses_daypart_and_ambiguous_input_stays_partial(self):
        expected = {"V1261S03": "12:30", "V1261S04": "00:30", "V1261S05": "13:15", "V1261S06": "01:15", "V1261S07": "12:45", "V1261S08": "00:45", "V1261S18": "14:30", "V1261S19": "02:30", "V1261S33": "02:30", "V1261H08": "01:30"}
        for case_id, clock in expected.items():
            self.assertTrue(answer(self.cases[case_id])["params"]["starts_at"].endswith("T" + clock))
        ambiguous = [r for r in self.cases.values() if "_ambiguous_" in r["category"]]
        self.assertEqual(len(ambiguous), 12)
        for row in ambiguous:
            params = answer(row)["params"]
            self.assertIn("date", params)
            self.assertFalse({"time", "starts_at", "ends_at"} & params.keys())

    def test_midnight_and_calendar_boundaries(self):
        params = answer(self.cases["V1261S21"])["params"]
        self.assertEqual((params["starts_at"], params["ends_at"], params["duration_min"]), ("2028-02-29T23:55", "2028-03-01T00:05", 10))
        self.assertEqual(answer(self.cases["V1261S38"])["params"]["ends_at"], "2028-01-02T00:30")
        self.assertEqual(answer(self.cases["V1261S48"])["params"]["starts_at"], "2027-06-15T18:20")
        self.assertEqual(answer(self.cases["V1261H20"])["params"]["starts_at"], "2030-05-01T20:00")
        self.assertEqual(answer(self.cases["V1261S44"])["params"]["range_start"], "2027-06-14T18:20")

    def test_duration_value_and_ignored_event_do_not_leak_into_time(self):
        partial = answer(self.cases["V1261S16"])["params"]
        self.assertEqual(partial["duration_min"], 30)
        self.assertNotIn("starts_at", partial)
        offset = answer(self.cases["V1261S17"])["params"]
        self.assertEqual(offset["starts_at"], "2026-09-30T10:45")
        self.assertNotIn("duration_min", offset)
        first = answer(self.cases["V1261S26"])["params"]
        self.assertEqual(first, {"title": "Реставрация рамы", "starts_at": "2028-01-01T13:15"})
        combined = answer(self.cases["V1261S37"])["params"]
        self.assertEqual((combined["starts_at"][-5:], combined["duration_min"], combined["value"]), ("12:40", 25, 40))
        self.assertNotIn("title", answer(self.cases["V1261H21"])["params"])

    def test_new_evaluation_event_titles_are_independent(self):
        titles = {}
        for split, rows in self.speech.items():
            titles[split] = {data.norm(answer(r)["params"]["title"]) for r in rows if "title" in answer(r)["params"]}
        self.assertFalse(titles["train"] & titles["validation"])
        self.assertFalse(titles["train"] & titles["speech_holdout"])
        self.assertFalse(titles["validation"] & titles["speech_holdout"])
        validation_users = {data.user_key(r) for r in self.outputs["validation"]}
        self.assertFalse(validation_users & {data.user_key(r) for r in self.outputs["speech_holdout"]})


if __name__ == "__main__":
    unittest.main()
