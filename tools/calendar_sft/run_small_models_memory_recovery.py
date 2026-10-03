"""Continue from checkpoint 440 after preserving the slow uncapped attempt."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import shutil
import time
import traceback

from run_small_models_v2 import PYTHON, NEW_PYTHON, REPORT, build_and_evaluate, report, run, transfer
from small_models_v2_common import ROOT, WORK, NAMES, digest, freeze_inputs, now, read, write


def stop_after_verified_checkpoint():
    import psutil
    folder = WORK / 'qwen3_1_7b'
    checkpoint = folder / 'checkpoint-0440'
    history = WORK / 'before_cuda_memory_guard'
    if history.exists():
        raise ValueError('This deliberate recovery has already been attempted')
    active = read(WORK / 'jobs/qwen3_1_7b_train.running.json')
    owner = read(WORK / 'workflow.json')
    trainer = str(ROOT / 'tools/calendar_sft/train_small_model_v2.py')
    driver = str(ROOT / 'tools/calendar_sft/run_small_models_v2.py')
    process = psutil.Process(active['pid'])
    owner_process = psutil.Process(owner['owner_pid'])
    if not any(Path(arg).resolve() == Path(driver).resolve() for arg in owner_process.cmdline() if arg.endswith('.py')):
        raise ValueError('Original workflow process identity changed')
    while not (checkpoint / 'checkpoint_integrity.json').exists():
        if not process.is_running() or (WORK / 'workflow_error.json').exists():
            raise ValueError('Original run ended before the intended checkpoint')
        time.sleep(20)
    integrity = read(checkpoint / 'checkpoint_integrity.json')
    for path, field in ((checkpoint / 'adapter/adapter_model.safetensors', 'adapter_sha256'),
                        (checkpoint / 'training_state.pt', 'state_sha256')):
        if digest(path) != integrity[field]:
            raise ValueError('Recovery checkpoint failed verification')
    children = process.children(recursive=True)
    targets = [process]
    for item in children:
        if item.name().lower() == 'conhost.exe':
            continue
        if trainer not in item.cmdline() or 'qwen3_1_7b' not in item.cmdline():
            raise ValueError('Unexpected child outside this training job')
        targets.append(item)
    if trainer not in process.cmdline() or 'qwen3_1_7b' not in process.cmdline():
        raise ValueError('Refusing to stop a process outside this training job')
    if read(folder / 'training.json')['status'] != 'RUNNING':
        raise ValueError('Original training is no longer active')
    history.mkdir()
    write(history / 'planned_interruption.json', {'time': now(), 'checkpoint': str(checkpoint),
        'integrity': integrity, 'processes': [{'pid': item.pid, 'command': item.cmdline()} for item in targets],
        'reason': 'Sustained optimizer slowdown with almost full device memory; resume with a bounded native CUDA allocator'})
    # Terminate only the verified child computation, then let its launcher and
    # original driver record the nonzero exit and finish normally.
    if len(targets) != 2:
        raise ValueError('Unexpected Windows venv process tree; inspect manually')
    targets[-1].terminate()
    targets[-1].wait(timeout=30)
    process.wait(timeout=30)
    owner_process.wait(timeout=30)
    for path in (WORK / 'workflow.json', WORK / 'workflow_error.json', folder / 'training.json',
                 folder / 'optimizer_steps.jsonl', WORK / 'jobs/qwen3_1_7b_train.json'):
        destination = history / path.name
        shutil.copy2(path, destination)
        if digest(path) != digest(destination):
            raise ValueError('Attempt history copy verification failed')
    # Every original line remains in the history. Only optimizer updates after
    # the chosen saved state are omitted from the continued attempt's main log.
    log = folder / 'optimizer_steps.jsonl'
    lines = log.read_text(encoding='utf-8').splitlines()
    saved_lines = [line for line in lines if json.loads(line)['step'] <= 440]
    if [json.loads(line)['step'] for line in saved_lines] != list(range(1, 441)):
        raise ValueError('Original optimizer history has unexpected step order')
    log.write_text('\n'.join(saved_lines) + '\n', encoding='utf-8')
    old_error = WORK / 'workflow_error.json'
    old_error.rename(history / 'original_workflow_error.json')
    return checkpoint


def main():
    freeze_inputs()
    checkpoint = stop_after_verified_checkpoint()
    write(WORK / 'workflow.json', {'status': 'RUNNING', 'started_at': now(), 'owner_pid': os.getpid(),
        'driver': str(Path(__file__).resolve()), 'resume_checkpoint': str(checkpoint),
        'previous_attempt_preserved': str(WORK / 'before_cuda_memory_guard')})
    with ThreadPoolExecutor(max_workers=1) as cpu:
        run('qwen3_1_7b_resume_0440_train', PYTHON, 'train_small_model_memory_guard.py',
            '--model', 'qwen3_1_7b', '--mode', 'train', '--micro-batch', '4', '--resume', str(checkpoint))
        first_future = cpu.submit(build_and_evaluate, 'qwen3_1_7b')
        run('qwen35_2b_train', NEW_PYTHON, 'train_small_model_memory_guard.py',
            '--model', 'qwen35_2b', '--mode', 'train', '--micro-batch', '4')
        first_future.result()
        build_and_evaluate('qwen35_2b')
    deliveries, error = {}, None
    try:
        for key in NAMES:
            deliveries[key] = transfer(key)
    except Exception as exc:
        error = str(exc)
    result = report(deliveries, error)
    # The release audit is separate from training and never influences adapter
    # selection. It checks already completed artifacts and raw predictions.
    run('release_audit', PYTHON, 'audit_small_models_release.py')
    write(WORK / 'workflow.json', {'status': result['execution_status'], 'completed_at': now(),
        'quality_status': result['quality_status'], 'report': str(REPORT)})
    print(result['execution_status'])


if __name__ == '__main__':
    try:
        main()
    except Exception:
        write(WORK / 'memory_recovery_error.json', {'status': 'FAILED', 'time': now(), 'traceback': traceback.format_exc()})
        raise
