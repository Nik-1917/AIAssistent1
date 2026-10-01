"""Frozen desktop GGUF probes using a local, single-slot completion server."""
from __future__ import annotations
import argparse
from collections import Counter
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
from prepare_v12_63 import ROOT, OUTPUT, read, digest
from evaluate_v12_62_gguf import cases as legacy_cases, write
from v12_63_evaluation import case_from_row, grade, summary

BASE = ROOT / 'build/calendar_sft_models/Qwen3-4B-Instruct-2507/cdbee75f17c01a7cc42f958dc650907174af0554'
BINARY = ROOT / 'build/llama-b10621-bin-win-cpu-x64/llama-server.exe'

def cases(suite):
    if suite == 'grid':
        rows = [json.loads(x) for x in (OUTPUT/'train.jsonl').read_bytes().splitlines()]
        return [case_from_row(r, r['case_id'], 'seen_training_relative_grid') for r in rows if r['category']=='v12_63_relative_clock']
    blind = [json.loads(x) for x in (OUTPUT/'input_clock_holdout.jsonl').read_bytes().splitlines()]
    result = [case_from_row(r, r['case_id'], 'new_blind_input_clock') for r in blind]
    for key in ('V1263H70','V1263H31','V1263H50','V1263H68','V1263H64','V1263H63'):
        row = deepcopy(next(r for r in result if r['id']==key))
        m = re.fullmatch(r'Сегодня дата и время:(\S+) \((.+)\) (\S+) Europe/Samara ответ JSON', row['system'])
        row.update(id='ANDROID_'+key, suite='new_blind_android_prompt',
                   system=f'cегодня {m[1]} {m[3]} день недели {m[2]} ответ JSON', prompt_format='android_chatml')
        result.append(row)
    for row in legacy_cases():
        row['id'] = 'OLD_' + row['id']
        row['suite'] = 'known_regression_' + row['suite']
        result.append(row)
    seen = {(r['system'],r['user']) for r in result}
    for i,line in enumerate((OUTPUT/'regression_holdout.jsonl').read_bytes().splitlines()):
        row = json.loads(line)
        pair=(row['messages'][0]['content'],row['messages'][1]['content'])
        if pair not in seen:
            result.append(case_from_row(row,'REG_'+row.get('case_id',str(i)),'legacy_regression'))
    return result

