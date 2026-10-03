"""Qwen3.5 calendar training with bounded, reproducible AMP batch retries."""
from __future__ import annotations
import argparse
from collections import Counter
import importlib.metadata
import json
import math
import os
from pathlib import Path
import random
import shutil
import time

os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1',
                  TOKENIZERS_PARALLELISM='false', PYTHONDONTWRITEBYTECODE='1')
from small_models_v2_common import ROOT, WORK, DATA, NAMES, SEED, read, write, digest, now, prompt, encode_row, verify_source
from dataset_contract import load_jsonl
from dataset_provenance import verify_dataset_provenance
from v12_63_evaluation import case_from_row, grade, summary
from small_models_v2_common import selection_score, completion_loss
from amp_retry import optimizer_step_with_retries

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', choices=('qwen35_2b',), required=True)
    parser.add_argument('--mode', choices=('preflight', 'smoke', 'train'), required=True)
    parser.add_argument('--resume', type=Path)
    parser.add_argument('--micro-batch', type=int, choices=(1, 2, 4, 8, 16), default=4)
    args = parser.parse_args()
    import torch
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training, PeftModel
    from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig, get_linear_schedule_with_warmup
    torch.set_num_threads(12)
    torch.manual_seed(SEED)
    random.seed(SEED)
    lock, source = verify_source(args.model)
    out = WORK / args.model
    out.mkdir(parents=True, exist_ok=True)
    provenance = verify_dataset_provenance(DATA / 'provenance.json', DATA / 'train.jsonl', DATA / 'validation.jsonl')
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True, trust_remote_code=False)
    tokenizer.pad_token = tokenizer.eos_token
    rows = load_jsonl(DATA / 'train.jsonl')
    validation = load_jsonl(DATA / 'validation.jsonl')
    # Alternate message transport only; Russian inputs and gold answers are
    # neither generated nor duplicated. Both transports are fully preflighted.
    train_data = [encode_row(tokenizer, row, android=(i % 2 == 0)) for i, row in enumerate(rows)]
    lengths = [len(encode_row(tokenizer, row, android=android)['input_ids'])
               for row in rows + validation for android in (False, True)]
    dev_rows = read(DATA / 'generation_dev.json')
    dev_cases = [case_from_row(r, r.get('case_id', f'DEV_{i}'),
                 'clock' if r.get('category') == 'v12_63_dev' else 'legacy_' + json.loads(r['messages'][-1]['content'])['intent'])
                 for i, r in enumerate(dev_rows)]
    train_pairs = {(r['messages'][0]['content'], r['messages'][1]['content']) for r in rows}
    if any((c['system'], c['user']) in train_pairs for c in dev_cases):
        raise ValueError('Development query leaked into training')
    epochs, accumulation = 3, 16
    steps_per_epoch = math.ceil(len(rows) / accumulation)
    total_steps = epochs * steps_per_epoch
    preflight = {'source_repository': lock['repository'], 'source_revision': lock['revision'],
        'source_lock_sha256': digest(WORK / (NAMES[args.model] + '_source_lock.json')),
        'dataset_provenance': provenance, 'development_sha256': digest(DATA / 'generation_dev.json'),
        'train_rows': len(rows), 'validation_rows': len(validation), 'development_rows': len(dev_cases),
        'max_tokens_both_transports': max(lengths), 'truncated_records': 0,
        'train_intents': dict(Counter(json.loads(r['messages'][-1]['content'])['intent'] for r in rows)),
        'epochs': epochs, 'gradient_accumulation': accumulation // args.micro_batch, 'effective_batch_size':accumulation,
        'planned_optimizer_steps': total_steps, 'batch_size':args.micro_batch,
        'learning_rate': 1e-4, 'warmup_ratio': 0.03, 'lora_rank': 32, 'lora_alpha': 64,
        'lora_dropout': 0.05, 'seed': SEED, 'mask': 'assistant JSON and end-of-message only',
        'amp_initial_scale':128.0,'amp_max_retries':12,'checkpoint_interval':44,
        'amp_policy':'Retry the same accumulated batch with restored RNG after GradScaler skips an overflowing update; advance scheduler and data only after a finite update',
        'transport': 'alternating exact Android ChatML and standard ChatML, no thinking prefix',
        'selection': 'Highest macro full-pass rate across six intents; then macro exact params; then reply-clock passes; earlier wins ties',
        'selection_steps': [steps_per_epoch // 2 + i * (steps_per_epoch // 2) for i in range(epochs * 2)],
        'holdout_used_for_selection': False,
        'packages': {p: importlib.metadata.version(p) for p in ('torch','transformers','peft','bitsandbytes','accelerate','safetensors')}}
    write(out / 'preflight.json', preflight)
    if args.mode == 'preflight':
        print(json.dumps({'model': args.model, 'preflight': 'COMPLETE', 'max_tokens': max(lengths)}))
        return
    if not torch.cuda.is_available():
        raise RuntimeError('CUDA unavailable')
    if torch.cuda.get_device_capability()[0] >= 8:
        raise RuntimeError('This run is configured and verified for the local Pascal FP16 path')
    quantization = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type='nf4',
        bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=torch.float16)
    cls = AutoModelForCausalLM
    if args.model == 'qwen35_2b':
        from transformers import Qwen3_5ForCausalLM
        cls = Qwen3_5ForCausalLM
    model, loading = cls.from_pretrained(source, local_files_only=True, trust_remote_code=False,
        torch_dtype=torch.float16, quantization_config=quantization, device_map={'': 0}, attn_implementation='eager',
        output_loading_info=True)
    write(out / 'source_loading.json', {k: sorted(str(x) for x in v) if isinstance(v, (list, set, tuple)) else str(v)
                                        for k,v in loading.items()})
    if any(loading.get(k) for k in ('missing_keys', 'mismatched_keys', 'error_msgs')):
        raise ValueError('Base-model loading has missing, mismatched, or erroneous weights')
    model.config.use_cache = False
    model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True,
        gradient_checkpointing_kwargs={'use_reentrant': False})
    if args.resume:
        model = PeftModel.from_pretrained(model, args.resume / 'adapter', is_trainable=True, local_files_only=True)
    else:
        model = get_peft_model(model, LoraConfig(r=32, lora_alpha=64, lora_dropout=0.05,
            target_modules='all-linear', bias='none', task_type='CAUSAL_LM'))
    adapter_names = [n for n,p in model.named_parameters() if p.requires_grad]
    write(out / 'trainable_parameters.json', {'names': adapter_names,
        'count': sum(p.numel() for p in model.parameters() if p.requires_grad),
        'base_model_class': model.get_base_model().__class__.__name__})
    if any('visual' in n for n in adapter_names):
        raise ValueError('Vision parameters must not participate in this text-only run')
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=1e-4, weight_decay=0.0)
    scheduler = get_linear_schedule_with_warmup(optimizer, math.ceil(total_steps * 0.03), total_steps)
    scaler = torch.amp.GradScaler('cuda', init_scale=preflight['amp_initial_scale'])
    trainable = tuple(parameter for parameter in model.parameters() if parameter.requires_grad)
    device = torch.device('cuda:0')

    def batch(items):
        length=max(len(item['input_ids']) for item in items)
        result={'input_ids':torch.full((len(items),length),tokenizer.pad_token_id,dtype=torch.long,device=device),
                'attention_mask':torch.zeros((len(items),length),dtype=torch.long,device=device),
                'labels':torch.full((len(items),length),-100,dtype=torch.long,device=device)}
        for i,item in enumerate(items):
            for key,values in item.items():
                result[key][i,:len(values)]=torch.tensor(values,dtype=torch.long,device=device)
        return result

    def loss_for(items):
        inputs=batch(items)
        output=model(input_ids=inputs['input_ids'],attention_mask=inputs['attention_mask'],use_cache=False)
        return completion_loss(output.logits,inputs['labels'])

    if args.mode == 'smoke':
        model.train()
        worst=sorted(train_data,key=lambda r:len(r['input_ids']),reverse=True)[:args.micro_batch]
        torch.cuda.reset_peak_memory_stats()
        torch.cuda.synchronize()
        started=time.monotonic()
        smoke_retries=[]
        def smoke_backward():
            with torch.autocast('cuda', dtype=torch.float16):
                loss = loss_for(worst)
            if not torch.isfinite(loss):
                raise ValueError('Non-finite forward loss')
            scaler.scale(loss).backward()
            return loss.detach().item()
        for _ in range(3):
            outcome=optimizer_step_with_retries(trainable,optimizer,scaler,smoke_backward,
                on_retry=smoke_retries.append,max_retries=preflight['amp_max_retries'])
        torch.cuda.synchronize()
        write(out / f'smoke-batch{args.micro_batch}.json', {'status': 'COMPLETE', 'loss': outcome['loss'], 'gradient_norm': outcome['gradient_norm'],
            'trainable_parameters': len(adapter_names), 'max_cuda_memory_bytes': torch.cuda.max_memory_allocated(),
            'micro_batch':args.micro_batch,'max_tokens':len(worst[0]['input_ids']),'three_micro_batches_seconds':time.monotonic()-started,
            'amp_overflow_retries':smoke_retries,'amp_scale':scaler.get_scale(),
            'weights_saved': False, 'smoke_weights_used_for_training': False})
        print(args.model + ': forward/backward/optimizer smoke complete')
        return
    run_manifest = out / 'training.json'
    if run_manifest.exists() and not args.resume:
        raise ValueError('Training output already exists; explicit verified resume required')
    frozen = {str(p): digest(p) for p in (Path(__file__), ROOT/'tools/calendar_sft/small_models_common.py',
              ROOT/'tools/calendar_sft/small_models_v2_common.py',ROOT/'tools/calendar_sft/amp_retry.py',
              DATA/'train.jsonl', DATA/'validation.jsonl', DATA/'generation_dev.json')}
    evaluations, best, best_step, global_step = [], None, None, 0
    start_epoch, start_offset = 0, 0
    if args.resume:
        integrity = read(args.resume / 'checkpoint_integrity.json')
        if digest(args.resume / 'training_state.pt') != integrity['state_sha256'] or digest(args.resume / 'adapter/adapter_model.safetensors') != integrity['adapter_sha256']:
            raise ValueError('Resume checkpoint failed integrity verification')
        saved = torch.load(args.resume / 'training_state.pt', map_location='cpu', weights_only=False)
        if saved['frozen_inputs'] != frozen or saved['config'] != preflight:
            raise ValueError('Cannot resume after changing frozen inputs')
        optimizer.load_state_dict(saved['optimizer'])
        scheduler.load_state_dict(saved['scheduler'])
        scaler.load_state_dict(saved['scaler'])
        torch.set_rng_state(saved['torch_rng'])
        torch.cuda.set_rng_state_all(saved['cuda_rng'])
        random.setstate(saved['python_rng'])
        global_step, start_epoch, start_offset = saved['step'], saved['next_epoch'], saved['next_offset']
        evaluations, best, best_step = saved['evaluations'], saved['best'], saved['best_step']
    write(run_manifest, {'status':'RUNNING', 'started_at':now(), 'frozen_inputs':frozen,
        'source_lock':str(WORK / (NAMES[args.model] + '_source_lock.json')), 'config':preflight})
    started = time.monotonic()
    optimizer.zero_grad(set_to_none=True)
    model.train()

    def evaluate(step):
        nonlocal evaluations, best, best_step
        folder = out / f'checkpoint-{step:04}'
        if not (folder/'checkpoint_integrity.json').exists():
            raise ValueError('Save a recoverable checkpoint before development inference')
        predictions=folder/'development_cases'
        predictions.mkdir(exist_ok=True)
        model.eval()
        records = []
        with torch.random.fork_rng(devices=[0]), torch.inference_mode(), torch.autocast('cuda', dtype=torch.float16):
            for case in dev_cases:
                cached=predictions/(case['id']+'.json')
                if cached.exists():
                    saved=read(cached)
                    if saved['case']!=case or saved['adapter_sha256']!=read(folder/'checkpoint_integrity.json')['adapter_sha256']:
                        raise ValueError('Cached development case changed')
                    records.append(grade(case,saved['raw_output']))
                    continue
                text = prompt([{'role':'system','content':case['system']}, {'role':'user','content':case['user']}])
                ids = torch.tensor([tokenizer.encode(text, add_special_tokens=False)], device=device)
                result = model.generate(input_ids=ids, attention_mask=torch.ones_like(ids), max_new_tokens=192,
                    do_sample=False, temperature=None, top_p=None, top_k=None, repetition_penalty=1.0,
                    pad_token_id=tokenizer.eos_token_id, eos_token_id=tokenizer.eos_token_id, use_cache=True)
                raw = tokenizer.decode(result[0, ids.shape[1]:].tolist(), skip_special_tokens=False)
                if raw.endswith(tokenizer.eos_token):
                    raw = raw[:-len(tokenizer.eos_token)]
                write(cached,{'case':case,'raw_output':raw.strip(),'step':step,
                              'adapter_sha256':read(folder/'checkpoint_integrity.json')['adapter_sha256']})
                records.append(grade(case, raw.strip()))
        score = selection_score(dev_cases,records)
        report = dict(summary(records), step=step, score=score, cases=records)
        evaluations.append({k:v for k,v in report.items() if k != 'cases'})
        if best is None or tuple(score) > tuple(best):
            best, best_step = score, step
        write(folder / 'development.json', report)
        write(out / 'selection.json', {'status':'IN_PROGRESS', 'best_step':best_step, 'best_score':best,
                                      'evaluations':evaluations, 'holdout_used':False})
        model.train()
        torch.cuda.empty_cache()
        return folder

    def save_checkpoint(next_epoch,next_offset):
        folder=out/f'checkpoint-{global_step:04}'
        folder.mkdir(exist_ok=False)
        model.save_pretrained(folder/'adapter',safe_serialization=True)
        tokenizer.save_pretrained(folder/'adapter')
        torch.save({'optimizer':optimizer.state_dict(),'scheduler':scheduler.state_dict(),
            'scaler':scaler.state_dict(),'torch_rng':torch.get_rng_state(),'cuda_rng':torch.cuda.get_rng_state_all(),
            'python_rng':random.getstate(),'step':global_step,'next_epoch':next_epoch,'next_offset':next_offset,
            'frozen_inputs':frozen,'config':preflight,'evaluations':evaluations,'best':best,'best_step':best_step},folder/'training_state.pt')
        write(folder/'checkpoint_integrity.json',{'adapter_sha256':digest(folder/'adapter/adapter_model.safetensors'),
            'state_sha256':digest(folder/'training_state.pt'),'step':global_step})

    if args.resume and global_step in preflight['selection_steps']:
        evaluate(global_step)

    log_path = out / 'optimizer_steps.jsonl'
    with log_path.open('a', encoding='utf-8') as log:
        for epoch in range(start_epoch, epochs):
            order = list(range(len(train_data)))
            random.Random(SEED + epoch).shuffle(order)
            offset = start_offset if epoch == start_epoch else 0
            for first in range(offset, len(order), accumulation):
                indices = order[first:first+accumulation]
                def backward():
                    average=0.0
                    for start in range(0,len(indices),args.micro_batch):
                        micro=indices[start:start+args.micro_batch]
                        with torch.autocast('cuda', dtype=torch.float16):
                            loss = loss_for([train_data[index] for index in micro])
                        if not torch.isfinite(loss):
                            raise ValueError(f'Non-finite loss at optimizer step {global_step+1}')
                        weight=len(micro)/len(indices)
                        average += loss.detach().item()*weight
                        scaler.scale(loss*weight).backward()
                    return average
                def overflow(record):
                    with (out/'amp_overflow_retries.jsonl').open('a',encoding='utf-8') as retry_log:
                        retry_log.write(json.dumps(dict(record,step=global_step+1,epoch=epoch+1,
                            indices=indices,time=now()))+'\n')
                outcome=optimizer_step_with_retries(trainable,optimizer,scaler,backward,on_retry=overflow,
                    max_retries=preflight['amp_max_retries'])
                scheduler.step()
                optimizer.zero_grad(set_to_none=True)
                global_step += 1
                log.write(json.dumps({'step':global_step,'epoch':epoch+1,**outcome,
                    'learning_rate':scheduler.get_last_lr()[0],'seconds':time.monotonic()-started})+'\n')
                log.flush()
                if global_step % preflight['checkpoint_interval'] == 0 or global_step == total_steps:
                    next_offset = first + len(indices)
                    next_epoch = epoch
                    if next_offset == len(order):
                        next_epoch, next_offset = epoch+1, 0
                    save_checkpoint(next_epoch,next_offset)
                if global_step in preflight['selection_steps']:
                    evaluate(global_step)
    if global_step != total_steps or best_step is None:
        raise ValueError('Planned optimizer steps or development selection incomplete')
    for path, expected in frozen.items():
        if digest(path) != expected:
            raise ValueError('Frozen training input changed: '+path)
    shutil.copytree(out/f'checkpoint-{best_step:04}'/'adapter', out/'adapter')
    selected_sha = digest(out/'adapter/adapter_model.safetensors')
    write(out/'selection.json', {'status':'COMPLETE','best_step':best_step,'best_score':best,'evaluations':evaluations,
                                'holdout_used':False,'adapter_sha256':selected_sha})
    write(run_manifest, {'status':'COMPLETE','completed_at':now(),'completed_optimizer_steps':global_step,
        'training_seconds':time.monotonic()-started,'selected_step':best_step,'adapter_sha256':selected_sha,
        'max_cuda_memory_bytes':torch.cuda.max_memory_allocated(),'frozen_inputs':frozen,'config':preflight})
    print(args.model + ': training and development selection complete')

if __name__ == '__main__':
    main()
