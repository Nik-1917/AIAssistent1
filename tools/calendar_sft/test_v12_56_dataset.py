"""Regression checks for PM defaults, endpoint semantics and version isolation."""

from copy import deepcopy
from datetime import datetime, time
import json
import unittest

from dataset_contract import file_sha256, parse_and_validate_assistant_response
from dataset_provenance import verify_dataset_provenance
from prepare_v12_51 import read_json
from prepare_v12_56 import (
    MANUAL, OUTPUT, SOURCE, SOURCE_HASHES, REPLACEMENT_IDS, TOTAL_ROWS,
    default_half, interval_clocks, serialized, validate_inputs, validate_semantics,
)


class V1256DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manual = read_json(MANUAL)
        cls.cases = {e['id']: e for e in cls.manual['examples']}
        cls.added, cls.outputs = validate_inputs()

    def test_handwritten_grid_and_persisted_content(self):
        self.assertEqual(TOTAL_ROWS, {s: len(rows) for s, rows in self.outputs.items()})
        for split, payload in serialized(self.outputs).items():
            self.assertEqual(payload, (OUTPUT / f'{split}.jsonl').read_bytes())
        for rows in self.added.values():
            for row in rows:
                example = self.cases[row['case_id']]
                self.assertEqual(example['user'], row['messages'][1]['content'])
                self.assertEqual(example['assistant'], json.loads(row['messages'][2]['content']))

    def test_frozen_sources_and_only_six_inherited_relabels(self):
        changed = set()
        for split, digest in SOURCE_HASHES.items():
            source = SOURCE / f'{split}.jsonl'
            self.assertEqual(digest, file_sha256(source))
            old_lines = source.read_bytes().splitlines(keepends=True)
            new_lines = (OUTPUT / f'{split}.jsonl').read_bytes().splitlines(keepends=True)
            for before, after in zip(old_lines, new_lines):
                if before != after:
                    self.assertEqual('half_hour_holdout', split)
                    old, new = json.loads(before), json.loads(after)
                    changed.add(old['case_id'])
                    self.assertEqual(old['messages'][:2], new['messages'][:2])
                    self.assertEqual(old['case_id'], new['case_id'])
        self.assertEqual(REPLACEMENT_IDS, changed)
        verify_dataset_provenance(OUTPUT / 'provenance.json', OUTPUT / 'train.jsonl', OUTPUT / 'validation.jsonl')

    def test_all_twelve_default_hours(self):
        cases = [('полпервого', 12), ('полвторого', 13), ('полтретьего', 14),
                 ('полчетвёртого', 15), ('полпятого', 16), ('полшестого', 17),
                 ('полседьмого', 18), ('полвосьмого', 19), ('полдевятого', 20),
                 ('полдесятого', 21), ('пол-одиннадцатого', 22), ('полдвенадцатого', 23)]
        for phrase, hour in cases:
            with self.subTest(phrase=phrase):
                self.assertEqual(hour, default_half(phrase)[1])

    def test_surfaces_and_explicit_dayparts(self):
        for phrase in ('полдевятого', 'в полдевятого', 'пол девятого',
                       'в пол девятого', 'половина девятого', 'в половине девятого'):
            self.assertEqual(20, default_half(phrase)[1])
            self.assertEqual(8, default_half(phrase + ' утра')[1])
        for phrase, hour in [('полпервого ночи', 0), ('полпервого дня', 12),
                             ('полдвенадцатого дня', 11), ('полдвенадцатого ночи', 23)]:
            self.assertEqual(hour, default_half(phrase)[1])

    def test_interval_dayparts_and_event_word_orders(self):
        cases = [
            ('С пяти до семи я в бане.', time(5), time(7)),
            ('Баня завтра с пяти до семи вечера.', time(17), time(19)),
            ('С пяти завтра баня до семи.', time(5), time(7)),
            ('Завтра вечером с пяти до семи баня.', time(17), time(19)),
            ('С полдевятого до полдесятого.', time(20, 30), time(21, 30)),
            ('Утром с полдевятого до полдесятого.', time(8, 30), time(9, 30)),
            ('От половины двенадцатого дня до половины первого дня.', time(11, 30), time(12, 30)),
            ('С одиннадцати вечера до часу ночи.', time(23), time(1)),
        ]
        for user, start, end in cases:
            with self.subTest(user=user):
                self.assertEqual((start, end), interval_clocks(user))

    def test_midnight_keeps_default_and_explicit_night_distinct(self):
        for case_id, minutes in [('V1256HI04', 780), ('V1256TP23', 60), ('V1256EI05', 20)]:
            example = self.cases[case_id]
            params = example['assistant']['params']
            duration = datetime.fromisoformat(params['ends_at']) - datetime.fromisoformat(params['starts_at'])
            self.assertEqual(minutes, duration.total_seconds() / 60)
            validate_semantics(example, False)
            bad = deepcopy(example)
            bad['assistant']['params']['ends_at'] = params['starts_at'][:10] + params['ends_at'][10:]
            with self.assertRaises(ValueError):
                validate_semantics(bad, False)

    def test_default_time_does_not_shift_with_current_clock(self):
        expected = {'V1256HD1302': '2032-04-30T13:30',
                    'V1256HD1501': '2032-05-01T15:30',
                    'V1256HD1904': '2032-05-01T19:30'}
        for case_id, timestamp in expected.items():
            example = self.cases[case_id]
            self.assertEqual(timestamp, example['assistant']['params']['starts_at'])
            validate_semantics(example, True)

    def test_contract_endpoints_and_legacy_isolation(self):
        response = deepcopy(self.cases['V1256TW17']['assistant'])
        self.parse(response)
        for version in (None, 'v12.1', 'v12.5'):
            with self.assertRaises(ValueError):
                parse_and_validate_assistant_response(json.dumps(response, ensure_ascii=False), contract_version=version)
        response['params']['duration_min'] = 120
        self.parse(response)
        start = response['params'].pop('starts_at')
        response['params'].update(date=start[:10], time=start[11:])
        self.parse(response)

    def test_reject_invalid_or_conflicting_endpoints(self):
        original = self.cases['V1256TW17']['assistant']
        for patch in ({'ends_at': '2028-02-29T17:00'}, {'ends_at': '2028-02-29T16:00'},
                      {'ends_at': '19:00'}, {'ends_at': None}, {'duration_min': 60},
                      {'duration_min': True}, {'unknown': 1}):
            response = deepcopy(original)
            response['params'].update(patch)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                self.parse(response)
        response = deepcopy(original)
        response['params'].pop('starts_at')
        response['params']['date'] = '2028-02-29'
        with self.assertRaises(ValueError):
            self.parse(response)

    def test_reply_style_remains_scoped_to_reply(self):
        response = deepcopy(self.cases['V1256TW17']['assistant'])
        response['params']['title'] = 'Обсуждение «План 20:30»'
        self.parse(response)
        response['reply'] = 'Баня завтра в 17:00.'
        with self.assertRaises(ValueError):
            self.parse(response)

    @staticmethod
    def parse(response):
        return parse_and_validate_assistant_response(json.dumps(response, ensure_ascii=False), contract_version='v12.56')


if __name__ == '__main__':
    unittest.main()
