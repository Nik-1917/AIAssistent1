"""Comparable raw GGUF regression probes, including actual Android prompts."""
from __future__ import annotations
import argparse
from copy import deepcopy
import json
import re
import subprocess
import time
from small_models_common import ROOT, WORK, NAMES, read, write, digest, now, prompt
from evaluate_v12_63_gguf import request, BINARY
from v12_63_evaluation import grade, summary

OLD = ROOT/'build/calendar_sft_v12_63_gguf_eval_20261001'
BASELINE = ROOT/'build/calendar_sft_qwen3_v12_63_gguf_20261001/calendar-assistant-v12.63-Q4_K_M.gguf'

def prepare():
    target=WORK/'evaluation_inputs'
    target.mkdir(exist_ok=True)
    (target/'prompts').mkdir(exist_ok=True)
    original=read(OLD/'inputs.json')
    cases=deepcopy(original['cases'])
    for case in cases:
        source=OLD/'prompts'/(case['id']+'.txt')
        if digest(source)!=case['prompt_sha256']:
            raise ValueError('Original comparison prompt changed')
        text=source.read_text(encoding='utf-8')
        path=target/'prompts'/(case['id']+'.txt')
        if path.exists() and path.read_text(encoding='utf-8')!=text:
            raise ValueError('Frozen evaluation prompt changed')
        path.write_text(text,encoding='utf-8')
        case['suite']=case['suite'].replace('new_blind','known_heldout')
        case['prompt_sha256']=digest(path)
    previous_android={c['id'].removeprefix('ANDROID_') for c in cases if c['id'].startswith('ANDROID_')}
    for source in original['cases']:
        if source['suite']!='new_blind_input_clock' or source['id'] in previous_android:
            continue
        case=deepcopy(source)
        matched=re.fullmatch(r'Сегодня дата и время:(\S+) \((.+)\) (\S+) Europe/Samara ответ JSON',case['system'])
        if not matched:raise ValueError('Unknown system context format')
        case.update(id='ANDROID_'+case['id'],suite='additional_android_clock',prompt_format='android_chatml',
            system=f'cегодня {matched[1]} {matched[3]} день недели {matched[2]} ответ JSON')
        text=prompt([{'role':'system','content':case['system']},{'role':'user','content':case['user']}])
        path=target/'prompts'/(case['id']+'.txt')
        if path.exists() and path.read_text(encoding='utf-8')!=text:raise ValueError('Frozen Android prompt changed')
        path.write_text(text,encoding='utf-8')
        case['prompt_sha256']=digest(path)
        cases.append(case)
    if len(cases)!=297 or len({c['id'] for c in cases})!=297:
        raise ValueError('Unexpected comparison case count')
    settings=original['decoding']
    result={'cases':cases,'decoding':settings,'context':512,'cpu_threads':12,'gpu_layers':0,
        'origin_inputs_sha256':digest(OLD/'inputs.json'),'known_primary_cases':231,'additional_android_cases':66,
        'new_blind_holdout':False,'grammar_constraint':False,'response_repair':False,
        'server_sha256':digest(BINARY),'grader_sha256':digest(ROOT/'tools/calendar_sft/v12_63_evaluation.py')}
    path=target/'inputs.json'
    if path.exists() and read(path)!=result:raise ValueError('Evaluation inputs changed')
    write(path,result)
    return target,result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--model',choices=(*NAMES,'baseline'),required=True)
    p.add_argument('--prepare-only',action='store_true')
    p.add_argument('--port',type=int,default=8764)
    args=p.parse_args()
    target,inputs=prepare()
    if args.prepare_only:return
    model=BASELINE if args.model=='baseline' else next((WORK/args.model/'release').glob('*-Q4_K_M.gguf'))
    model_hash=digest(model)
    if read(model.parent/'gguf_manifest.json')['model']['sha256']!=model_hash:
        raise ValueError('Release artifact failed integrity check')
    out=WORK/(args.model+'_evaluation')
    (out/'cases').mkdir(parents=True,exist_ok=True)
    binding={'model':str(model),'model_sha256':model_hash,'inputs_sha256':digest(target/'inputs.json'),
             'evaluator_sha256':digest(__file__)}
    if (out/'binding.json').exists() and read(out/'binding.json')!=binding:
        raise ValueError('Cannot reuse predictions from different inputs')
    write(out/'binding.json',binding)
    command=[str(BINARY),'--offline','-m',str(model),'-c','512','-np','1','-t','12','-b','512','-ub','512','-ngl','0',
        '--host','127.0.0.1','--port',str(args.port),'--no-webui','--no-context-shift','--cache-reuse','0']
    with (out/'server.stdout.log').open('ab') as stdout,(out/'server.stderr.log').open('ab') as stderr:
        server=subprocess.Popen(command,cwd=ROOT,stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,
                                creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            for _ in range(240):
                if server.poll() is not None:raise RuntimeError('GGUF server exited; inspect server log')
                try:
                    if request(args.port,'/health').get('status')=='ok':break
                except Exception:pass
                time.sleep(0.5)
            else:raise TimeoutError('GGUF server startup timed out')
            for case in inputs['cases']:
                path=out/'cases'/(case['id']+'.json')
                if path.exists():
                    saved=read(path)
                    if saved['model_sha256']!=model_hash or saved['prompt_sha256']!=case['prompt_sha256']:
                        raise ValueError('Cached raw response has wrong binding')
                    continue
                text=(target/'prompts'/(case['id']+'.txt')).read_text(encoding='utf-8')
                started=time.monotonic()
                raw=request(args.port,'/completion',dict(inputs['decoding'],prompt=text))
                if not isinstance(raw.get('content'),str):raise ValueError('Completion is not text')
                write(path,{'id':case['id'],'model_sha256':model_hash,'prompt_sha256':case['prompt_sha256'],
                    'raw_response':raw,'output':raw['content'].strip(),'runtime_seconds':time.monotonic()-started})
        finally:
            server.terminate()
            try:server.wait(timeout=15)
            except subprocess.TimeoutExpired:server.kill();server.wait(timeout=15)
    records=[]
    for case in inputs['cases']:
        raw=read(out/'cases'/(case['id']+'.json'))
        if raw['output']!=raw['raw_response']['content'].strip():raise ValueError('Raw response was modified')
        records.append(grade(case,raw['output']))
    write(out/'report.json',dict(summary(records),execution_status='COMPLETE',completed_at=now(),
        quality_status='PASS' if all(r['passed'] for r in records) else 'FAILURES_FOUND',
        model_sha256=model_hash,cases=records,primary=summary(records[:231]),android_extra=summary(records[231:]),
        physical_device_inference='NOT_RUN',independent_new_blind_holdout=False))
    print(args.model+': regression and Android-prompt desktop evaluation complete')

if __name__=='__main__':main()
