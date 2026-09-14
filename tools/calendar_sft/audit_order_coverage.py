"""Read-only corpus audit; bounded order recognition reports unresolved rows explicitly."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

from dataset_contract import file_sha256, load_jsonl, normalize_record
from prepare_v12_52 import normalized_words
from prepare_v12_55 import word_order
from prepare_v12_56 import START_RE, END_RE, interval_clocks

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'docs/calendar_sft_v12_57'
DAY = re.compile(r'\b(?:послезавтра|завтра|сегодня)\b')
# Individually reviewed interval rows: E event, D date, I intact interval,
# S/F start/end when another parameter separates the interval endpoints.
MANUAL_INTERVAL_ORDERS = {
    'V1256TW00':'DIE', 'V1256TW01':'EDI', 'V1256TW02':'SDFE', 'V1256TW03':'EID',
    'V1256TW04':'DEI', 'V1256TW05':'IE', 'V1256TW06':'EDI', 'V1256TW07':'SDEF',
    'V1256TW08':'DIE', 'V1256TW09':'EID', 'V1256TW10':'IDE', 'V1256TW11':'DEI',
    'V1256TW12':'DIE', 'V1256TW13':'IDE', 'V1256TW14':'EDI', 'V1256TW15':'DIE',
    'V1256TW16':'EID', 'V1256TW17':'DIE', 'V1256TW18':'SDEF', 'V1256TW19':'DEI',
    'V1256TW20':'EID', 'V1256TW21':'IDE', 'V1256TW22':'DEI', 'V1256TW23':'DIE',
    'V1256TP00':'DIE', 'V1256TP01':'EDI', 'V1256TP02':'IDE', 'V1256TP03':'DEI',
    'V1256TP04':'SDFE', 'V1256TP05':'EID', 'V1256TP06':'DEI', 'V1256TP07':'IDE',
    'V1256TP08':'DIE', 'V1256TP09':'EDI', 'V1256TP10':'IED', 'V1256TP11':'DEI',
    'V1256TP12':'SDEF', 'V1256TP13':'EID', 'V1256TP14':'DIE', 'V1256TP15':'EDI',
    'V1256TP16':'IDE', 'V1256TP17':'DEI', 'V1256TP18':'IDE', 'V1256TP19':'EID',
    'V1256TP20':'DIE', 'V1256TP21':'EDI', 'V1256TP22':'IDE', 'V1256TP23':'DEI',
}


def interval_order(user, title):
    user, title = normalized_words(user), normalized_words(title or '')
    start, end, day = START_RE.search(user), END_RE.search(user), DAY.search(user)
    event = user.find(title) if title else -1
    if not start or not end or not day or event < 0:
        return 'unclassified'
    if start.start() < event < end.start():
        return ''.join(key for _, key in sorted([(day.start(), 'D'), (start.start(), 'S'), (event, 'E'), (end.start(), 'F')]))
    return ''.join(key for _, key in sorted([(day.start(), 'D'), (start.start(), 'I'), (event, 'E')]))


def audit():
    report = {'source': str(SOURCE), 'method': 'Exact normalized title + today/tomorrow + bounded clock expressions; unresolved rows stay unclassified. Counts are corpus coverage, not model accuracy.', 'files': {}, 'same_context_parameter_conflicts': [], 'interval_clock_mismatches': [], 'invalid_rows': []}
    contexts = defaultdict(list)
    for path in sorted(SOURCE.glob('*.jsonl')):
        rows = load_jsonl(path)
        intents, fields, intervals, halves, reviewed = Counter(), Counter(), Counter(), Counter(), Counter()
        for index, row in enumerate(rows):
            label = row.get('case_id', f'{path.stem}:{index+1}')
            try:
                normalize_record(row, label)
            except ValueError as error:
                report['invalid_rows'].append({'case': label, 'error': str(error)})
            answer = json.loads(row['messages'][-1]['content'])
            user = row['messages'][-2]['content']
            intent, params = answer['intent'], answer['params']
            intents[intent] += 1
            fields[intent + ':' + ','.join(sorted(params))] += 1
            context = tuple((m['role'], normalized_words(m['content'])) for m in row['messages'][:-1])
            contexts[context].append({'split': path.stem, 'case': label, 'intent': intent, 'params': params})
            if intent == 'calendar_add' and START_RE.search(normalized_words(user)) and END_RE.search(normalized_words(user)):
                intervals[interval_order(user, params.get('title'))] += 1
                reviewed[MANUAL_INTERVAL_ORDERS.get(label, 'not_manually_reviewed')] += 1
                try:
                    start, end = interval_clocks(user)
                    if 'ends_at' in params and (params['starts_at'][-5:] != start.isoformat(timespec='minutes') or params['ends_at'][-5:] != end.isoformat(timespec='minutes')):
                        report['interval_clock_mismatches'].append(label)
                except ValueError:
                    intervals['clock_check_unresolved'] += 1
            if str(label).startswith('V1255T'):
                try:
                    halves[word_order({'user': user, 'id': label})] += 1
                except ValueError:
                    halves['unclassified'] += 1
        report['files'][path.name] = {'rows': len(rows), 'sha256': file_sha256(path), 'intents': dict(intents), 'parameter_sets': dict(fields), 'recognized_interval_orders': dict(intervals), 'manually_reviewed_interval_orders': dict(reviewed), 'v1255_half_hour_orders': dict(halves)}
    for values in contexts.values():
        meanings = {json.dumps([v['intent'], v['params']], sort_keys=True, ensure_ascii=False) for v in values}
        if len(meanings) > 1:
            report['same_context_parameter_conflicts'].append(values)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = audit()
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({s: {'rows': f['rows'], 'intervals': f['recognized_interval_orders'], 'halves': f['v1255_half_hour_orders']} for s, f in result['files'].items()}, ensure_ascii=False))
    print(json.dumps({k: result[k] for k in ('same_context_parameter_conflicts', 'interval_clock_mismatches', 'invalid_rows')}, ensure_ascii=False))
