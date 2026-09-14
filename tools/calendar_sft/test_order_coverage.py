from copy import deepcopy
import json
import unittest

from dataset_contract import load_jsonl
from prepare_order_coverage import OUTPUT, SOURCE, SOURCE_HASHES, ORDERS, prepare, observed_order, validate_interval
from evaluate_order_predictions import score


class OrderCoverageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows, cls.payloads, cls.index, cls.coverage = prepare()

    def test_total_interval_balance_including_legacy(self):
        self.assertEqual({o:20 for o in ORDERS}, self.coverage['interval_train_after'])
        self.assertEqual(375, len(self.index))

    def test_serialized_rows_and_frozen_sources(self):
        for split, payload in self.payloads.items():
            self.assertEqual(payload, (OUTPUT / f'{split}.jsonl').read_bytes())
            if split in SOURCE_HASHES:
                before = (SOURCE / f'{split}.jsonl').read_bytes()
                self.assertTrue(payload.startswith(before))
                if split not in ('train','validation'):
                    self.assertEqual(before, payload)

    def test_split_interval_and_missing_fields_remain_exact(self):
        records = {r['case_id']:json.loads(r['messages'][-1]['content']) for rs in self.rows.values() for r in rs if r.get('case_id') in self.index}
        self.assertEqual(records['OI01-EDI']['params'], records['OI01-DSEF']['params'])
        self.assertEqual({'starts_at':'2028-02-28T17:00', 'ends_at':'2028-02-28T19:00'}, records['OP04-I']['params'])
        self.assertEqual({'starts_at':'2028-02-28T20:30'}, records['OP03-T']['params'])
        self.assertEqual({'title':'Доставка бумаги', 'date':'2028-02-29'}, records['OM04-EI']['params'])
        self.assertEqual('2028-02-29T19:00', records['OM02-IE']['params']['starts_at'])
        self.assertNotIn('duration_min', records['OM03-EI']['params'])
        self.assertEqual(0, records['OM03-EI']['params']['value'])
        self.assertEqual('2028-02-29T18:30', records['OC03-EI']['params']['starts_at'])

    def test_scorer_missing_outputs_are_failures(self):
        report = score(self.rows['order_holdout'], self.index, {})
        self.assertEqual(43, report['summary']['total'])
        self.assertEqual(0, report['summary']['exact_intent_and_params'])
        self.assertTrue(all(not v['consistent_params'] for v in report['families'].values()))

    def test_scorer_consistency_does_not_hide_wrong_times(self):
        refs = [r for r in self.rows['order_holdout'] if r['case_id'].startswith('OIH01-')]
        predictions = {}
        for row in refs:
            value = json.loads(row['messages'][-1]['content'])
            value['params']['starts_at'] = '2028-03-03T08:30'
            predictions[row['case_id']] = json.dumps(value, ensure_ascii=False)
        report = score(refs, self.index, predictions)
        self.assertTrue(report['families']['OIH01']['consistent_params'])
        self.assertFalse(report['families']['OIH01']['all_variants_correct'])
        self.assertEqual(0, report['summary']['field_accuracy']['starts_at']['correct'])

    def test_scorer_detects_leakage_from_ignored_event(self):
        row = next(r for r in self.rows['order_holdout'] if r['case_id'] == 'OMH01-EI')
        answer = json.loads(row['messages'][-1]['content'])
        answer['params']['value'] = 8
        report = score([row], self.index, {row['case_id']:json.dumps(answer, ensure_ascii=False)})
        self.assertEqual(0, report['summary']['exact_intent_and_params'])
        self.assertEqual({'correct':0,'total':1}, report['summary']['field_accuracy']['value'])


if __name__ == '__main__':
    unittest.main()
