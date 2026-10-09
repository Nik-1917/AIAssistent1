"""Finalize already completed training, retaining strict training-input integrity.

Android-only drift is recorded as DIFFERS against the original snapshot. A
separate immutable boundary protects the current Android tree for the remaining
stages. No optimizer updates or development generation are replayed.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import traceback
from uuid import uuid4

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
import run_context as context
from run_context import ROOT, RUN, WORK, DATA, CONFIG, read, write, digest, now
from recovery.recover import archive_runtime, controller_lock, process_exists
from v12_66_launch_integrity import verify_launch_inputs, PROTECTED_LOCK, PREPARATION_LOCK
from v12_66_evaluation import grade, summary, selection_score
from gpu_power import PowerGuard, read_gpu_power, validate_snapshot

STAGES = (
    ('gguf_build', 'build_gguf.py', ['--model', CONFIG['model']]),
    ('f16_evaluation', 'release_evaluation.py', ['--format', 'f16']),
    ('q4_evaluation', 'release_evaluation.py', ['--format', 'q4']),
    ('quantization_comparison', 'release_evaluation.py', ['--compare']),
    ('final_report', 'final_report.py', []),
)
SOURCE = Path(__file__).resolve()


def safe_file(root, name):
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError('Missing or outside protected root: ' + name)
    return path


def android_inventory(root):
    return {p.relative_to(root).as_posix(): digest(p)
            for p in sorted((root / 'app/src').rglob('*')) if p.is_file()}


def capture_boundary(root, original):
    """Allow only pre-existing Android drift; never relax data/rules protection."""
    if not original:
        raise ValueError('Empty original preservation snapshot')
    files, changes = {}, []
    for name, expected in original.items():
        actual = digest(safe_file(root, name))
        if actual != expected:
            if not name.startswith('app/src/'):
                raise ValueError('Protected training data/rules changed: ' + name)
            changes.append(dict(path=name, original_sha256=expected, recovery_sha256=actual))
        files[name] = actual
    android = android_inventory(root)
    files.update(android)
    return dict(files=files, android_files=android,
                android_changed=changes,
                android_added=sorted(set(android) - set(original)),
                original_files_checked=len(original),
                non_android_files_checked=sum(not n.startswith('app/src/') for n in original),
                original_whole_project_snapshot='DIFFERS' if changes or set(android) - set(original) else 'MATCHES')


def verify_boundary(root, boundary):
    if android_inventory(root) != boundary['android_files']:
        raise ValueError('Android changed after the recovery boundary was captured')
    for name, expected in boundary['files'].items():
        if digest(safe_file(root, name)) != expected:
            raise ValueError('Protected file changed after recovery: ' + name)
    return dict(status='PASS_TRAINING_INPUTS_ANDROID_DRIFT' if boundary['original_whole_project_snapshot'] == 'DIFFERS' else 'PASS',
                files_checked=len(boundary['files']),
                non_android_files_checked=boundary['non_android_files_checked'],
                original_whole_project_snapshot=boundary['original_whole_project_snapshot'],
                android_changed=boundary['android_changed'], android_added=boundary['android_added'],
                current_android_tree='MATCHES_RECOVERY_BOUNDARY')


def verify_optimizer(rows, planned, steps_per_epoch):
    if [row.get('step') for row in rows] != list(range(1, planned + 1)):
        raise ValueError('Optimizer log is incomplete or contains duplicate updates')
    for row in rows:
        if row['epoch'] != (row['step'] - 1) // steps_per_epoch + 1:
            raise ValueError('Optimizer epoch mismatch')
        for key in ('loss', 'gradient_norm', 'learning_rate', 'seconds', 'optimizer_step_seconds', 'scale'):
            if not math.isfinite(row[key]) or row[key] < 0:
                raise ValueError('Non-finite or negative optimizer value: ' + key)
        if row['scale'] <= 0 or not isinstance(row['overflow_retries'], int) or row['overflow_retries'] < 0:
            raise ValueError('Invalid AMP history')
    return dict(steps=len(rows), optimizer_seconds=sum(r['optimizer_step_seconds'] for r in rows),
                overflow_retries=sum(r['overflow_retries'] for r in rows),
                last_elapsed_seconds=rows[-1]['seconds'])


def verify_selection(evaluations, planned, claimed_step, claimed_score):
    if [e['step'] for e in evaluations] != planned:
        raise ValueError('Development evaluations are missing, duplicated, or out of order')
    best = max(evaluations, key=lambda e: tuple(e['score']))
    if claimed_step != best['step'] or claimed_score != best['score']:
        raise ValueError('Selected checkpoint differs from the recomputed development ranking')
    if any(not math.isfinite(e['seconds']) or e['seconds'] <= 0 for e in evaluations):
        raise ValueError('Invalid development timing')
    return best['step']


def inventory_files(folder):
    return {p.relative_to(folder).as_posix(): digest(p) for p in sorted(folder.rglob('*')) if p.is_file()}


def audit_completed():
    previous, training = read(RUN / 'pipeline.json'), read(RUN / 'training.json')
    if previous['status'] != 'FAILED' or previous['stage'] != 'training' or training['status'] != 'RUNNING':
        raise ValueError('This recovery requires failed finalization of already completed training')
    if 'Protected Android/data/rules file changed: app/src/' not in (RUN / 'training.stderr.log').read_text(encoding='utf-8'):
        raise ValueError('The recorded failure is not the expected Android preservation audit')
    for key in ('controller_pid', 'child_pid'):
        if process_exists(previous.get(key)):
            raise ValueError('Previous process remains alive: ' + key)
    for name in ('build', 'release', 'evaluation_f16', 'evaluation_q4', 'quantization_comparison.json'):
        if (RUN / name).exists():
            raise ValueError('Later outputs already exist; separate stage recovery is required: ' + name)
    frozen = {str(p): digest(p) for p in context.frozen_paths() + [ROOT / PROTECTED_LOCK, ROOT / PREPARATION_LOCK]}
    if frozen != training['frozen_inputs']:
        raise ValueError('Frozen training inputs changed')
    print('Audit: frozen training inputs verified', flush=True)
    config = training['config']
    if config != read(RUN / 'preflight.json') or config['configuration'] != CONFIG:
        raise ValueError('Training configuration changed')
    if config['holdout_used_for_selection'] or config['planned_optimizer_steps'] != 1128:
        raise ValueError('Wrong selection or training contract')
    for package, version in config['packages'].items():
        if importlib.metadata.version(package) != version:
            raise ValueError('Training/evaluation dependency changed: ' + package)
    source_lock, source_folder = context.verify_source(CONFIG['model'])
    print('Audit: original model source verified', flush=True)
    baseline = read(WORK / 'comparison_baseline.json')
    for path, expected in baseline['files_sha256'].items():
        if digest(path) != expected:
            raise ValueError('Bound baseline changed: ' + path)
    boundary = capture_boundary(ROOT, read(context.PRESERVATION)['files'])
    optimizer = verify_optimizer([json.loads(line) for line in (RUN / 'optimizer_steps.jsonl').read_text(encoding='utf-8').splitlines()],
                                 config['planned_optimizer_steps'], math.ceil(config['train_rows'] / config['effective_batch_size']))
    evidence = dict(baseline['files_sha256'])
    evidence.update({str(source_folder / name): item['sha256'] for name, item in source_lock['files'].items()})
    for path in sorted(RUN.glob('*.jsonl')):
        for line in path.read_text(encoding='utf-8').splitlines():
            item = json.loads(line)
            if 'gpu_power_samples' in path.name:
                validate_snapshot(item, CONFIG['gpu_power'])
        evidence[str(path)] = digest(path)
    selected = read(RUN / 'selection.json')
    if selected['status'] != 'IN_PROGRESS' or selected['holdout_used']:
        raise ValueError('Unexpected saved selection status')
    best_step = verify_selection(selected['evaluations'], config['selection_steps'], selected['best_step'], selected['best_score'])
    cases = context.development_cases(read(DATA / 'generation_dev.json'))
    if len(cases) != 232 or len({case['id'] for case in cases}) != 232:
        raise ValueError('Wrong development inventory')
    checkpoints = []
    for folder in sorted(RUN.glob('checkpoint-*')):
        integrity = read(folder / 'checkpoint_integrity.json')
        if (folder.name != f"checkpoint-{integrity['step']:04}"
                or digest(folder / 'training_state.pt') != integrity['state_sha256']
                or digest(folder / 'adapter/adapter_model.safetensors') != integrity['adapter_sha256']):
            raise ValueError('Checkpoint integrity failed: ' + folder.name)
        checkpoints.append(integrity['step'])
        evidence.update({str(folder / name): sha for name, sha in inventory_files(folder).items()})
    print('Audit: all checkpoint file hashes verified', flush=True)
    for evaluation in selected['evaluations']:
        step = evaluation['step']
        folder = RUN / f'checkpoint-{step:04}'
        integrity = read(folder / 'checkpoint_integrity.json')
        if {p.stem for p in (folder / 'development_cases').glob('*.json')} != {c['id'] for c in cases}:
            raise ValueError('Incomplete or unexpected development cache')
        records = []
        for case in cases:
            cached = read(folder / 'development_cases' / (case['id'] + '.json'))
            if (cached['case'] != case or cached['step'] != step or cached['adapter_sha256'] != integrity['adapter_sha256']
                    or not isinstance(cached['raw_output'], str)):
                raise ValueError('Development cache binding failed')
            records.append(grade(case, cached['raw_output']))
        report = read(folder / 'development.json')
        if (report['cases'] != records or report['score'] != selection_score(cases, records)
                or {k: v for k, v in report.items() if k != 'cases'} != evaluation
                or any(report[k] != value for k, value in summary(records).items())):
            raise ValueError('Development report differs from retained raw predictions')
    if read(RUN / 'development_state.json') != dict(step=1128, completed=232, total=232):
        raise ValueError('Final development progress is incomplete')
    import torch
    torch.set_num_threads(12)
    saved = torch.load(RUN / 'checkpoint-1128/training_state.pt', map_location='cpu', weights_only=False)
    if (saved['step'] != 1128 or saved['next_epoch'] != CONFIG['epochs'] or saved['next_offset'] != 0
            or saved['config'] != config or saved['frozen_inputs'] != frozen
            or saved['evaluations'] != selected['evaluations'][:-1]):
        raise ValueError('Terminal checkpoint does not prove the completed training sequence')
    if saved['best_step'] != best_step or saved['best'] != selected['best_score']:
        raise ValueError('Terminal checkpoint and retained selection differ')
    if saved['scheduler']['last_epoch'] != 1128 or saved['scheduler']['_last_lr'] != [0.0]:
        raise ValueError('Scheduler did not reach the planned final update')
    for state in saved['optimizer']['state'].values():
        if int(state['step'].item()) != 1128:
            raise ValueError('Optimizer parameter state has an incomplete update count')
        for value in state.values():
            if torch.is_tensor(value) and not torch.isfinite(value).all().item():
                raise ValueError('Non-finite terminal optimizer state')
    if inventory_files(RUN / 'adapter') != inventory_files(RUN / f'checkpoint-{best_step:04}/adapter'):
        raise ValueError('Selected adapter copy differs from the selected checkpoint')
    from safetensors import safe_open
    with safe_open(RUN / 'adapter/adapter_model.safetensors', framework='pt', device='cpu') as handle:
        for key in handle.keys():
            if not torch.isfinite(handle.get_tensor(key)).all().item():
                raise ValueError('Non-finite selected adapter')
    evidence.update({str(RUN / 'adapter' / name): sha for name, sha in inventory_files(RUN / 'adapter').items()})
    print('Audit: terminal optimizer, development ranking and selected adapter verified', flush=True)
    for name in ('optimizer_steps.jsonl', 'development_state.json', 'preflight.json', 'source_loading.json', 'trainable_parameters.json', 'validation.json'):
        evidence[str(RUN / name)] = digest(RUN / name)
    return dict(checked_at=now(), status='PASS_COMPLETED_COMPUTATION', boundary=boundary,
                frozen_inputs=frozen, completed_optimizer_steps=1128, optimizer=optimizer,
                selected_step=best_step, selected_score=selected['best_score'],
                adapter_sha256=digest(RUN / 'adapter/adapter_model.safetensors'),
                checkpoint_steps=checkpoints, development_evaluations=6,
                retained_development_predictions=6 * len(cases),
                completed_development_segment_seconds=sum(e['seconds'] for e in selected['evaluations']),
                immutable_evidence=evidence, training_inputs=verify_launch_inputs(),
                source_files_checked=len(source_lock['files']), source_directory=str(source_folder),
                baseline_files_checked=len(baseline['files_sha256']), gpu_power=read_gpu_power(CONFIG['gpu_power']))


def exclusive_json(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def verified_session(session, binding, *, retained_evidence=True):
    session = session.resolve()
    if session.parent != RUN.resolve() or not session.name.startswith('finalization_recovery_'):
        raise ValueError('Recovery session is outside the run')
    manifest = session / 'recovery.json'
    if digest(manifest) != binding:
        raise ValueError('Immutable recovery manifest changed')
    recovery = read(manifest)
    for path, expected in recovery['helper_sources'].items():
        if digest(path) != expected or digest(session / Path(path).name) != expected:
            raise ValueError('Recovery helper source changed: ' + path)
    for path, expected in recovery['audit']['frozen_inputs'].items():
        if digest(path) != expected:
            raise ValueError('Frozen training input changed: ' + path)
    if retained_evidence:
        for path, expected in recovery['audit']['immutable_evidence'].items():
            if digest(path) != expected:
                raise ValueError('Retained training evidence changed: ' + path)
    for name, item in recovery['archive'].items():
        if digest(session / 'originals' / name) != item['sha256']:
            raise ValueError('Archived original changed: ' + name)
    if digest(session / 'preservation.original.json') != digest(context.PRESERVATION):
        raise ValueError('Original preservation snapshot changed')
    verify_boundary(ROOT, recovery['audit']['boundary'])
    return recovery


def prepare():
    with controller_lock():
        audit = audit_completed()
        session = RUN / ('finalization_recovery_' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '_' + uuid4().hex[:8])
        session.mkdir(exist_ok=False)
        archive = archive_runtime(RUN, session / 'originals')
        shutil.copyfile(context.PRESERVATION, session / 'preservation.original.json')
        helpers = [SOURCE, SOURCE.with_name('test_finalization.py'), SOURCE.with_name('recover.py')]
        helper_sources = {str(path): digest(path) for path in helpers}
        for path in helpers:
            shutil.copyfile(path, session / path.name)
        validation_path = WORK / 'finalization_validation.json'
        validation = read(validation_path)
        if validation['status'] != 'PASS' or validation['sources_sha256'] != helper_sources:
            raise ValueError('Recovery helper tests are absent or belong to different sources')
        recovery = dict(version=1, created_at=now(), reason='Training and all development inference completed; final preservation audit detected later Android-only work',
                        audit=audit, archive=archive, helper_sources=helper_sources,
                        validation=validation, validation_sha256=digest(validation_path),
                        optimizer_replayed=False, development_generation_replayed=False,
                        verification_overlay='Only run_context.verify_preservation is replaced for post-training scripts; it strictly checks original frozen inputs, retained computation evidence, and the captured current Android boundary',
                        original_preservation_snapshot_overwritten=False,
                        physical_device_inference='NOT_RUN')
        exclusive_json(session / 'recovery.json', recovery)
        binding = digest(session / 'recovery.json')
        verified_session(session, binding)
        return session, binding


def finalize_training(session, binding, recovery):
    for name in ('training.json', 'selection.json', 'pipeline.json'):
        if digest(RUN / name) != recovery['archive'][name]['sha256']:
            raise ValueError('Runtime metadata changed before finalization: ' + name)
    training, selected = read(RUN / 'training.json'), read(RUN / 'selection.json')
    previous = read(session / 'originals/pipeline.json')
    audit = recovery['audit']
    duration = (datetime.fromisoformat(previous['failed_at']) - datetime.fromisoformat(training['started_at'])).total_seconds()
    if duration < audit['optimizer']['optimizer_seconds'] + audit['completed_development_segment_seconds']:
        raise ValueError('Recorded training wall time contradicts completed computation')
    selected.update(status='COMPLETE', adapter_sha256=audit['adapter_sha256'],
                    finalized_at=now(), finalization_recovery_sha256=binding)
    training.update(status='COMPLETE', completed_at=now(), computation_finished_at=previous['failed_at'],
                    completed_optimizer_steps=1128, selected_step=audit['selected_step'],
                    adapter_sha256=audit['adapter_sha256'], training_seconds=duration,
                    training_seconds_scope='UTC interval from retained training started_at to failed finalization; includes optimizer, development and checkpoint overhead; recovery downtime excluded',
                    optimizer_seconds=audit['optimizer']['optimizer_seconds'],
                    completed_development_segment_seconds=audit['completed_development_segment_seconds'],
                    max_cuda_memory_bytes=None,
                    max_cuda_memory_scope='Not retained by the original trainer before its final preservation audit failed',
                    finalization_recovery=dict(manifest=str(session / 'recovery.json'), sha256=binding,
                                               optimizer_replayed=False, development_generation_replayed=False),
                    preservation=verify_boundary(ROOT, audit['boundary']))
    write(RUN / 'selection.json', selected)
    write(RUN / 'training.json', training)


def disclose_recovery(session, binding, recovery):
    quality_path = RUN / 'release/quality_manifest.json'
    report_path = ROOT / 'docs/CALENDAR_ASSISTANT_V12_66_QWEN3_17B_RESULTS.md'
    for path, name in ((quality_path, 'quality_before_recovery_disclosure.json'), (report_path, 'results_before_recovery_disclosure.md')):
        with (session / name).open('xb') as stream:
            stream.write(path.read_bytes())
    quality = read(quality_path)
    quality['finalization_recovery'] = dict(manifest=str(session / 'recovery.json'), sha256=binding,
                                           optimizer_replayed=False, development_generation_replayed=False,
                                           completed_computation_audit=recovery['audit']['status'],
                                           recovery_validation=recovery['validation'])
    quality['timing']['training_seconds_scope'] = read(RUN / 'training.json')['training_seconds_scope']
    write(quality_path, quality)
    boundary = recovery['audit']['boundary']
    with report_path.open('a', encoding='utf-8', newline='\n') as stream:
        stream.write('\n## Завершение после ошибки проверки Android\n\n'
                     'Все 1128 обновлений и шесть разработческих проверок уже были завершены до остановки. '
                     'Повторных обновлений весов и генерации разработческих ответов не выполнялось. '
                     'Проверены 175 замороженных входов, все контрольные точки и 1392 сохранённых ответа; '
                     'выбор шага 752 повторно рассчитан по исходным ответам.\n\n'
                     f"Первоначальный снимок проекта имеет статус DIFFERS: изменены {len(boundary['android_changed'])} "
                     f"Android-файлов, добавлены {len(boundary['android_added'])}. Данные, правила, учебные скрипты "
                     'и исходная модель сохранены. Исходный снимок и журналы сохранены побайтно; '
                     'для оставшихся этапов отдельно проверялось совпадение Android с состоянием на начало восстановления. '
                     'Это не является подтверждением неизменности Android за всё время обучения.\n\n'
                     'Пиковая память полного обучения не была записана до исходной ошибки; значение не восстанавливалось по дымовой проверке. '
                     'Время обучения рассчитано по сохранённым UTC-отметкам без перерыва до восстановления.\n\n'
                     f"Доказательства: `{session / 'recovery.json'}`, SHA-256 `{binding}`.\n")
    exclusive_json(session / 'disclosure.json', dict(completed_at=now(), quality_sha256=digest(quality_path), report_sha256=digest(report_path)))


def execute_stage(session, binding, script):
    recovery = verified_session(session, binding)
    allowed = {item[1]: item[2] for item in STAGES}
    if script not in allowed:
        raise ValueError('Unapproved recovery stage')
    target = HERE / script
    if str(target) not in recovery['audit']['frozen_inputs']:
        raise ValueError('Stage script is not part of the original frozen training inputs')
    def preservation():
        # The stage wrapper checks all retained evidence before and after the
        # unchanged script. Its internal preservation calls need only recheck
        # the frozen inputs, archive and current Android/data boundary.
        verified_session(session, binding, retained_evidence=False)
        return verify_boundary(ROOT, recovery['audit']['boundary'])
    context.verify_preservation = preservation
    sys.argv = [str(target), *allowed[script]]
    runpy.run_path(str(target), run_name='__main__')
    verified_session(session, binding)


def execute_controller(session, binding):
    with controller_lock():
        recovery = verified_session(session, binding)
        previous = read(session / 'originals/pipeline.json')
        power = PowerGuard(CONFIG['gpu_power'], RUN / (session.name + '_gpu_power_samples.jsonl'))
        power.check(force=True)
        record = dict(previous, status='RUNNING', resumed_at=now(), controller_pid=os.getpid(), child_pid=None,
                      stage='finalization_recovery', steps=[], recovery_manifest=str(session / 'recovery.json'),
                      recovery_sha256=binding, preservation=verify_boundary(ROOT, recovery['audit']['boundary']))
        # Original metadata must still match before any status rewrite.
        finalize_training(session, binding, recovery)
        record['steps'].append(dict(stage='training_finalization', completed_at=now(), exit_code=0,
                                    optimizer_replayed=False, development_generation_replayed=False))
        write(RUN / 'pipeline.json', record)
        try:
            for stage, script, arguments in STAGES:
                power.check(force=True)
                command = [sys.executable, '-X', 'utf8', '-B', str(SOURCE), '--session', str(session),
                           '--binding', binding, '--stage', script]
                step = dict(stage=stage, original_script_sha256=digest(HERE / script), command=command,
                            original_arguments=arguments, started_at=now())
                with (session / (stage + '.stdout.log')).open('xb') as out, (session / (stage + '.stderr.log')).open('xb') as err:
                    child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                             creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                    record.update(stage=stage, child_pid=child.pid)
                    write(RUN / 'pipeline.json', record)
                    print(stage + ': started', flush=True)
                    step['exit_code'] = child.wait()
                step['completed_at'] = now()
                record['steps'].append(step)
                write(RUN / 'pipeline.json', record)
                if step['exit_code']:
                    raise RuntimeError(stage + ' failed; original and recovery evidence retained')
            verified_session(session, binding)
            disclose_recovery(session, binding, recovery)
            record.update(status='COMPLETE', completed_at=now(), child_pid=None,
                          quality_status=read(RUN / 'quantization_comparison.json')['q4']['quality_status'],
                          model=read(RUN / 'release/gguf_manifest.json')['model'],
                          preservation=verify_boundary(ROOT, recovery['audit']['boundary']))
            write(RUN / 'pipeline.json', record)
            print('Completed training finalized; GGUF and independent comparison complete', flush=True)
        except BaseException:
            record.update(status='FAILED', failed_at=now(), error=traceback.format_exc())
            write(RUN / 'pipeline.json', record)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-only', action='store_true')
    parser.add_argument('--launch-hidden', action='store_true')
    parser.add_argument('--controller', action='store_true')
    parser.add_argument('--stage')
    parser.add_argument('--session', type=Path)
    parser.add_argument('--binding')
    args = parser.parse_args()
    if args.audit_only:
        result = audit_completed()
        print(json.dumps({key: result[key] for key in ('status', 'completed_optimizer_steps', 'selected_step',
              'optimizer', 'development_evaluations', 'retained_development_predictions', 'source_files_checked', 'baseline_files_checked')}, indent=2))
    elif args.launch_hidden:
        session, binding = prepare()
        command = [sys.executable, '-X', 'utf8', '-B', str(SOURCE), '--controller', '--session', str(session), '--binding', binding]
        with (session / 'controller.stdout.log').open('xb') as out, (session / 'controller.stderr.log').open('xb') as err:
            process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        exclusive_json(session / 'controller_launch.json', dict(started_at=now(), pid=process.pid, command=command,
                                                               session=str(session), binding=binding))
        print(json.dumps(dict(session=str(session), pid=process.pid, binding=binding), indent=2))
    elif args.controller and args.session and args.binding:
        execute_controller(args.session, args.binding)
    elif args.stage and args.session and args.binding:
        execute_stage(args.session, args.binding, args.stage)
    else:
        parser.error('Use --audit-only, --launch-hidden, or a bound controller/stage invocation')


if __name__ == '__main__':
    main()
