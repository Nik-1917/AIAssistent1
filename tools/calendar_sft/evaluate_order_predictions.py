"""Score saved model predictions by order, field set and family. No inference."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

from dataset_contract import load_jsonl, parse_and_validate_assistant_response, file_sha256
from prepare_order_coverage import OUTPUT
from prepare_v12_51 import read_json, write_json


def leaves(value, prefix=''):
    result = {}
    for key, item in value.items():
        path = prefix + key
        if isinstance(item, dict):
            result.update(leaves(item, path + '.'))
        else:
            result[path] = item
    return result


def score(expected, index, predictions):
    by_id = {r['case_id']:r for r in expected}
    if set(predictions) - set(by_id):
        raise ValueError('unknown prediction ID')
    groups, family_results, per_case = defaultdict(list), defaultdict(list), []
    for case_id, row in by_id.items():
        reference = json.loads(row['messages'][-1]['content'])
        actual, error = None, None
        if case_id not in predictions:
            error = 'missing prediction'
        else:
            try:
                actual = parse_and_validate_assistant_response(predictions[case_id], contract_version=row['contract_version'])
            except (ValueError, TypeError) as exc:
                error = str(exc)
        correct = actual is not None and actual['intent'] == reference['intent'] and actual['params'] == reference['params']
        meta = index[case_id]
        wanted = leaves(reference['params'])
        got = leaves(actual['params']) if actual else {}
        fields = {key: actual is not None and (key in wanted) == (key in got) and wanted.get(key) == got.get(key) for key in wanted.keys() | got.keys()}
        ignored_notice = None
        if meta['ignored']:
            # Structural hint only; exact parameter comparison is the leakage check.
            ignored_notice = actual is not None and 'не обработан' in actual['reply'].lower() and 'одно событие' in actual['reply'].lower()
        item = {'case_id':case_id, 'family':meta['family'], 'valid_contract':actual is not None, 'exact_intent_and_params':correct, 'fields':fields, 'ignored_notice':ignored_notice, 'error':error}
        per_case.append(item)
        groups[meta['category'] + '/' + meta['order']].append(item)
        groups['provided/' + ','.join(meta['provided']) if meta['provided'] else 'other'].append(item)
        signature = json.dumps([actual['intent'], actual['params']], sort_keys=True, ensure_ascii=False) if actual else None
        family_results[meta['family']].append((signature, correct))
    def summarize(items):
        field_scores = defaultdict(lambda: [0,0])
        for item in items:
            for key, good in item['fields'].items():
                field_scores[key][0] += int(good)
                field_scores[key][1] += 1
        return {'total':len(items), 'valid_contract':sum(i['valid_contract'] for i in items), 'exact_intent_and_params':sum(i['exact_intent_and_params'] for i in items), 'field_accuracy':{k:{'correct':v[0], 'total':v[1]} for k,v in sorted(field_scores.items())}}
    families = {key:{'total_variants':len(values), 'all_variants_correct':all(ok for _,ok in values), 'consistent_params':all(s is not None for s,_ in values) and len({s for s,_ in values}) == 1} for key,values in family_results.items()}
    return {'summary':summarize(per_case), 'by_group':{k:summarize(v) for k,v in sorted(groups.items())}, 'families':families, 'cases':per_case}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predictions', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    predictions = {}
    for line in args.predictions.read_text(encoding='utf-8').splitlines():
        item = json.loads(line)
        if set(item) != {'case_id','prediction'} or item['case_id'] in predictions or not isinstance(item['prediction'], str):
            raise ValueError('need unique case_id and raw prediction string')
        predictions[item['case_id']] = item['prediction']
    source = OUTPUT / 'order_holdout.jsonl'
    report = score(load_jsonl(source), read_json(OUTPUT / 'coverage_index.json'), predictions)
    report.update(holdout_sha256=file_sha256(source), predictions_sha256=file_sha256(args.predictions), inference_performed_by_this_script=False)
    write_json(args.output, report)
    print(json.dumps(report['summary']))


if __name__ == '__main__':
    main()
