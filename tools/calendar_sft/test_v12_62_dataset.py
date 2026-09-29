"""Adversarial invariants for the approved reply-only data migration."""
from copy import deepcopy
import json
import unittest

import prepare_v12_62 as prep


class ReplyMigrationTest(unittest.TestCase):
    def setUp(self):
        self.row = {"case_id": "probe", "category": "probe", "contract_version": "v12.57", "messages": [
            {"role": "system", "content": "fixed context"}, {"role": "user", "content": "завтра в 12:15 едем"},
            {"role": "assistant", "content": json.dumps({"intent": "calendar_add", "reply": "Едем в четверть первого.",
             "params": {"title": "Едем", "starts_at": "2026-09-30T12:15"}}, ensure_ascii=False)}]}

    def test_structured_time_cannot_change(self):
        changed = deepcopy(self.row)
        a = prep.answer(changed)
        a["params"]["starts_at"] = "2026-09-30T12:30"
        changed["messages"][-1]["content"] = json.dumps(a)
        with self.assertRaises(ValueError):
            prep.assert_reply_only(self.row, changed)

    def test_user_and_system_cannot_change(self):
        for index in (0, 1):
            changed = deepcopy(self.row)
            changed["messages"][index]["content"] += " changed"
            with self.assertRaises(ValueError):
                prep.assert_reply_only(self.row, changed)

    def test_quarter_is_not_half(self):
        group = {"id": "probe", "time": "12:15", "reply": "Едем."}
        prep.validate_title_literal(group, "Едем в четверть первого.")
        with self.assertRaises(ValueError):
            prep.validate_title_literal(group, "Едем в половине первого.")

    def test_title_is_preserved(self):
        with self.assertRaises(ValueError):
            prep.validate_title_literal({"id": "probe", "time": "12:15", "reply": "Едем."}, "Плывём в четверть первого.")

    def test_minutes_to_boundaries(self):
        for text, expected in (("Едем без двадцати девяти минут час.", (0, 31)),
                               ("Едем без одной минуты двенадцать.", (11, 59)),
                               ("Едем без десяти час.", (0, 50))):
            self.assertEqual(prep.reply_clock(text), expected)
        with self.assertRaises(ValueError):
            prep.reply_clock("Едем в без одной минуты час.")

    def test_duration_is_not_part_of_style_rewrite(self):
        old = "Едем без десяти минут час на тридцать минут."
        valid = "Едем без десяти час на тридцать минут."
        invalid = "Едем без десяти час в половине первого."
        self.assertEqual(prep.style_signature(old), prep.style_signature(valid))
        self.assertNotEqual(prep.style_signature(old), prep.style_signature(invalid))

    def test_only_reviewed_metadata_migration_allowed(self):
        before, after = deepcopy(self.row), deepcopy(self.row)
        before["contract_version"] = "v12.1"
        prep.assert_reply_only(before, after)
        after["category"] = "changed"
        with self.assertRaises(ValueError):
            prep.assert_reply_only(before, after)


if __name__ == "__main__":
    unittest.main()