def request(port, path, payload=None):
    data = None if payload is None else json.dumps(payload,ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}',data=data,headers={'Content-Type':'application/json'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req,timeout=600) as response:
        return json.load(response)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--suite',choices=('primary','grid'),default='primary')
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--port',type=int,default=8763)
    args=parser.parse_args()
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    for name in ('cases','prompts'):(out/name).mkdir(exist_ok=True)
    os.environ.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',TOKENIZERS_PARALLELISM='false')
    from transformers import AutoTokenizer
    tokenizer=AutoTokenizer.from_pretrained(BASE,local_files_only=True,trust_remote_code=False)
    template_sha=hashlib.sha256(tokenizer.chat_template.encode()).hexdigest()
    if template_sha!='64f85b198065d0fba2a81f37e10ed68161ce2c19a754c7100e67e0ca2ee9c326':
        raise ValueError('tokenizer template differs')
    rows=cases(args.suite)
    if len({r['id'] for r in rows})!=len(rows):raise ValueError('duplicate ID')
    for row in rows:
        messages=[{'role':'system','content':row['system']},{'role':'user','content':row['user']}]
        prompt=(''.join('<|im_start|>'+m['role']+'\n'+m['content']+'\n<|im_end|>\n' for m in messages)+'<|im_start|>assistant\n') if row['prompt_format']=='android_chatml' else tokenizer.apply_chat_template(messages,tokenize=False,add_generation_prompt=True)
        path=out/'prompts'/(row['id']+'.txt')
        if path.exists() and path.read_text(encoding='utf-8')!=prompt:raise ValueError('frozen prompt differs')
        path.write_text(prompt,encoding='utf-8')
        row['prompt_sha256']=digest(path)
    if args.prepare_only:
        write(out/'prepared_cases.json',rows)
        print({'prepared':len(rows),'groups':dict(Counter(r['suite'] for r in rows))});return
    model=args.model.resolve();model_sha=digest(model)
    manifest=read(model.parent/'gguf_manifest.json')
    if manifest['status']!='COMPLETE' or manifest['model']['sha256']!=model_sha:raise ValueError('GGUF differs from completed build')
    decoding={'n_predict':192,'temperature':0,'top_k':1,'top_p':1,'min_p':0,'seed':20260825,'repeat_penalty':1,'cache_prompt':False,'stream':False}
    inputs={'model':str(model),'model_sha256':model_sha,'cases':rows,'decoding':decoding,'context':512,'cpu_threads':12,'gpu_layers':0,'grammar_constraint':False,
            'chat_template_sha256':template_sha,'tools':{str(p):digest(p) for p in (Path(__file__),ROOT/'tools/calendar_sft/v12_63_evaluation.py',ROOT/'tools/calendar_sft/evaluate_v12_62_gguf.py',ROOT/'tools/calendar_sft/audit_v12_62_reply_speech.py',BINARY)}}
    if (out/'inputs.json').exists() and read(out/'inputs.json')!=inputs:raise ValueError('frozen evaluation inputs changed')
    write(out/'inputs.json',inputs)
    command=[str(BINARY),'--offline','-m',str(model),'-c','512','-np','1','-t','12','-b','512','-ub','512','-ngl','0',
             '--host','127.0.0.1','--port',str(args.port),'--no-webui','--no-context-shift','--cache-reuse','0']
    with (out/'server.stdout.log').open('ab') as stdout,(out/'server.stderr.log').open('ab') as stderr:
        server=subprocess.Popen(command,cwd=ROOT,stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            for _ in range(240):
                if server.poll() is not None:raise RuntimeError('completion server exited; see logs')
                try:
                    if request(args.port,'/health').get('status')=='ok':break
                except Exception:pass
                time.sleep(0.5)
            else:raise TimeoutError('server startup timed out')
            for index,row in enumerate(rows,1):
                path=out/'cases'/(row['id']+'.json')
                if path.exists():
                    saved=read(path)
                    if saved['model_sha256']!=model_sha or saved['prompt_sha256']!=row['prompt_sha256']:raise ValueError('cached prediction differs')
                    continue
                prompt=(out/'prompts'/(row['id']+'.txt')).read_text(encoding='utf-8')
                started=time.monotonic()
                response=request(args.port,'/completion',dict(decoding,prompt=prompt))
                raw=response['content']
                if not isinstance(raw,str):raise ValueError('completion is not text')
                write(path,{'id':row['id'],'model_sha256':model_sha,'prompt_sha256':row['prompt_sha256'],'output':raw.strip(),
                            'raw_response':response,'runtime_seconds':time.monotonic()-started})
                write(out/'state.json',{'status':'RUNNING','completed':index,'total':len(rows),'last_case':row['id'],'server_pid':server.pid})
        finally:
            server.terminate()
            try:server.wait(timeout=15)
            except subprocess.TimeoutExpired:server.kill();server.wait(timeout=15)
    results=[grade(row,read(out/'cases'/(row['id']+'.json'))['output']) for row in rows]
    report=dict(summary(results),execution_status='COMPLETE',quality_status='PASS' if all(r['passed'] for r in results) else 'FAILURES_FOUND',
                model=str(model),model_sha256=model_sha,completed_at=datetime.now(timezone.utc).isoformat(),cases=results,
                limitations='Desktop CPU GGUF inference. Grid suite uses training prompts and is a memorization check, not an independent holdout. Android inference on the physical phone is separate.')
    write(out/'report.json',report)
    write(out/'state.json',{'status':'COMPLETE','quality_status':report['quality_status'],'completed':len(rows),'total':len(rows)})
    print({k:v for k,v in report.items() if k not in ('cases','failed_ids')})

if __name__=='__main__':main()
