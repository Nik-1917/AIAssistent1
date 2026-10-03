"""Merge a completed compact adapter and build a verified Q4_K_M GGUF."""
from __future__ import annotations
import argparse
from collections import Counter
import gc
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from small_models_v2_common import ROOT, WORK, NAMES, read, write, digest, now, verify_source

SOURCE = ROOT/'build/llama.cpp-v0.3.0'
BIN = ROOT/'build/llama-b10621-bin-win-cpu-x64'
CALENDAR_TEMPLATE = "{% for message in messages %}{{ '<|im_start|>' + message['role'] + '\\n' + message['content'] + '\\n<|im_end|>\\n' }}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\\n' }}{% endif %}"

def inspect(path, arch, file_type):
    sys.path.insert(0, str(SOURCE/'gguf-py'))
    from gguf import GGUFReader
    import numpy as np
    reader = GGUFReader(path)
    fields = reader.fields
    if fields['general.architecture'].contents() != arch or fields['general.file_type'].contents() != file_type:
        raise ValueError('Wrong GGUF architecture or quantization')
    names = set()
    for tensor in reader.tensors:
        if tensor.name in names or 'lora_' in tensor.name or 'visual' in tensor.name:
            raise ValueError('Unexpected GGUF tensor')
        names.add(tensor.name)
        if tensor.data_offset + tensor.n_bytes > path.stat().st_size:
            raise ValueError('Truncated tensor data')
        if tensor.data.dtype.kind == 'f' and not np.isfinite(tensor.data).all():
            raise ValueError('Non-finite GGUF tensor')
    return {'file':str(path), 'bytes':path.stat().st_size, 'sha256':digest(path), 'architecture':arch,
        'file_type':file_type, 'tensor_count':len(names), 'tensor_types':dict(Counter(t.tensor_type.name for t in reader.tensors)),
        'chat_template_sha256':__import__('hashlib').sha256(fields['tokenizer.chat_template'].contents().encode()).hexdigest()}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--model', choices=tuple(NAMES), required=True)
    args=p.parse_args()
    os.environ.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',TOKENIZERS_PARALLELISM='false')
    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from safetensors import safe_open
    from build_v12_2_gguf import verify_tools
    tools=verify_tools()
    run=WORK/args.model
    training=read(run/'training.json')
    if training['status']!='COMPLETE' or training['completed_optimizer_steps']!=training['config']['planned_optimizer_steps']:
        raise ValueError('Training is incomplete')
    adapter=run/'adapter'
    if digest(adapter/'adapter_model.safetensors')!=training['adapter_sha256']:
        raise ValueError('Selected adapter changed')
    for path, expected in training['frozen_inputs'].items():
        if digest(path)!=expected:
            raise ValueError('Training inputs changed')
    lock, source=verify_source(args.model)
    build=run/'build'
    merged=build/'merged'
    release=run/'release'
    if build.exists() or release.exists():
        raise ValueError('Refusing to overwrite previous build')
    build.mkdir()
    release.mkdir()
    cls=AutoModelForCausalLM
    if args.model=='qwen35_2b':
        from transformers import Qwen3_5ForCausalLM
        cls=Qwen3_5ForCausalLM
    torch.set_num_threads(12)
    model, loading=cls.from_pretrained(source,local_files_only=True,trust_remote_code=False,
        torch_dtype=torch.float32,device_map={'':'cpu'},low_cpu_mem_usage=True,output_loading_info=True)
    if any(loading.get(k) for k in ('missing_keys','mismatched_keys','error_msgs')):
        raise ValueError('Original weights did not load completely')
    model=PeftModel.from_pretrained(model,adapter,local_files_only=True).merge_and_unload(safe_merge=True)
    model.to(dtype=torch.bfloat16)
    model.save_pretrained(merged,safe_serialization=True,max_shard_size='4GB')
    tokenizer=AutoTokenizer.from_pretrained(source,local_files_only=True,trust_remote_code=False)
    original_template=tokenizer.chat_template
    tokenizer.chat_template=CALENDAR_TEMPLATE
    from small_models_v2_common import prompt
    messages=[{'role':'system','content':'Контекст'},{'role':'user','content':'Запрос'}]
    if tokenizer.apply_chat_template(messages,tokenize=False,add_generation_prompt=True)!=prompt(messages):
        raise ValueError('Exported template differs from Android transport')
    tokenizer.save_pretrained(merged)
    (build/'original_chat_template.jinja').write_text(original_template,encoding='utf-8')
    del model
    gc.collect()
    torch.cuda.empty_cache()
    tensor_names=set()
    for shard in merged.glob('*.safetensors'):
        with safe_open(shard,framework='pt',device='cpu') as handle:
            for name in handle.keys():
                if name in tensor_names or 'lora_' in name or 'visual' in name:
                    raise ValueError('Unexpected merged tensor')
                tensor_names.add(name)
                flat=handle.get_tensor(name).reshape(-1)
                for first in range(0,flat.numel(),1048576):
                    if not torch.isfinite(flat[first:first+1048576]).all():
                        raise ValueError('Non-finite merged tensor')
    write(build/'merge.json',{'source':lock['repository'],'revision':lock['revision'],
        'adapter_sha256':training['adapter_sha256'],'tensor_count':len(tensor_names),'all_finite':True,
        'files':{p.name:digest(p) for p in merged.iterdir() if p.is_file()},
        'template_change':'Direct calendar JSON in the existing Android ChatML transport; no thinking prefix'})
    prefix='calendar-assistant-v12.63-'+NAMES[args.model]
    f16=build/(prefix+'-F16.gguf')
    final=release/(prefix+'-Q4_K_M.gguf')
    steps=[('convert',[sys.executable,'-B',str(SOURCE/'convert_hf_to_gguf.py'),str(merged),'--outfile',str(f16),
                '--outtype','f16','--model-name','Calendar Assistant V12.63 '+NAMES[args.model]]),
           ('quantize',[str(BIN/'llama-quantize.exe'),str(f16),str(final),'Q4_K_M','12'])]
    for name,cmd in steps:
        start=time.monotonic()
        with (build/(name+'.stdout.log')).open('wb') as stdout,(build/(name+'.stderr.log')).open('wb') as stderr:
            result=subprocess.run(cmd,stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,cwd=ROOT,
                                  creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        write(build/(name+'.json'),{'command':cmd,'exit_code':result.returncode,'seconds':time.monotonic()-start})
        if result.returncode:
            raise RuntimeError(name+' failed; see build logs')
    arch='qwen3' if args.model=='qwen3_1_7b' else 'qwen35'
    intermediate=inspect(f16,arch,1)
    artifact=inspect(final,arch,15)
    if intermediate['tensor_count']!=artifact['tensor_count']:
        raise ValueError('Quantization changed tensor count')
    shutil.copy2(source/'LICENSE',release/'LICENSE')
    for notice in source.glob('NOTICE*'):
        if notice.is_file():shutil.copy2(notice,release/notice.name)
    (release/'MODIFICATIONS.txt').write_text('Calendar Assistant V12.63 '+NAMES[args.model]+'\n'
        +'Derived from '+lock['repository']+' at '+lock['revision']+'.\n'
        +'New calendar LoRA trained on approved V12.63 data; selected on development queries, merged into original weights, '
        +'exported with direct-JSON Android ChatML template, converted to F16 and quantized to Q4_K_M.\n'
        +'Text-only language model. Source license: Apache-2.0, see LICENSE.\n',encoding='utf-8')
    (release/'SHA256SUMS.txt').write_text(artifact['sha256']+'  '+final.name+'\n',encoding='ascii')
    write(release/'gguf_manifest.json',{'status':'COMPLETE','completed_at':now(),'model':artifact,
        'intermediate':intermediate,'adapter_sha256':training['adapter_sha256'],'selected_step':training['selected_step'],
        'source_repository':lock['repository'],'source_revision':lock['revision'],'tools':tools,
        'dataset_version':'v12.63','inference_tests':'NOT_RUN','physical_device_inference':'NOT_RUN'})
    print(args.model+': Q4_K_M build complete')

if __name__=='__main__':main()
