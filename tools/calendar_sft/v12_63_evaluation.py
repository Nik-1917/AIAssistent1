"""Shared scoring for frozen development and final V12.63 inference probes."""
from __future__ import annotations
from collections import Counter
import json
import re
from audit_v12_62_reply_speech import scan
from evaluate_v12_62_gguf import grade as prior_grade
from prepare_v12_63 import answer

def case_from_row(row, key, suite):
    expected = answer(row)
    p = expected['params']
    clocks = []
    if expected['intent'] == 'calendar_add' and row.get('category','').startswith('v12_63_'):
        clocks = [p[k][-5:] for k in ('starts_at','ends_at','time') if k in p]
    return {'id': key, 'suite': suite, 'system': row['messages'][0]['content'], 'user': row['messages'][1]['content'],
            'expected': expected, 'clocks': clocks, 'clock_phrases': [], 'prompt_format': 'locked_tokenizer'}

def grade(case, raw):
    result = prior_grade(case, raw)
    p = case['expected']['params']
    if case['expected']['intent'] == 'calendar_add' and not any(k in p for k in ('starts_at','time','ends_at')):
        reply = result['actual'].get('reply', '')
        reply = re.sub(re.escape(p.get('title','__NO_TITLE__')), 'EVENT', reply, count=1, flags=re.I)
        if scan(reply):
            result['passed'] = False
            result['reply_clock_correct'] = False
            result['reply_error'] = 'unknown time invented in reply'
    return result

def summary(results):
    groups = {}
    for group in sorted({r['suite'] for r in results}):
        items = [r for r in results if r['suite'] == group]
        groups[group] = {'total': len(items), **{k: sum(r[k] is True for r in items) for k in (
            'passed','json_valid','contract_valid','params_exact','params_case_insensitive','temporal_params_match','reply_clock_correct')}}
    return {'total': len(results), 'groups': groups,
            'failed_ids': [r['case_id'] for r in results if not r['passed']]}
