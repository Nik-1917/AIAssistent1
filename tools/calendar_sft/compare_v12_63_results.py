"""Compare identical frozen GGUF probes and expose date/clock errors separately."""
from __future__ import annotations
from collections import Counter, defaultdict
import json
from pathlib import Path
from prepare_v12_63 import ROOT, read, write, norm

OLD=ROOT/'build/calendar_sft_v12_63_compare_baseline_20261001'
NEW=ROOT/'build/calendar_sft_v12_63_gguf_eval_20261001'
DEST=ROOT/'build/calendar_sft_v12_63_comparison_20261001'

def field_metrics(rows):
    metrics=defaultdict(Counter)
    for row in rows:
        expected, actual=row['expected'],row['actual']
        p=expected['params'];ap=actual.get('params',{})
        ap=ap if isinstance(ap,dict) else {}
        intent=actual.get('intent')==expected['intent']
        for key in ('starts_at','ends_at'):
            if key not in p:continue
            for label,slice_ in (('date',slice(0,10)),('clock',slice(-5,None))):
                metrics[key+'_'+label]['total']+=1
                metrics[key+'_'+label]['correct']+=bool(intent and isinstance(ap.get(key),str) and ap[key][slice_]==p[key][slice_])
        for key in ('duration_min','value','date','time'):
            if key in p:
                metrics[key]['total']+=1
                metrics[key]['correct']+=bool(intent and ap.get(key)==p[key])
        if expected['intent']=='calendar_add' and 'title' in p:
            metrics['title_in_reply']['total']+=1
            reply=actual.get('reply','')
            metrics['title_in_reply']['correct']+=bool(intent and isinstance(reply,str) and norm(p['title']) in norm(reply))
    return {k:dict(v) for k,v in metrics.items()}

def main():
    old,new=read(OLD/'report.json'),read(NEW/'report.json')
    oi,ni=read(OLD/'inputs.json'),read(NEW/'inputs.json')
    if oi['cases']!=ni['cases'] or oi['decoding']!=ni['decoding']:
        raise ValueError('comparison requires identical prompts, references and decoding')
    before={r['case_id']:r for r in old['cases']}
    after={r['case_id']:r for r in new['cases']}
    if before.keys()!=after.keys():raise ValueError('case sets differ')
    changes=[]
    for key in before:
        a,b=before[key],after[key]
        if a['passed']!=b['passed'] or a['params_exact']!=b['params_exact'] or a['reply_clock_correct']!=b['reply_clock_correct']:
            changes.append({'id':key,'suite':a['suite'],'before_passed':a['passed'],'after_passed':b['passed'],
                            'before_params_exact':a['params_exact'],'after_params_exact':b['params_exact'],
                            'before_reply_clock_correct':a['reply_clock_correct'],'after_reply_clock_correct':b['reply_clock_correct']})
    groups={}
    for suite in old['groups']:
        a=[r for r in old['cases'] if r['suite']==suite];b=[r for r in new['cases'] if r['suite']==suite]
        groups[suite]={'v12.62':old['groups'][suite],'v12.63':new['groups'][suite],
                       'fields_v12.62':field_metrics(a),'fields_v12.63':field_metrics(b)}
    report={'status':'COMPLETE','total':len(before),'identical_cases_and_decoding':True,'baseline_model_sha256':old['model_sha256'],
            'candidate_model_sha256':new['model_sha256'],'groups':groups,
            'passed_before':sum(r['passed'] for r in old['cases']),'passed_after':sum(r['passed'] for r in new['cases']),
            'params_exact_before':sum(r['params_exact'] for r in old['cases']),'params_exact_after':sum(r['params_exact'] for r in new['cases']),
            'improved_ids':[k for k in before if not before[k]['passed'] and after[k]['passed']],
            'regressed_ids':[k for k in before if before[k]['passed'] and not after[k]['passed']],
            'remaining_failed_ids':new['failed_ids'],'changes':changes,
            'holdout_policy':'New blind cases were frozen before optimization and not used for checkpoint selection. Old probes are known regression. Full pass uses the existing case-insensitive string comparison; exact params are reported separately.'}
    write(DEST/'comparison.json',report)
    print({k:report[k] for k in ('status','total','passed_before','passed_after','params_exact_before','params_exact_after','regressed_ids')})

if __name__=='__main__':main()
