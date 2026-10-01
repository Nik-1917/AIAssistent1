"""Copy reviewed literal input variants and targets; never synthesize Russian text.

This is a dataset auditor/serializer, not an Android clock parser. Full relative
clock coverage is accepted only after independent numeric/date/unit checks.
"""
from __future__ import annotations
import argparse
from collections import Counter
from copy import deepcopy
from datetime import date
import json
from pathlib import Path
import re

from dataset_contract import normalize_record, parse_and_validate_assistant_response
from freeze_v12_63_baseline import digest
from prepare_v12_61 import GENITIVE, NUMBERS, ORDINAL, number, norm, validate_partitions, compact_clock
from audit_v12_62_reply_speech import check_clock

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'docs/calendar_sft_v12_62'
MANUAL = ROOT / 'docs/calendar_v12_63_manual'
OUTPUT = ROOT / 'docs/calendar_sft_v12_63'
CARD = '(?:' + '|'.join(sorted(NUMBERS, key=len, reverse=True)) + ')'
GEN = '(?:' + '|'.join(sorted(GENITIVE, key=len, reverse=True)) + ')'
ORD = '(?:' + '|'.join(ORDINAL) + ')'
CN = CARD + '(?: ' + CARD + ')?'
GN = GEN + '(?: ' + GEN + ')?'
DP = r'(?P<daypart>утра|дня|вечера|ночи)'
RELATIVE = re.compile(
    r'\b(?:без (?:(?P<quarter_to>четверти)|(?P<remaining>' + GN + r')(?: минуты| минут)?) (?P<next>' + CN + r')'
    r'|(?P<special>четверть|половина|половине|половины) (?P<special_hour>' + ORD + r')'
    r'|пол[ -]?(?P<half_hour>' + ORD + r')'
    r'|(?P<minutes>' + CN + r') (?:минута|минуту|минуты|минут) (?P<ordinal>' + ORD + r'))\s+' + DP + r'\b')
WHOLE = re.compile(r'\b(?:в|на) (?:(?P<hour>' + CN + r') (?:часа|часов|час)|(?P<one>час))(?: (?P<daypart>утра|дня|вечера|ночи))?\b')
FULL = re.compile(r'\b(?P<hour>' + CN + r') (?:часов|часа|час) (?P<minute>' + CN + r') (?:минуту|минута|минуты|минут)(?: (?P<daypart>утра|дня|вечера|ночи))?\b')

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def answer(row):
    return json.loads(row['messages'][2]['content'])

def resolve_hour(h, part):
    if part is None:
        if 0 <= h <= 23:
            return h
    elif 1 <= h <= 12:
        if part == 'утра' and h < 12:
            return h
        if part == 'дня':
            return 12 if h == 12 else h + 12
        if part == 'вечера':
            return 0 if h == 12 else h + 12
        if part == 'ночи':
            return 0 if h == 12 else h if h <= 5 else h + 12
    raise ValueError(f'unresolved hour/daypart: {h}, {part}')

def relative_clocks(text):
    text = norm(text)
    clocks = []
    for m in RELATIVE.finditer(text):
        if m['next']:
            next_hour = resolve_hour(number(m['next']), m['daypart'])
            remaining = 15 if m['quarter_to'] else number(m['remaining'], GENITIVE)
            if not 1 <= remaining <= 29:
                raise ValueError('minutes-to outside reviewed range')
            minute = 60 - remaining
        else:
            ordinal = m['special_hour'] or m['half_hour'] or m['ordinal']
            next_hour = resolve_hour(ORDINAL[ordinal], m['daypart'])
            minute = 30 if m['half_hour'] or m['special'] in ('половина', 'половине', 'половины') else 15 if m['special'] else number(m['minutes'])
            if not 1 <= minute <= 59:
                raise ValueError('elapsed minute outside range')
        clocks.append(((next_hour - 1) % 24, minute))
    if clocks:
        return clocks
    if re.search(r'\b(?:в полночь|на полночь)\b', text):
        return [(0, 0)]
    if re.search(r'\b(?:в полдень|на полдень)\b', text):
        return [(12, 0)]
    for m in WHOLE.finditer(text):
        if m[0].startswith('на ') and m['daypart'] is None:
            continue
        clocks.append((resolve_hour(1 if m['one'] else number(m['hour']), m['daypart']), 0))
    return clocks

