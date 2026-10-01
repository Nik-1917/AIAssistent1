"""Launch the authorized offline V12.63 training and preserve exact evidence."""
from __future__ import annotations
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import traceback
from prepare_v12_63 import ROOT, OUTPUT, read, write, digest, assemble
from dataset_provenance import verify_dataset_provenance

PYTHON = ROOT / 'build/calendar_sft_local_gpu_venv/Scripts/python.exe'
BASE = ROOT / 'build/calendar_sft_models/Qwen3-4B-Instruct-2507/cdbee75f17c01a7cc42f958dc650907174af0554'
INITIAL = ROOT / 'build/calendar_sft_qwen3_v12_62_epoch1_20260929/adapter'
RUN = ROOT / 'build/calendar_sft_qwen3_v12_63_epoch1_20261001'
LAUNCH = ROOT / 'build/calendar_sft_qwen3_v12_63_launch_20261001'

def now():
    return datetime.now(timezone.utc).isoformat()

def main():
    if RUN.exists() or LAUNCH.exists():
        raise ValueError('refusing to overwrite an existing training run')
    LAUNCH.mkdir()
    try:
        write(LAUNCH / 'state.json', {'stage':'PREFLIGHT', 'updated_at':now()})
        prepared = assemble(check_only=True)
        provenance = verify_dataset_provenance(OUTPUT/'provenance.json', OUTPUT/'train.jsonl', OUTPUT/'validation.jsonl')
        tokens = read(OUTPUT/'tokenization_report.json')
        if tokens['status'] != 'PASS' or tokens['total_rows'] != 6755 or tokens['truncated_rows'] != 0:
            raise ValueError('full token audit required')
        for name, artifact in tokens['files'].items():
            if digest(OUTPUT/name) != artifact['sha256']:
                raise ValueError(f'tokenization snapshot changed: {name}')
        if digest(INITIAL/'adapter_model.safetensors') != 'e61548f6ee00550149d517036644fa769e50b9c9b7c610d853d261405ba646a6':
            raise ValueError('initial adapter differs')
        paths = list(OUTPUT.iterdir()) + list((ROOT/'tools/calendar_sft').glob('*v12_63*.py')) + list((ROOT/'docs/calendar_v12_63_manual').iterdir())
        paths += [ROOT/'docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md', ROOT/'docs/CALENDAR_ASSISTANT_V12_63_INPUT_CLOCK.md',
                  INITIAL/'adapter_model.safetensors', INITIAL/'adapter_config.json']
        hashes = {str(p):digest(p) for p in paths if p.is_file()}
        command = [PYTHON,'-X','utf8','-B',ROOT/'tools/calendar_sft/train_qlora_v12_63.py',
                   '--model-dir',BASE,'--initial-adapter',INITIAL,'--generation-dev-file',OUTPUT/'generation_dev.json',
                   '--train-file',OUTPUT/'train.jsonl','--validation-file',OUTPUT/'validation.jsonl',
                   '--dataset-provenance',OUTPUT/'provenance.json','--output-dir',RUN,
                   '--max-seq-length',str(tokens['max_seq_length']),'--epochs','1',
                   '--per-device-batch-size','1','--gradient-accumulation-steps','16','--learning-rate','0.00005',
                   '--seed','20260825','--lora-rank','16','--lora-alpha','32','--lora-dropout','0.05']
        write(LAUNCH/'launch.json', {'version':'v12.63','started_at':now(),'command':[str(x) for x in command],
              'protected_sha256':hashes,'provenance':provenance,'rows':prepared['rows'],'contract_version':'v12.57',
              'planned_optimizer_steps':352,'authorization':'Owner approved the revised input-clock plan; global PM default excluded'})
        env = os.environ.copy()
        env.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', PYTHONDONTWRITEBYTECODE='1', PYTHONIOENCODING='utf-8', TOKENIZERS_PARALLELISM='false')
        write(LAUNCH/'state.json', {'stage':'TRAINING_WITH_DEVELOPMENT_SELECTION','updated_at':now()})
        with (LAUNCH/'train.stdout.log').open('wb') as out, (LAUNCH/'train.stderr.log').open('wb') as err:
            code = subprocess.run([str(x) for x in command],cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=out,stderr=err,
                                  creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0)).returncode
        write(LAUNCH/'exit.json', {'status':'COMPLETE' if code==0 else 'FAILED','exit_code':code,'finished_at':now()})
        if code:
            raise RuntimeError(f'trainer exit code {code}; see preserved logs')
        result = read(RUN/'run_manifest.json')['result']
        if result['completed_optimizer_steps'] != 352:
            raise ValueError('incomplete optimizer steps')
        for path, sha in hashes.items():
            if digest(Path(path)) != sha:
                raise ValueError(f'protected input changed during training: {path}')
        write(LAUNCH/'state.json', {'stage':'READY_FOR_BUILD','updated_at':now(),'selected_step':result['selected_step']})
    except Exception:
        write(LAUNCH/'state.json', {'stage':'FAILED','updated_at':now(),'error':traceback.format_exc()})
        raise

if __name__ == '__main__':
    main()
