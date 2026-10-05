"""Durable local train -> build -> independent evaluation sequence."""
import os
from pathlib import Path
import subprocess
import sys
import traceback

from v12_66_training import ROOT, WORK, read, write, digest, now
from v12_66_launch_integrity import verify_launch_inputs


def main():
    run = WORK / 'qwen35_2b'
    run.mkdir(parents=True, exist_ok=True)
    state = run / 'pipeline.json'
    if state.exists() or (run / 'training.json').exists():
        raise ValueError('Run already exists; explicit checkpoint recovery is required')
    if read(run / 'smoke-batch4.json')['status'] != 'COMPLETE':
        raise ValueError('Successful GPU smoke is required')
    inputs = verify_launch_inputs()
    stages = (
        ('training', 'train_v12_66_release.py', ['--mode', 'train']),
        ('gguf_build', 'build_v12_66_gguf.py', ['--model', 'qwen35_2b']),
        ('holdout_evaluation', 'evaluate_v12_66_release.py', []),
    )
    record = dict(status='RUNNING', started_at=now(), controller_pid=os.getpid(),
                  workspace=str(ROOT), python=sys.executable, training_inputs=inputs,
                  stage=None, steps=[], physical_device_inference='NOT_RUN')
    write(state, record)
    try:
        for stage, script, arguments in stages:
            path = ROOT / 'tools/calendar_sft' / script
            command = [sys.executable, '-X', 'utf8', '-B', str(path), *arguments]
            step = dict(stage=stage, script_sha256=digest(path), command=command, started_at=now())
            with (run / (stage + '.stdout.log')).open('xb') as out, (run / (stage + '.stderr.log')).open('xb') as err:
                child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                         creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                record.update(stage=stage, child_pid=child.pid)
                write(state, record)
                step['exit_code'] = child.wait()
            step['completed_at'] = now()
            record['steps'].append(step)
            write(state, record)
            if step['exit_code'] != 0:
                raise RuntimeError(stage + ' failed; preserved all logs and checkpoints')
        evaluation = read(run / 'evaluation/report.json')
        record.update(status='COMPLETE', completed_at=now(), child_pid=None,
                      quality_status=evaluation['quality_status'],
                      model=read(run / 'release/gguf_manifest.json')['model'])
        write(state, record)
    except BaseException:
        record.update(status='FAILED', failed_at=now(), error=traceback.format_exc())
        write(state, record)
        raise


if __name__ == '__main__':
    main()