def literal_register(family):
    result = {}
    for path in sorted(MANUAL.glob(f'{family}_??.txt')):
        for line_no, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            if not line or line.startswith('#'):
                continue
            clock, user = line.split('|', 1)
            if clock in result or not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d', clock) or user != user.strip():
                raise ValueError(f'duplicate/malformed literal: {path}:{line_no}')
            result[clock] = (user, f'{path.relative_to(ROOT).as_posix()}:{line_no}')
    return result

def duration(text):
    # Only check the explicit final "на duration" in this literal grid.
    # Dates beginning "на завтра/сегодня/послезавтра" are not durations.
    text = norm(text).rstrip('.')
    found = []
    for m in re.finditer(r'\bна (?:(?P<special>час|полчаса|четверть часа|половину часа)|(?P<n>' + CN + r') (?P<unit>минуту|минуты|минут))\b', text):
        value = {'час': 60, 'полчаса': 30, 'четверть часа': 15, 'половину часа': 30}.get(m['special']) if m['special'] else number(m['n'])
        # "на час ночи" is a clock, not an hour-long event.
        if m['special'] == 'час' and re.match(r' (?:ночи|дня)\b', text[m.end():]):
            continue
        found.append(value)
    if len(found) > 1:
        raise ValueError('multiple duration declarations')
    return found[0] if found else None

def full_clocks(text):
    return [(resolve_hour(number(m['hour']), m['daypart']), number(m['minute'][5:] if m['minute'].startswith('ноль ') else m['minute'])) for m in FULL.finditer(norm(text))]

def check_variant(clock, user, row, family='relative'):
    a = answer(row)
    p = a['params']
    actual = relative_clocks(user) if family == 'relative' else full_clocks(user)
    expected = tuple(map(int, clock.split(':')))
    if actual != [expected] or p['starts_at'][-5:] != clock:
        raise ValueError(f'{clock}: input clock {actual}: {user}')
    text = norm(user)
    words = [d for d in ('сегодня', 'завтра', 'послезавтра') if re.search(r'\b' + d + r'\b', text)]
    if 'завтрашн' in text:
        words.append('завтра')
    if len(words) != 1:
        raise ValueError(f'{clock}: expected one explicit relative date: {user}')
    context_date = re.search(r'\d{4}-\d{2}-\d{2}', row['messages'][0]['content']).group()
    delta = (date.fromisoformat(p['starts_at'][:10]) - date.fromisoformat(context_date)).days
    if delta != {'сегодня': 0, 'завтра': 1, 'послезавтра': 2}[words[0]]:
        raise ValueError(f'{clock}: date differs: {user}')
    if duration(user) != p.get('duration_min'):
        raise ValueError(f'{clock}: duration differs: {user}')
    # Titles may be inflected naturally, so this check is deliberately a
    # conservative common-stem guard plus a retained manual review register.
    title_words = re.findall(r'[а-я]+', norm(p['title']))
    input_words = re.findall(r'[а-я]+', text)
    for word in title_words:
        if len(word) >= 4 and not any(w.startswith(word[:max(4, len(word)-2)]) for w in input_words):
            raise ValueError(f'{clock}: title word possibly lost: {word}: {user}')
    spoken = re.sub(re.escape(p['title']), 'EVENT', a['reply'], count=1, flags=re.I)
    if not check_clock(spoken, clock):
        raise ValueError(f'{clock}: source reply no longer matches')

def protected_check():
    lock = read(MANUAL / 'baseline_lock.json')
    changed = []
    for relative, sha in lock['files'].items():
        if relative in lock['authorized_existing_file_edits']:
            continue
        path = ROOT / relative
        if not path.is_file() or digest(path) != sha:
            changed.append(relative)
    if changed:
        raise ValueError(f'protected files changed: {changed}')
    return len(lock['files'])

