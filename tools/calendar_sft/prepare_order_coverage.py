"""Validate and serialize handwritten order families. Never generate language."""
import argparse
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
import json
from pathlib import Path
import re

from audit_order_coverage import MANUAL_INTERVAL_ORDERS
from dataset_contract import file_sha256, load_jsonl, normalize_record, normalized_user_prompt
from dataset_provenance import verify_dataset_provenance
from prepare_v12_51 import read_json, write_json
from prepare_v12_52 import normalized_words
from prepare_v12_56 import interval_clocks, spoken_clock

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'docs/calendar_sft_v12_57'
OUTPUT = ROOT / 'docs/calendar_sft_order_coverage'
MANUAL_FILES = [ROOT / ('docs/calendar_order_' + name + '_manual.json') for name in ('intervals', 'fields', 'updates', 'first_event')]
ORDERS = ('EDI', 'DEI', 'IDE', 'DIE', 'IED', 'EID', 'DSEF', 'SDEF', 'SDFE', 'ESDF', 'SEDF', 'SEFD')
SOURCE_HASHES = {
    'train':'59752b049ae427561b0bcaf4f56161bd6df2f353bdcc8ff1234778bbc1cdfc57',
    'validation':'3a43ef66df99d5e7d51c5fd54e74bf8e5cca2d5efa0d2e8757e93da72cb70549',
    'holdout':'a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19',
    'calendar_holdout_v12_2':'504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade',
    'regression_holdout':'b2a7bdbc93a6f59769f0ab0a9108dc707e1bc7a494d00cbd5c8bb2fbfa7d1e34',
    'half_hour_holdout':'5d614f04f6d90333c7857b50833c20efebbf055262547d89086d16d8da56982c',
    'interval_holdout':'08532c3e0d667452bec2e7bded0804636bb2c08c29e9a585f68a5eeef3d930d1',
    'last_event_holdout':'d7141c98bdc4a5310200062f64738f2ba768f5a14d9de3acadbcd8ea84b04b53',
}
DATES = {'сегодня':'2028-02-28', 'завтра':'2028-02-29', 'послезавтра':'2028-03-01', 'третьего марта':'2028-03-03'}


def observed_order(user, parts):
    text = normalized_words(user)
    positions = []
    for label, span in parts.items():
        span = normalized_words(span)
        if text.count(span) != 1:
            raise ValueError(f'expected exactly one span {span!r} in {user!r}')
        positions.append((text.index(span), label))
    order = ''.join(key for _, key in sorted(positions))
    return order.replace('SF', 'I') if 'S' in parts else order


def validate_interval(family, user):
    p = family['assistant']['params']
    if set(p) != {'title', 'starts_at', 'ends_at'}:
        raise ValueError('core interval must not invent other fields')
    start_clock, end_clock = interval_clocks(user)
    start_day = date.fromisoformat(DATES[normalized_words(family['parts']['D'])])
    start = datetime.combine(start_day, start_clock)
    if start_clock == end_clock:
        raise ValueError('equal clocks need explicit context')
    end = datetime.combine(start_day + timedelta(days=int(end_clock < start_clock)), end_clock)
    if p['starts_at'] != start.isoformat(timespec='minutes') or p['ends_at'] != end.isoformat(timespec='minutes'):
        raise ValueError('wrong interval timestamps')
    if normalized_words(p['title']) != normalized_words(family['parts']['E']):
        raise ValueError('title differs from family phrase')
    reply = family['assistant']['reply']
    prefix = p['title'] + ' ' + family['parts']['D'] + ', начало '
    if not reply.startswith(prefix) or not reply.endswith('.'):
        raise ValueError('reply title/date differs')
    beginning, ending = reply[len(prefix):-1].split(', окончание ')
    next_day = ending.endswith(' следующего дня')
    if next_day != (end.date() > start.date()):
        raise ValueError('reply end day differs')
    ending = ending.removesuffix(' следующего дня')
    if spoken_clock(beginning, start.minute) != (start.hour % 12, start.minute) or spoken_clock(ending, end.minute) != (end.hour % 12, end.minute):
        raise ValueError('reply clock differs')


def validate_partial(family):
    supplied = set(family['provided'])
    p = family['assistant']['params']
    for concept, field in [('title','title'), ('duration','duration_min'), ('value','value'), ('interval','ends_at')]:
        if (concept in supplied) != (field in p):
            raise ValueError(f'partial request invents or drops {field}')
    expected_clock = bool({'time', 'interval'} & supplied)
    if expected_clock != ('starts_at' in p) or (not expected_clock) != ('date' in p):
        raise ValueError('partial request invents or drops a clock')


