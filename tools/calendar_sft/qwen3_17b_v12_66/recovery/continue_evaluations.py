"""Continue post-build stages using unique stage names and unchanged graders."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import traceback
from uuid import uuid4

import finalize_completed as original
from finalize_completed import ROOT, RUN, WORK, HERE, CONFIG, read, write, digest, now, context
from recovery.recover import controller_lock, process_exists, archive_runtime
from gpu_power import PowerGuard

SOURCE = Path(__file__).resolve()
# A script is reused by several distinct stages. Bind arguments to the stage,
# never to the script filename (which would collapse F16, Q4 and compare).
STAGES = {name: (script, arguments) for name, script, arguments in original.STAGES if name != 'gguf_build'}


def resolve_stage(name):
    if name not in STAGES:
        raise ValueError('Unapproved post-build stage: ' + name)
    script, arguments = STAGES[name]
    return script, list(arguments)


def guard(session, binding, *, retained_evidence=True):
    session = session.resolve()
    if session.parent != RUN.resolve() or not session.name.startswith('evaluation_recovery_'):
        raise ValueError('Invalid post-build recovery session')
    if digest(session / 'recovery.json') != binding:
        raise ValueError('Post-build recovery manifest changed')
    recovery = read(session / 'recovery.json')
    for path, sha in recovery['helper_sources'].items():
        if digest(path) != sha or digest(session / Path(path).name) != sha:
            raise ValueError('Post-build helper source changed')
    for name, item in recovery['archive'].items():
        if digest(session / 'originals' / name) != item['sha256']:
            raise ValueError('Post-build archive changed')
    original.verified_session(Path(recovery['parent_session']), recovery['parent_binding'], retained_evidence=retained_evidence)
    return recovery


def prepare():
    with controller_lock():
        previous = read(RUN / 'pipeline.json')
        if previous['status'] != 'FAILED' or previous['stage'] != 'f16_evaluation':
            raise ValueError('Expected failed first F16 stage')
        for key in ('controller_pid', 'child_pid'):
            if process_exists(previous.get(key)):
                raise ValueError('Previous pipeline is still alive')
        if any((RUN / name).exists() for name in ('evaluation_f16', 'evaluation_q4', 'quantization_comparison.json')):
            raise ValueError('Evaluation output already exists; use a cache-aware recovery')
        if [(s['stage'], s['exit_code']) for s in previous['steps']] != [('training_finalization', 0), ('gguf_build', 0), ('f16_evaluation', 1)]:
            raise ValueError('Unexpected previous pipeline sequence')
        parent = Path(previous['recovery_manifest'])
        original.verified_session(parent.parent, previous['recovery_sha256'])
        manifest = read(RUN / 'release/gguf_manifest.json')
        if manifest['status'] != 'COMPLETE' or manifest['loader_smoke']['status'] != 'COMPLETE':
            raise ValueError('Completed verified build is missing')
        for key in ('model', 'intermediate'):
            item = manifest[key]
            path = Path(item['file'])
            if digest(path) != item['sha256'] or path.stat().st_size != item['bytes']:
                raise ValueError('Completed GGUF changed')
        sources = [SOURCE, SOURCE.with_name('test_stage_arguments.py')]
        hashes = {str(p): digest(p) for p in sources}
        validation = read(WORK / 'stage_arguments_validation.json')
        if validation['status'] != 'PASS' or validation['sources_sha256'] != hashes:
            raise ValueError('Stage argument regression tests do not match the helper')
        session = RUN / ('evaluation_recovery_' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '_' + uuid4().hex[:8])
        session.mkdir(exist_ok=False)
        archive = archive_runtime(RUN, session / 'originals')
        for p in sources:
            shutil.copyfile(p, session / p.name)
        recovery = dict(created_at=now(), reason='The first F16 wrapper selected --compare by duplicate script filename before any evaluation output existed',
                        parent_session=str(parent.parent), parent_binding=previous['recovery_sha256'],
                        helper_sources=hashes, validation=validation, archive=archive,
                        stages={name: dict(script=script, arguments=arguments) for name, (script, arguments) in STAGES.items()},
                        training_replayed=False, build_replayed=False)
        original.exclusive_json(session / 'recovery.json', recovery)
        return session, digest(session / 'recovery.json')


def stage(session, binding, name):
    recovery = guard(session, binding)
    script, arguments = resolve_stage(name)
    target = HERE / script
    def preservation():
        guard(session, binding, retained_evidence=False)
        parent = read(Path(recovery['parent_session']) / 'recovery.json')
        return original.verify_boundary(ROOT, parent['audit']['boundary'])
    context.verify_preservation = preservation
    sys.argv = [str(target), *arguments]
    runpy.run_path(str(target), run_name='__main__')
    guard(session, binding)


def controller(session, binding):
    with controller_lock():
        recovery = guard(session, binding)
        previous = read(session / 'originals/pipeline.json')
        if digest(RUN / 'pipeline.json') != recovery['archive']['pipeline.json']['sha256']:
            raise ValueError('Pipeline changed before post-build recovery')
        power = PowerGuard(CONFIG['gpu_power'], RUN / (session.name + '_gpu_power_samples.jsonl'))
        record = dict(previous, status='RUNNING', controller_pid=os.getpid(), child_pid=None,
                      resumed_at=now(), stage='evaluation_recovery', steps=[s for s in previous['steps'] if s['exit_code'] == 0],
                      post_build_recovery_manifest=str(session / 'recovery.json'), post_build_recovery_sha256=binding,
                      previous_failure_archive=str(session / 'originals/pipeline.json'))
        record.pop('error', None)
        record.pop('failed_at', None)
        write(RUN / 'pipeline.json', record)
        try:
            for name in STAGES:
                power.check(force=True)
                script, arguments = resolve_stage(name)
                command = [sys.executable, '-X', 'utf8', '-B', str(SOURCE), '--session', str(session), '--binding', binding, '--stage-name', name]
                step = dict(stage=name, original_script_sha256=digest(HERE / script), original_arguments=arguments,
                            command=command, started_at=now())
                with (session / (name + '.stdout.log')).open('xb') as out, (session / (name + '.stderr.log')).open('xb') as err:
                    child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                             creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                    record.update(stage=name, child_pid=child.pid)
                    write(RUN / 'pipeline.json', record)
                    print(name + ': started; arguments=' + repr(arguments), flush=True)
                    step['exit_code'] = child.wait()
                step['completed_at'] = now()
                record['steps'].append(step)
                write(RUN / 'pipeline.json', record)
                if step['exit_code']:
                    raise RuntimeError(name + ' failed; all evidence retained')
            guard(session, binding)
            parent_session = Path(recovery['parent_session'])
            parent = read(parent_session / 'recovery.json')
            original.disclose_recovery(parent_session, recovery['parent_binding'], parent)
            quality_path = RUN / 'release/quality_manifest.json'
            report_path = ROOT / 'docs/CALENDAR_ASSISTANT_V12_66_QWEN3_17B_RESULTS.md'
            for path in (quality_path, report_path):
                shutil.copyfile(path, session / ('before_stage_disclosure_' + path.name))
            quality = read(quality_path)
            quality['post_build_recovery'] = dict(manifest=str(session / 'recovery.json'), sha256=binding,
                                                 validation=recovery['validation'], training_replayed=False, build_replayed=False)
            write(quality_path, quality)
            with report_path.open('a', encoding='utf-8', newline='\n') as stream:
                stream.write('\nОшибка первого служебного запуска F16 исправлена привязкой аргументов к имени этапа. '
                             'Обучение и сборка не повторялись. Исходный код ошибочного запуска и его журналы сохранены; '
                             'четыре проверки передачи аргументов прошли. '
                             f"Доказательства: `{session / 'recovery.json'}`, SHA-256 `{binding}`.\n")
            original.exclusive_json(session / 'disclosure.json', dict(quality_sha256=digest(quality_path), report_sha256=digest(report_path)))
            record.update(status='COMPLETE', completed_at=now(), child_pid=None,
                          quality_status=read(RUN / 'quantization_comparison.json')['q4']['quality_status'])
            write(RUN / 'pipeline.json', record)
            print('F16, Q4 and comparison completed', flush=True)
        except BaseException:
            record.update(status='FAILED', failed_at=now(), error=traceback.format_exc())
            write(RUN / 'pipeline.json', record)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launch-hidden', action='store_true')
    parser.add_argument('--controller', action='store_true')
    parser.add_argument('--session', type=Path)
    parser.add_argument('--binding')
    parser.add_argument('--stage-name')
    args = parser.parse_args()
    if args.launch_hidden:
        session, binding = prepare()
        command = [sys.executable, '-X', 'utf8', '-B', str(SOURCE), '--controller', '--session', str(session), '--binding', binding]
        with (session / 'controller.stdout.log').open('xb') as out, (session / 'controller.stderr.log').open('xb') as err:
            child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                     creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        original.exclusive_json(session / 'controller_launch.json', dict(pid=child.pid, command=command, started_at=now()))
        print(json.dumps(dict(session=str(session), pid=child.pid, binding=binding), indent=2))
    elif args.session and args.binding and args.stage_name:
        stage(args.session, args.binding, args.stage_name)
    elif args.session and args.binding and args.controller:
        controller(args.session, args.binding)
    else:
        parser.error('Use a launch or bound stage/controller')


if __name__ == '__main__':
    main()