def conversations(name):
    contexts = read(MANUAL / 'contexts.json')
    rows = []
    for line_no, line in enumerate((MANUAL / f'{name}.txt').read_text(encoding='utf-8').splitlines(), 1):
        if not line or line.startswith('#'):
            continue
        key, context, user, literal_answer = line.split('|', 3)
        row = {'case_id': 'V1263' + key, 'category': 'v12_63_' + name, 'contract_version': 'v12.57',
               'messages': [{'role': 'system', 'content': contexts[context]}, {'role': 'user', 'content': user},
                            {'role': 'assistant', 'content': literal_answer}]}
        normalize_record(row, f'{name}:{line_no}')
        parse_and_validate_assistant_response(literal_answer, contract_version='v12.57')
        rows.append(row)
    return rows

def jsonl_bytes(rows):
    return ''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n' for row in rows).encode('utf-8')

def assemble(partial=False, check_only=False):
    outputs = {p.stem: [json.loads(line) for line in p.read_bytes().splitlines()]
               for p in sorted(SOURCE.glob('*.jsonl'))}
    grid = read(SOURCE / 'clock_coverage_index.json')
    audit = []
    for family in ('relative', 'full'):
        records = literal_register(family)
        for clock, (user, reference) in sorted(records.items()):
            original = outputs['train'][grid[clock]['train_line']-1]
            check_variant(clock, user, original, family)
            new = deepcopy(original)
            new['messages'][1]['content'] = user
            new['case_id'] = 'V1263' + family[0].upper() + clock.replace(':', '')
            new['category'] = 'v12_63_' + family + '_clock'
            new['contract_version'] = 'v12.57'
            normalize_record(new, reference)
            outputs['train'].append(new)
            audit.append({'clock': clock, 'family': family, 'literal': reference, 'source_train_line': grid[clock]['train_line'],
                          'case_id': new['case_id'], 'target_unchanged': new['messages'][2] == original['messages'][2]})
    if partial:
        return {'status': 'PARTIAL', 'reviewed_clocks_by_family': dict(Counter(a['family'] for a in audit))}
    if len(literal_register('relative')) != 1440:
        raise ValueError('incomplete relative input grid')
    outputs['train'].extend(conversations('train_extra'))
    dev = conversations('dev')
    counts = Counter()
    for row in outputs['validation']:
        intent = answer(row)['intent']
        if counts[intent] < 4:
            dev.append(deepcopy(row))
            counts[intent] += 1
    if set(counts.values()) != {4} or len(counts) != 6:
        raise ValueError('incomplete operation regression development set')
    outputs['validation'].extend(conversations('dev'))
    outputs['input_clock_holdout'] = conversations('blind')
    validate_partitions(outputs)
    old_grid = deepcopy(grid)
    for clock, item in old_grid.items():
        row = outputs['train'][item['train_line']-1]
        a = answer(row)
        user = row['messages'][1]['content']
        if 'duration_min' in a['params']:
            user = user.rsplit(' на ', 1)[0]
        if compact_clock(user) != tuple(map(int, clock.split(':'))):
            raise ValueError(f'compact source clock differs: {clock}')
        spoken = re.sub(re.escape(a['params']['title']), 'EVENT', a['reply'], count=1, flags=re.I)
        if not check_clock(spoken, clock):
            raise ValueError(f'compact source reply differs: {clock}')
    if len(old_grid) != 1440:
        raise ValueError('short-input baseline coverage changed')
    # The source prefix in every partition is retained literally, including
    # category/contract metadata and assistant JSON whitespace.
    for source in SOURCE.glob('*.jsonl'):
        original_rows = [json.loads(line) for line in source.read_bytes().splitlines()]
        if outputs[source.stem][:len(original_rows)] != original_rows:
            raise ValueError(f'baseline rows changed: {source.stem}')
    full = []
    for i, row in enumerate(outputs['train'], 1):
        a = answer(row)
        if a['intent'] != 'calendar_add':
            continue
        parsed = full_clocks(row['messages'][1]['content'])
        clock = a['params'].get('starts_at', a['params'].get('time', ''))[-5:]
        if len(parsed) == 1 and clock == f'{parsed[0][0]:02}:{parsed[0][1]:02}':
            full.append({'train_line': i, 'hour': parsed[0][0], 'minute': parsed[0][1]})
    if {x['hour'] for x in full} != set(range(24)) or {x['minute'] for x in full} != set(range(60)):
        raise ValueError('full-unit forms do not cover every hour and minute')
    protected = protected_check()
    import hashlib
    artifacts = {split: {'sha256': hashlib.sha256(jsonl_bytes(rows)).hexdigest(), 'rows': len(rows)} for split, rows in outputs.items()}
    sources = {p.relative_to(ROOT).as_posix(): digest(p) for p in MANUAL.iterdir() if p.is_file()}
    summary = {'version': 'v12.63', 'contract_version': 'v12.57', 'rows': {k: len(v) for k,v in outputs.items()},
               'artifacts': artifacts, 'unchanged_baseline_rows': 5127, 'protected_files_checked': protected,
               'manual_sha256': sources, 'rules_sha256': {p: digest(ROOT / p) for p in (
                   'docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md', 'docs/CALENDAR_ASSISTANT_V12_63_INPUT_CLOCK.md')},
               'coverage': {'compact_grid': 1440, 'relative_grid': 1440, 'full_units_distinct_clocks': len({(x['hour'],x['minute']) for x in full}),
                            'full_units_hours': 24, 'full_units_minutes': 60, 'full_units_is_cartesian_1440': False},
               'training': 'NOT_RUN', 'inference': 'NOT_RUN'}
    if check_only:
        for split, rows in outputs.items():
            if (OUTPUT / f'{split}.jsonl').read_bytes() != jsonl_bytes(rows):
                raise ValueError(f'reassembly differs: {split}')
        if read(OUTPUT / 'generation_dev.json') != dev:
            raise ValueError('development set changed')
        return summary
    if OUTPUT.exists():
        raise ValueError('output already exists; refusing overwrite')
    OUTPUT.mkdir()
    for split, rows in outputs.items():
        (OUTPUT / f'{split}.jsonl').write_bytes(jsonl_bytes(rows))
    write(OUTPUT / 'manifest.json', summary)
    write(OUTPUT / 'clock_coverage_index.json', old_grid)
    write(OUTPUT / 'input_variants_index.json', audit)
    write(OUTPUT / 'full_units_index.json', full)
    write(OUTPUT / 'generation_dev.json', dev)
    provenance = deepcopy(read(SOURCE / 'provenance.json'))
    provenance['dataset_id'] = 'aiassistent1-v12.63-input-clock'
    provenance['review']['reviewed_on'] = '2026-10-01'
    provenance['review']['decision_evidence'] = 'Owner explicitly approved revised input-clock plan after withdrawing the general PM default. Complete literal fictional queries and answers are authored and reviewed in the workspace.'
    provenance['artifacts'] = {s: {'sha256': artifacts[s]['sha256']} for s in ('train','validation')}
    provenance['records'][0]['source_reference'] = 'workspace:docs/calendar_sft_v12_62/provenance.json and docs/calendar_v12_63_manual'
    provenance['records'][0]['rights_evidence'] = 'Existing approved fictional corpus plus manually authored complete literal input variants and conversations; no external corpus or personal calendar data; serializer never generates Russian sentences.'
    write(OUTPUT / 'provenance.json', provenance)
    return summary

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--partial', action='store_true', help='Check written grid rows; never marks incomplete preparation complete')
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    result = assemble(partial=args.partial, check_only=args.check_only)
    print(json.dumps({k:v for k,v in result.items() if k not in ('manual_sha256','rules_sha256','artifacts')}, ensure_ascii=False))
