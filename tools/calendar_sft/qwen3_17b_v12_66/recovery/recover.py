"""Resume an interrupted isolated run without changing its frozen training inputs."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import ctypes
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import traceback
from uuid import uuid4

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
from run_context import (ROOT, RUN, WORK, DATA, CONFIG, read, write, digest, now,
                         frozen_paths, development_cases, verify_preservation)
from v12_66_launch_integrity import verify_launch_inputs, PROTECTED_LOCK, PREPARATION_LOCK
from gpu_power import PowerGuard, read_gpu_power


def process_exists(pid):
    """Query process liveness on Windows without signalling or terminating it."""
    if not isinstance(pid, int) or pid <= 0:
        return False
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if handle:
        kernel.CloseHandle(handle)
        return True
    error = ctypes.get_last_error()
    if error == 87:  # The PID does not exist.
        return False
    raise OSError(error, 'Cannot establish whether the previous run is still alive')


@contextmanager
def controller_lock():
    import msvcrt
    with (RUN / '.recovery.lock').open('a+b') as stream:
        if stream.tell() == 0:
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            yield
        finally:
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


def scan_runtime(run, step):
    """Reject corrupt logs and duplicate or uncommitted optimizer updates."""
    result = {'jsonl': {}, 'invalid_progress_metadata': None}
    for path in sorted(run.glob('*.jsonl')):
        records = []
        for index, line in enumerate(path.read_bytes().splitlines(), 1):
            try:
                item = json.loads(line.decode('utf-8'))
            except (ValueError, UnicodeError) as error:
                raise ValueError(f'Invalid JSONL: {path.name}:{index}') from error
            if not isinstance(item, dict):
                raise ValueError(f'Non-object JSONL record: {path.name}:{index}')
            records.append(item)
        if path.name == 'optimizer_steps.jsonl':
            if [item.get('step') for item in records] != list(range(1, step + 1)):
                raise ValueError('Optimizer log does not match the resume checkpoint')
            result['previous_optimizer_log_seconds'] = records[-1]['seconds']
        result['jsonl'][path.name] = {'rows': len(records), 'sha256': digest(path)}
    if 'optimizer_steps.jsonl' not in result['jsonl']:
        raise ValueError('Optimizer history is missing')
    for path in sorted(run.glob('*.json')):
        try:
            read(path)
        except (ValueError, UnicodeError) as error:
            if path.name != 'development_state.json':
                raise ValueError('Invalid runtime metadata: ' + path.name) from error
            result['invalid_progress_metadata'] = {
                'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)}
    return result


def verify_cached(folder, cases, integrity):
    expected = {case['id']: case for case in cases}
    files = []
    for path in sorted((folder / 'development_cases').glob('*.json')):
        item = read(path)
        if (path.stem not in expected or item.get('case') != expected[path.stem]
                or item.get('step') != integrity['step']
                or item.get('adapter_sha256') != integrity['adapter_sha256']
                or not isinstance(item.get('raw_output'), str)):
            raise ValueError('Development cache is bound to different inputs: ' + path.name)
        files.append({'file': path.name, 'sha256': digest(path)})
    return files


def archive_runtime(run, destination):
    """Keep byte-identical originals, including corrupt metadata and partial temp files."""
    if not destination.resolve().is_relative_to(run.resolve()):
        raise ValueError('Archive is outside the run directory')
    destination.mkdir(exist_ok=False)
    archived = {}
    for source in sorted(run.iterdir()):
        if not source.is_file() or source.suffix not in ('.json', '.jsonl', '.log', '.tmp'):
            continue
        expected = digest(source)
        target = destination / source.name
        with source.open('rb') as original, target.open('xb') as copy:
            shutil.copyfileobj(original, copy)
        if digest(source) != expected or digest(target) != expected:
            raise ValueError('Runtime file changed during archival: ' + source.name)
        archived[source.name] = {'bytes': source.stat().st_size, 'sha256': expected}
    return archived


def require_interrupted_training(previous):
    if previous.get('stage') != 'training' or previous.get('status') not in ('RUNNING', 'FAILED'):
        raise ValueError('This recovery requires an interrupted training stage')
    for step in previous.get('steps', []):
        code = step.get('exit_code')
        if step.get('stage') != 'training' or not isinstance(code, int) or code == 0:
            raise ValueError('A completed or later pipeline stage requires separate recovery')


def preflight(checkpoint):
    checkpoint = checkpoint.resolve()
    if checkpoint.parent != RUN.resolve() or not checkpoint.name.startswith('checkpoint-'):
        raise ValueError('Checkpoint is outside the selected run')
    previous = read(RUN / 'pipeline.json')
    require_interrupted_training(previous)
    for key in ('controller_pid', 'child_pid'):
        if process_exists(previous.get(key)):
            raise ValueError('Previous process is still alive: ' + key)
    if any((RUN / name).exists() for name in ('adapter', 'build', 'release', 'evaluation_f16', 'evaluation_q4')):
        raise ValueError('Later stage outputs already exist; inspect them before recovery')
    integrity = read(checkpoint / 'checkpoint_integrity.json')
    if (digest(checkpoint / 'training_state.pt') != integrity['state_sha256']
            or digest(checkpoint / 'adapter/adapter_model.safetensors') != integrity['adapter_sha256']):
        raise ValueError('Checkpoint integrity failed')
    import torch
    saved = torch.load(checkpoint / 'training_state.pt', map_location='cpu', weights_only=False)
    frozen = {str(path): digest(path) for path in
              frozen_paths() + [ROOT / PROTECTED_LOCK, ROOT / PREPARATION_LOCK]}
    training = read(RUN / 'training.json')
    if frozen != saved['frozen_inputs'] or frozen != training['frozen_inputs']:
        raise ValueError('Frozen training inputs changed')
    if saved['config'] != training['config'] or saved['config'] != read(RUN / 'preflight.json'):
        raise ValueError('Saved training configuration changed')
    if saved['step'] != integrity['step']:
        raise ValueError('Checkpoint step mismatch')
    cases = development_cases(read(DATA / 'generation_dev.json'))
    cached = verify_cached(checkpoint, cases, integrity)
    record = dict(checked_at=now(), checkpoint=str(checkpoint), integrity=integrity,
                  retained_optimizer_steps=saved['step'], next_epoch=saved['next_epoch'],
                  next_offset=saved['next_offset'], best_step=saved['best_step'],
                  frozen_inputs_checked=len(frozen), retained_development_cases=cached,
                  development_total=len(cases), runtime=scan_runtime(RUN, saved['step']),
                  preservation=verify_preservation(), training_inputs=verify_launch_inputs(),
                  gpu_power=read_gpu_power(CONFIG['gpu_power']))
    return previous, record


def complete_recovery_report(session, recovery):
    """Disclose reboot and the elapsed-time boundary in the generated final report."""
    quality_path = RUN / 'release/quality_manifest.json'
    quality = read(quality_path)
    original = session / 'original_quality_manifest.json'
    with original.open('xb') as stream:
        stream.write(quality_path.read_bytes())
    quality['recovery'] = recovery
    quality['training_seconds_scope'] = 'Resumed segment including development evaluations; prior history archived separately'
    write(quality_path, quality)
    report = ROOT / 'docs/CALENDAR_ASSISTANT_V12_66_QWEN3_17B_RESULTS.md'
    with (session / 'original_results.md').open('xb') as stream:
        stream.write(report.read_bytes())
    text = report.read_text(encoding='utf-8').replace(
        'Лимит автоматически не сбрасывался.',
        'При восстановлении обучения лимит повторно подтверждён на уровне 150 Вт.')
    text += ('\n## Продолжение после выключения компьютера\n\n'
             f'Обучение продолжено с шага {recovery["retained_optimizer_steps"]}; '
             f'для этой контрольной точки найдено {len(recovery["retained_development_cases"])} сохранённых промежуточных ответов. '
             'Контрольная точка и все зафиксированные входы проверены. '
             'Побайтовые копии прежних журналов и повреждённого файла прогресса сохранены. '
             'Показатель training_seconds в итоговом training.json относится к продолженному '
             'отрезку и включает промежуточные проверки.\n')
    temporary = report.with_suffix('.md.tmp')
    temporary.write_text(text, encoding='utf-8')
    os.replace(temporary, report)


def execute(checkpoint, session):
    with controller_lock():
        previous, recovery = preflight(checkpoint)
        recovery['archive'] = archive_runtime(RUN, session / 'originals')
        recovery.update(session=str(session), controller_sha256=digest(Path(__file__)),
                        resumed_at=now(), gpu_power_constraint=CONFIG['gpu_power'],
                        previous_recovery_session=previous.get('recovery', {}).get('session'))
        with (session / 'controller_source.py').open('xb') as source_snapshot:
            source_snapshot.write(Path(__file__).read_bytes())
        if digest(session / 'controller_source.py') != recovery['controller_sha256']:
            raise ValueError('Recovery controller archive failed integrity verification')
        write(session / 'recovery.json', recovery)
        state = RUN / 'pipeline.json'
        record = dict(previous)
        record.update(status='RUNNING', resumed_at=now(), controller_pid=os.getpid(),
                      child_pid=None, stage='recovery', steps=[], recovery=recovery)
        write(state, record)
        stages = (
            ('training', 'train.py', ['--mode', 'train', '--resume', str(checkpoint)]),
            ('gguf_build', 'build_gguf.py', ['--model', CONFIG['model']]),
            ('f16_evaluation', 'release_evaluation.py', ['--format', 'f16']),
            ('q4_evaluation', 'release_evaluation.py', ['--format', 'q4']),
            ('quantization_comparison', 'release_evaluation.py', ['--compare']),
            ('final_report', 'final_report.py', []),
        )
        power = PowerGuard(CONFIG['gpu_power'], RUN / (session.name + '_gpu_power_samples.jsonl'))
        try:
            for stage, script, arguments in stages:
                power.check(force=True)
                source = HERE / script
                command = [sys.executable, '-X', 'utf8', '-B', str(source), *arguments]
                step = dict(stage=stage, script_sha256=digest(source), command=command, started_at=now())
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
                    raise RuntimeError(stage + ' failed; logs and checkpoints preserved')
            complete_recovery_report(session, recovery)
            comparison = read(RUN / 'quantization_comparison.json')
            record.update(status='COMPLETE', completed_at=now(), child_pid=None,
                          quality_status=comparison['q4']['quality_status'], preservation=verify_preservation(),
                          model=read(RUN / 'release/gguf_manifest.json')['model'])
            write(state, record)
            print('Recovered training, GGUF build and independent evaluations complete', flush=True)
        except BaseException:
            record.update(status='FAILED', failed_at=now(), error=traceback.format_exc())
            write(state, record)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkpoint', type=Path, required=True)
    parser.add_argument('--launch-hidden', action='store_true')
    parser.add_argument('--session', type=Path)
    args = parser.parse_args()
    if args.launch_hidden:
        read_gpu_power(CONFIG['gpu_power'])
        # Refuse a second launcher while an earlier recovery controller is active.
        current = read(RUN / 'pipeline.json')
        if current.get('recovery') and process_exists(current.get('controller_pid')):
            raise ValueError('Recovery controller is already running')
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        session = RUN / ('recovery_' + stamp + '_' + uuid4().hex[:8])
        session.mkdir(exist_ok=False)
        command = [sys.executable, '-X', 'utf8', '-B', str(Path(__file__)),
                   '--checkpoint', str(args.checkpoint.resolve()), '--session', str(session)]
        with (session / 'controller.stdout.log').open('xb') as out, (session / 'controller.stderr.log').open('xb') as err:
            child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                     stdout=out, stderr=err,
                                     creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        write(session / 'launch.json', dict(status='LAUNCHED', launcher_pid=child.pid,
                                           launched_at=now(), command=command,
                                           controller_sha256=digest(Path(__file__))))
        print(json.dumps(dict(launcher_pid=child.pid, session=str(session))))
        return
    if args.session is None or args.session.resolve().parent != RUN.resolve():
        raise ValueError('An isolated recovery session directory is required')
    execute(args.checkpoint.resolve(), args.session.resolve())


if __name__ == '__main__':
    main()
