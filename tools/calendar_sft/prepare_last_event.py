"""Serialize individually authored examples; preserve the trained V12.56 bytes."""
import argparse
from collections import Counter
import json
from pathlib import Path

from dataset_contract import file_sha256, load_jsonl, normalize_record, normalized_user_prompt
from dataset_provenance import verify_dataset_provenance
from prepare_v12_51 import read_json, write_json

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'docs/calendar_sft_v12_56'
OUTPUT = ROOT / 'docs/calendar_sft_v12_56_last_event'
MANUAL = ROOT / 'docs/calendar_assistant_last_event_manual.json'
GROUPS = ('last_created', 'find_and_update', 'named_event')
COUNTS = {'train': 30, 'validation': 6, 'last_event_holdout': 6}


def prepare():
    manual = read_json(MANUAL)
    source_manifest = read_json(SOURCE / 'manifest.json')
    source_files = source_manifest['artifacts']
    for split, info in source_files.items():
        if file_sha256(SOURCE / f'{split}.jsonl') != info['sha256']:
            raise ValueError(f'V12.56 source changed: {split}')
    verify_dataset_provenance(SOURCE / 'provenance.json', SOURCE / 'train.jsonl', SOURCE / 'validation.jsonl')
    rows = {split: load_jsonl(SOURCE / f'{split}.jsonl') for split in source_files}
    added = {split: [] for split in COUNTS}
    balance = Counter()
    for e in manual['examples']:
        split, group = e['split'], e['group']
        if split not in COUNTS or group not in GROUPS:
            raise ValueError('unexpected manual category')
        answer = e['assistant']
        if answer['intent'] != 'calendar_update':
            raise ValueError('all manual cases must be updates')
        target = answer['params']['target']
        if group == 'named_event':
            if not target.get('query') or 'use_last_created' in target:
                raise ValueError('named event must use query')
        elif target != {'use_last_created': True}:
            raise ValueError('last-created event must use only use_last_created')
        row = normalize_record({
            'case_id': e['id'], 'category': 'last_event_' + group, 'contract_version': 'v12.56',
            'messages': [{'role': 'system', 'content': manual['system']},
                         {'role': 'user', 'content': e['user']},
                         {'role': 'assistant', 'content': json.dumps(answer, ensure_ascii=False)}],
        }, e['id'])
        if row['messages'][0]['content'] != manual['system'] or row['messages'][1]['content'] != e['user'] or json.loads(row['messages'][2]['content']) != answer:
            raise ValueError('literal content changed')
        added[split].append(row)
        balance[split, group] += 1
    if balance != Counter({(s, g): n // 3 for s, n in COUNTS.items() for g in GROUPS}):
        raise ValueError('manual groups must be balanced')
    inherited_ids = {r.get('case_id') for values in rows.values() for r in values}
    manual_ids = [e['id'] for e in manual['examples']]
    if len(set(manual_ids)) != len(manual_ids) or inherited_ids & set(manual_ids):
        raise ValueError('duplicate ID')
    for split, values in added.items():
        rows.setdefault(split, []).extend(values)
    prompts = {s: {normalized_user_prompt(r) for r in values} for s, values in rows.items()}
    for split, values in added.items():
        other = set().union(*(p for s, p in prompts.items() if s != split))
        new_prompts = [normalized_user_prompt(r) for r in values]
        if len(set(new_prompts)) != len(new_prompts) or other.intersection(new_prompts):
            raise ValueError('duplicate or cross-split manual prompt')
    payloads = {}
    for split, values in rows.items():
        source = SOURCE / f'{split}.jsonl'
        before = source.read_bytes() if source.exists() else b''
        if before and not before.endswith(b'\n'):
            raise ValueError('source needs final newline')
        payloads[split] = before + b''.join((json.dumps(r, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8') for r in added.get(split, []))
    return rows, payloads


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    rows, payloads = prepare()
    if not args.check_only:
        if OUTPUT.exists():
            raise ValueError('refusing to overwrite supplement')
        OUTPUT.mkdir()
        for split, payload in payloads.items():
            (OUTPUT / f'{split}.jsonl').write_bytes(payload)
        artifacts = {s: {'sha256': file_sha256(OUTPUT / f'{s}.jsonl')} for s in rows}
        provenance = read_json(SOURCE / 'provenance.json')
        provenance['dataset_id'] = 'aiassistent1-v12.56-last-event-supplement'
        provenance['artifacts'] = artifacts
        provenance['review'] = {'reviewed_by': 'Codex under project owner instructions', 'reviewed_on': '2026-09-13', 'decision_evidence': 'User approved rules and handwritten examples for a single request only.'}
        provenance['records'] = [{'source_type': 'internal_authored', 'source_reference': 'workspace:docs/calendar_sft_v12_56/provenance.json; workspace:docs/calendar_assistant_last_event_manual.json', 'rights_evidence': 'Inherited approved V12.56 bytes and manually authored fictional requests and answers. No external material or model output imported.', 'permits_model_training': True, 'permits_derivative_weight_distribution': True, 'contains_personal_data': False}]
        write_json(OUTPUT / 'provenance.json', provenance)
        verify_dataset_provenance(OUTPUT / 'provenance.json', OUTPUT / 'train.jsonl', OUTPUT / 'validation.jsonl')
        write_json(OUTPUT / 'manifest.json', {'base_version': 'v12.56', 'supplement': 'single_request_last_event', 'new_counts': COUNTS, 'total_rows': {s: len(v) for s, v in rows.items()}, 'manual_sha256': file_sha256(MANUAL), 'artifacts': artifacts, 'inherited_bytes': 'UNCHANGED', 'training': 'NOT_RUN', 'model_evaluation': 'NOT_RUN'})
    print(json.dumps({s: len(v) for s, v in rows.items()}))


if __name__ == '__main__':
    main()
