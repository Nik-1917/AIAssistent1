"""Verify literal supplementation and isolated holdouts, without model inference."""
import json
import unittest
from prepare_last_event import MANUAL, OUTPUT, SOURCE, prepare
from prepare_v12_51 import read_json


class LastEventDataTest(unittest.TestCase):
    def test_literals_and_frozen_source_bytes(self):
        rows, payloads = prepare()
        self.assertEqual(3570, sum(map(len, rows.values())))
        for split, payload in payloads.items():
            self.assertEqual(payload, (OUTPUT / f'{split}.jsonl').read_bytes())
            source = SOURCE / f'{split}.jsonl'
            if source.exists():
                self.assertTrue(payload.startswith(source.read_bytes()))
                if split not in ('train', 'validation'):
                    self.assertEqual(source.read_bytes(), payload)

    def test_key_semantics_and_unchanged_unrequested_fields(self):
        cases = {e['id']: e['assistant']['params'] for e in read_json(MANUAL)['examples']}
        self.assertEqual({'target': {'use_last_created': True}, 'changes': {}}, cases['LET01'])
        self.assertEqual({'time': '16:30'}, cases['LET11']['changes'])
        self.assertEqual({'time': '20:30'}, cases['LET17']['changes'])
        self.assertEqual({'time': '08:30'}, cases['LET08']['changes'])
        self.assertEqual({'value': 0}, cases['LET06']['changes'])
        self.assertEqual({'clear_value': True}, cases['LET07']['changes'])
        self.assertEqual('2026-09-13T00:00', cases['LET29']['target']['range_start'])
        self.assertEqual({'date': '2026-09-14'}, cases['LET29']['changes'])


if __name__ == '__main__':
    unittest.main()
