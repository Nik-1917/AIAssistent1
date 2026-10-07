"""Continue only GGUF build and evaluation after verified completed training."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import traceback
from uuid import uuid4

from recover import (HERE, ROOT, RUN, WORK, CONFIG, read, write, digest, now,
                     verify_preservation, controller_lock, archive_runtime,
                     process_exists, complete_recovery_report)
from gpu_power import PowerGuard, read_gpu_power
from resume_evaluation import verify_training_result
from build_v12_2_gguf import verify_tools


def completed_training_step(previous, training):
    if training.get('status') != 'COMPLETE' or training.get('completed_optimizer_steps') != 1128:
        raise ValueError('Do not build from incomplete training')
    if previous.get('stage') != 'gguf_build' or previous.get('status') != 'FAILED':
        raise ValueError('Only the failed GGUF build can be continued here')
    completed = [step for step in previous.get('steps', [])
                 if step.get('stage') == 'training' and step.get('exit_code') == 0]
    if len(completed) != 1:
        raise ValueError('A verified successful training stage is required')
    if any(step.get('stage') not in ('training', 'gguf_build') for step in previous.get('steps', [])):
        raise ValueError('Later stages require their own recovery')
    return completed[0]


def require_fresh_outputs(run):
    if any((run / name).exists() for name in ('build', 'release', 'evaluation_f16', 'evaluation_q4')):
        raise ValueError('Partial or completed outputs exist; preserve and inspect them before retrying')


def execute(session):
    with controller_lock():
        previous = read(RUN / 'pipeline.json')
        training_step = completed_training_step(previous, read(RUN / 'training.json'))
        require_fresh_outputs(RUN)
        for key in ('controller_pid', 'child_pid'):
            if process_exists(previous.get(key)):
                raise ValueError('An earlier pipeline process is still alive: ' + key)
        verify_training_result()
        selection = read(RUN / 'selection.json')
        if selection['status'] != 'COMPLETE' or selection['best_step'] != read(RUN / 'training.json')['selected_step']:
            raise ValueError('Development selection is incomplete')
        tools = verify_tools()
        read_gpu_power(CONFIG['gpu_power'])
        restoration = read(WORK / 'converter_recovery_result.json')
        for item in restoration['restored_files']:
            path = ROOT / 'build/llama.cpp-v0.3.0' / item['path']
            if path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
                raise ValueError('Restored converter vocabulary changed')
        recovery = dict(started_at=now(), session=str(session), training_repeated=False,
                        completed_optimizer_steps=1128, selected_step=selection['best_step'],
                        tools=tools, restored_converter_files=restoration['restored_files'],
                        previous_recovery_session=previous['recovery']['session'],
                        controller_sha256=digest(Path(__file__)))
        recovery['archive'] = archive_runtime(RUN, session / 'originals')
        with (session / 'controller_source.py').open('xb') as stream:
            stream.write(Path(__file__).read_bytes())
        if digest(session / 'controller_source.py') != recovery['controller_sha256']:
            raise ValueError('Controller archive failed integrity verification')
        write(session / 'recovery.json', recovery)
        record = {key: value for key, value in previous.items()
                  if key not in ('failed_at', 'error', 'completed_at', 'quality_status')}
        record.update(status='RUNNING', controller_pid=os.getpid(), child_pid=None,
                      resumed_at=now(), stage='gguf_build', steps=[training_step],
                      post_training_recovery=recovery, training_repeated=False)
        state = RUN / 'pipeline.json'
        write(state, record)
        power = PowerGuard(CONFIG['gpu_power'], RUN / (session.name + '_gpu_power_samples.jsonl'))
        stages = (
            ('gguf_build', 'build_gguf.py', ['--model', CONFIG['model']]),
            ('f16_evaluation', 'release_evaluation.py', ['--format', 'f16']),
            ('q4_evaluation', 'release_evaluation.py', ['--format', 'q4']),
            ('quantization_comparison', 'release_evaluation.py', ['--compare']),
            ('final_report', 'final_report.py', []),
        )
        try:
            for stage, script, arguments in stages:
                power.check(force=True)
                path = HERE / script
                command = [sys.executable, '-X', 'utf8', '-B', str(path), *arguments]
                step = dict(stage=stage, script_sha256=digest(path), command=command, started_at=now())
                with (session / (stage + '.stdout.log')).open('xb') as out, (session / (stage + '.stderr.log')).open('xb') as err:
                    child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                             stdout=out, stderr=err,
                                             creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                    record.update(stage=stage, child_pid=child.pid)
                    write(state, record)
                    print(stage + ': started', flush=True)
                    step['exit_code'] = child.wait()
                step['completed_at'] = now()
                record['steps'].append(step)
                write(state, record)
                if step['exit_code']:
                    raise RuntimeError(stage + ' failed; logs and outputs preserved')
            complete_recovery_report(session, previous['recovery'])
            quality_path = RUN / 'release/quality_manifest.json'
            quality = read(quality_path)
            quality['post_training_recovery'] = recovery
            quality['recovery_validation'] = read(WORK / 'post_training_recovery_validation.json')
            write(quality_path, quality)
            report = ROOT / 'docs/CALENDAR_ASSISTANT_V12_66_QWEN3_4B_RESULTS.md'
            text = report.read_text(encoding='utf-8')
            text += ('\n## Восстановление сборки GGUF\n\n'
                     'Обучение завершило 1128 обновлений и не повторялось. '
                     'Для сборки восстановлены 19 отсутствующих штатных словарей llama.cpp '
                     'из закреплённого локального Git-коммита; код конвертера и бинарные '
                     'инструменты прошли исходную проверку целостности. '
                     'Предыдущие журналы и состояния сохранены с проверкой хешей копий.\n')
            temporary = report.with_suffix('.md.tmp')
            temporary.write_text(text, encoding='utf-8')
            os.replace(temporary, report)
            comparison = read(RUN / 'quantization_comparison.json')
            record.update(status='COMPLETE', completed_at=now(), child_pid=None,
                          quality_status=comparison['q4']['quality_status'], preservation=verify_preservation(),
                          model=read(RUN / 'release/gguf_manifest.json')['model'])
            write(state, record)
            print('GGUF build, both independent evaluations and final comparison complete', flush=True)
        except BaseException:
            record.update(status='FAILED', failed_at=now(), error=traceback.format_exc())
            write(state, record)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launch-hidden', action='store_true')
    parser.add_argument('--session', type=Path)
    args = parser.parse_args()
    if args.launch_hidden:
        read_gpu_power(CONFIG['gpu_power'])
        previous = read(RUN / 'pipeline.json')
        completed_training_step(previous, read(RUN / 'training.json'))
        require_fresh_outputs(RUN)
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        session = RUN / ('post_training_recovery_' + stamp + '_' + uuid4().hex[:8])
        session.mkdir(exist_ok=False)
        command = [sys.executable, '-X', 'utf8', '-B', str(Path(__file__)), '--session', str(session)]
        with (session / 'controller.stdout.log').open('xb') as out, (session / 'controller.stderr.log').open('xb') as err:
            child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                     creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        write(session / 'launch.json', dict(status='LAUNCHED', launched_at=now(), launcher_pid=child.pid,
                                           command=command, controller_sha256=digest(Path(__file__))))
        print(json.dumps(dict(launcher_pid=child.pid, session=str(session))))
        return
    if args.session is None or args.session.resolve().parent != RUN.resolve():
        raise ValueError('An isolated post-training session directory is required')
    execute(args.session.resolve())


if __name__ == '__main__':
    main()