def prepare():
    for split, digest in SOURCE_HASHES.items():
        if file_sha256(SOURCE / f'{split}.jsonl') != digest:
            raise ValueError('frozen source changed: ' + split)
    verify_dataset_provenance(SOURCE / 'provenance.json', SOURCE / 'train.jsonl', SOURCE / 'validation.jsonl')
    outputs = {s: load_jsonl(SOURCE / f'{s}.jsonl') for s in SOURCE_HASHES}
    added = {'train':[], 'validation':[], 'order_holdout':[]}
    index, families, family_meanings = {}, set(), {}
    order_counts = Counter()
    for path in MANUAL_FILES:
        manual = read_json(path)
        for family in manual['families']:
            name, split = family['id'], family['split']
            category = family.get('category', 'interval')
            if name in families or split not in added:
                raise ValueError('invalid family or split')
            families.add(name)
            semantic = json.dumps([manual['system'], family['assistant']['intent'], family['assistant']['params']], sort_keys=True, ensure_ascii=False)
            if semantic in family_meanings and family_meanings[semantic] != split:
                raise ValueError('semantic family crosses splits')
            family_meanings[semantic] = split
            if category == 'partial':
                validate_partial(family)
            ignored = family.get('ignored', [])
            reply = family['assistant']['reply']
            if ignored:
                if 'за один запрос можно добавить только одно событие' not in reply or any(normalized_words(title) not in normalized_words(reply) for title in ignored):
                    raise ValueError('missing ignored-event explanation')
                if any(normalized_words(title) == normalized_words(family['assistant']['params'].get('title','')) for title in ignored):
                    raise ValueError('selected an ignored event')
            elif category == 'single_event_control' and 'не обработан' in reply:
                raise ValueError('single event was split')
            for variant, user in family['variants'].items():
                order = variant.split('_')[0]
                case_id = name + '-' + variant
                if 'parts' in family and observed_order(user, family['parts']) != order:
                    raise ValueError(f'{case_id}: wrong declared order')
                if category == 'interval':
                    validate_interval(family, user)
                row = normalize_record({'case_id':case_id, 'category':'order_' + category, 'contract_version':'v12.57', 'messages':[
                    {'role':'system','content':manual['system']}, {'role':'user','content':user},
                    {'role':'assistant','content':json.dumps(family['assistant'], ensure_ascii=False, separators=(',', ':'))},
                ]}, case_id)
                if row['messages'][0]['content'] != manual['system'] or row['messages'][1]['content'] != user or json.loads(row['messages'][-1]['content']) != family['assistant']:
                    raise ValueError('normalization rewrote a literal')
                added[split].append(row)
                index[case_id] = {'family':name,'split':split,'category':category,'order':order,'provided':family.get('provided'), 'ignored':ignored}
                order_counts[split, category, order] += 1
    before = Counter(v for v in MANUAL_INTERVAL_ORDERS.values() if v in ORDERS)
    after = before + Counter({o:order_counts['train','interval',o] for o in ORDERS})
    if after != Counter({o:20 for o in ORDERS}):
        raise ValueError(f'final interval distribution is not uniform: {dict(after)}')
    for split in ('validation', 'order_holdout'):
        if {o:order_counts[split,'interval',o] for o in ORDERS} != {o:2 for o in ORDERS}:
            raise ValueError('new interval evaluation grid must be uniform')
    for split, n in [('train',4),('validation',1),('order_holdout',1)]:
        if {o:order_counts[split,'duration_value',o] for o in ('KLV','KVL','LKV','LVK','VKL','VLK')} != {o:n for o in ('KLV','KVL','LKV','LVK','VKL','VLK')}:
            raise ValueError('duration/value grid must be uniform')
    for split, n in [('train',10),('validation',2),('order_holdout',2)]:
        if [order_counts[split,'update_order',o] for o in ('UC','CU')] != [n,n]:
            raise ValueError('target/change grid must be uniform')
    for split, rows in added.items():
        outputs.setdefault(split, []).extend(rows)
    fitting = set()
    ids, contexts = set(), {}
    prompts = {s:{normalized_user_prompt(r) for r in rows} for s,rows in outputs.items()}
    for split, rows in outputs.items():
        for row in rows:
            cid = row.get('case_id')
            if cid and cid in ids:
                raise ValueError('duplicate case ID')
            if cid:
                ids.add(cid)
            key = tuple((m['role'], normalized_words(m['content'])) for m in row['messages'][:-1])
            value = json.loads(row['messages'][-1]['content'])
            meaning = (value['intent'], value['params'])
            if key in contexts and contexts[key] != meaning:
                raise ValueError('same input has contradictory parameters')
            contexts[key] = meaning
        if split in ('train','validation'):
            fitting |= prompts[split]
    for split, rows in added.items():
        others = set().union(*(p for s,p in prompts.items() if s != split))
        new_prompts = [normalized_user_prompt(r) for r in rows]
        if len(set(new_prompts)) != len(new_prompts) or set(new_prompts) & others:
            raise ValueError(f'{split}: duplicate or leaking new prompt: {sorted(set(new_prompts) & others)}')
    if any(fitting & p for s,p in prompts.items() if s not in ('train','validation')):
        raise ValueError('holdout leakage')
    payloads = {}
    for split in outputs:
        source = SOURCE / f'{split}.jsonl'
        before_bytes = source.read_bytes() if source.exists() else b''
        if before_bytes and not before_bytes.endswith(b'\n'):
            raise ValueError('source needs a final newline')
        payloads[split] = before_bytes + b''.join((json.dumps(r, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8') for r in added.get(split, []))
    coverage = {'interval_train_before':dict(before), 'interval_train_after':dict(after), 'new_counts':{s:len(v) for s,v in added.items()}, 'total_rows':{s:len(v) for s,v in outputs.items()}, 'new_order_counts':[{'split':s,'category':c,'order':o,'count':n} for (s,c,o),n in sorted(order_counts.items())], 'scope':'Final train balance is proved for all twelve E/D/S/F orders with S preceding F. Other new grids are balanced within their category; inherited non-interval corpus distributions are preserved, not globally equalized.'}
    return outputs, payloads, index, coverage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--refresh-draft', action='store_true', help='Refresh only this untrained supplement after a manual correction')
    args = parser.parse_args()
    outputs, payloads, index, coverage = prepare()
    if not args.check_only:
        if OUTPUT.exists() and not args.refresh_draft:
            raise ValueError('refusing to overwrite prepared data')
        if OUTPUT.exists() and (read_json(OUTPUT / 'manifest.json').get('dataset') != 'order_coverage_supplement' or read_json(OUTPUT / 'manifest.json').get('training') != 'NOT_RUN'):
            raise ValueError('only an untrained order-coverage draft can be refreshed')
        OUTPUT.mkdir(exist_ok=True)
        for split,payload in payloads.items():
            (OUTPUT / f'{split}.jsonl').write_bytes(payload)
        artifacts = {s:{'sha256':file_sha256(OUTPUT / f'{s}.jsonl')} for s in outputs}
        write_json(OUTPUT / 'coverage_index.json', index)
        write_json(OUTPUT / 'coverage_report.json', coverage)
        write_json(OUTPUT / 'manifest.json', {'dataset':'order_coverage_supplement', 'base_version':'v12.57', 'contract_version':'v12.57', 'source_sha256':SOURCE_HASHES, 'manual_sha256':{p.name:file_sha256(p) for p in MANUAL_FILES}, 'artifacts':artifacts, 'total_rows':coverage['total_rows'], 'original_bytes':'UNCHANGED', 'training':'NOT_RUN', 'model_evaluation':'NOT_RUN'})
        write_json(OUTPUT / 'provenance.json', {'format_version':1, 'status':'VERIFIED', 'dataset_id':'aiassistent1-order-coverage-v12.57-supplement', 'review':{'reviewed_by':'Codex under project owner instructions', 'reviewed_on':'2026-09-14', 'decision_evidence':'Owner approved manual order/field coverage, balancing and first-event-only rules; training remains a separate step.'}, 'artifacts':artifacts, 'records':[{'source_type':'internal_authored', 'source_reference':'workspace:docs/calendar_sft_v12_57/provenance.json; four docs/calendar_order_*_manual.json registers', 'rights_evidence':'Approved frozen project data plus individually handwritten fictional conversations. No external datasets, private records or model predictions.', 'permits_model_training':True, 'permits_derivative_weight_distribution':True, 'contains_personal_data':False}]})
        verify_dataset_provenance(OUTPUT / 'provenance.json', OUTPUT / 'train.jsonl', OUTPUT / 'validation.jsonl')
    print(json.dumps({k:coverage[k] for k in ('interval_train_after','new_counts','total_rows')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
