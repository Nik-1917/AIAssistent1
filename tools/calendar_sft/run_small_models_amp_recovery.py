"""Finish Qwen3.5 after an AMP repair, retaining the completed Qwen3 release."""
from __future__ import annotations

import os
from pathlib import Path
import traceback

from run_small_models_v2 import PYTHON, NEW_PYTHON, REPORT, build_and_evaluate, report, run, transfer
from small_models_v2_common import ROOT, WORK, NAMES, digest, freeze_inputs, now, read, write


def main():
    freeze_inputs()
    first = WORK / 'qwen3_1_7b'
    if read(first / 'training.json')['status'] != 'COMPLETE':
        raise ValueError('The completed first training run must be preserved')
    artifact = read(first / 'release/gguf_manifest.json')['model']
    if digest(artifact['file']) != artifact['sha256']:
        raise ValueError('The first GGUF changed')
    evaluation = read(WORK / 'qwen3_1_7b_evaluation/report.json')
    if evaluation['execution_status'] != 'COMPLETE' or evaluation['model_sha256'] != artifact['sha256']:
        raise ValueError('The first GGUF evaluation is incomplete')
    tests = read(WORK / 'amp_retry_tests.json')
    if tests['exit_code'] != 0 or tests['executed_tests'] != 2:
        raise ValueError('CUDA AMP integration checks have not passed')
    for path, expected in tests['sources'].items():
        if digest(path) != expected:
            raise ValueError('Tested AMP source changed')
    write(WORK / 'workflow.json', {'status':'RUNNING','started_at':now(),'owner_pid':os.getpid(),
        'driver':str(Path(__file__).resolve()),'first_model':'TRAINED_BUILT_EVALUATED',
        'preserved_failed_attempt':str(WORK / 'qwen35_2b_failed_step0045')})
    run('qwen35_2b_amp_smoke', NEW_PYTHON, 'train_qwen35_memory_guard.py',
        '--model','qwen35_2b','--mode','smoke','--micro-batch','4')
    smoke = read(WORK / 'qwen35_2b/smoke-batch4.json')
    if smoke['status'] != 'COMPLETE' or smoke['weights_saved']:
        raise ValueError('A fresh full-size GPU smoke must pass before training')
    run('qwen35_2b_amp_train', NEW_PYTHON, 'train_qwen35_memory_guard.py',
        '--model','qwen35_2b','--mode','train','--micro-batch','4')
    build_and_evaluate('qwen35_2b')
    deliveries, error = {}, None
    try:
        for key in NAMES:
            deliveries[key] = transfer(key)
    except Exception as exc:
        error = str(exc)
    result = report(deliveries, error)
    run('release_audit', PYTHON, 'audit_small_models_release.py')
    write(WORK / 'workflow.json', {'status':result['execution_status'],'completed_at':now(),
        'quality_status':result['quality_status'],'report':str(REPORT)})
    print(result['execution_status'])


if __name__ == '__main__':
    try:
        main()
    except Exception:
        write(WORK / 'amp_recovery_error.json', {'status':'FAILED','time':now(),'traceback':traceback.format_exc()})
        raise
