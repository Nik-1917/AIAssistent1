"""Evaluate the built Q4_K_M on untouched intent, reply and offset holdouts."""
import json
from pathlib import Path
import socket
import subprocess
import time

from v12_66_training import ROOT, WORK, read, write, digest, now
from v12_66_evaluation import VERSION, grade, summary
from evaluate_v12_66 import prepared_inputs
from evaluate_v12_63_gguf import request


def main():
    run = WORK / 'qwen35_2b'
    manifest = read(run / 'release/gguf_manifest.json')
    model = Path(manifest['model']['file']).resolve()
    model_sha = digest(model)
    if manifest['status'] != 'COMPLETE' or manifest['model']['sha256'] != model_sha:
        raise ValueError('GGUF does not match completed build')
    folder = run / 'evaluation'
    folder.mkdir(exist_ok=False)
    (folder / 'cases').mkdir()
    packs = {name: prepared_inputs(split=name) for name in (
        'intent_holdout', 'reply_consistency_holdout', 'offset_reply_holdout')}
    cases = [case for pack in packs.values() for case in pack['cases']]
    if len(cases) != 200 or len({c['id'] for c in cases}) != len(cases):
        raise ValueError('Wrong independent evaluation inventory')
    decoding = dict(n_predict=192, temperature=0, top_k=1, top_p=1, min_p=0,
                    seed=20261004, repeat_penalty=1, cache_prompt=False, stream=False)
    binary = ROOT / 'build/llama-b10621-bin-win-cpu-x64/llama-server.exe'
    write(folder / 'inputs.json', dict(grader_version=VERSION, model=str(model), model_sha256=model_sha,
          packs=packs, decoding=decoding, server_sha256=digest(binary), grammar_constraint=False,
          physical_device_inference='NOT_RUN'))
    with socket.socket() as port_probe:
        port_probe.bind(('127.0.0.1', 0))
        port = port_probe.getsockname()[1]
    command = [str(binary), '--offline', '-m', str(model), '-c', '512', '-np', '1',
               '-t', '12', '-b', '512', '-ub', '512', '-ngl', '0', '--host', '127.0.0.1',
               '--port', str(port), '--no-webui', '--no-context-shift', '--cache-reuse', '0']
    records = []
    with (folder / 'server.stdout.log').open('xb') as out, (folder / 'server.stderr.log').open('xb') as err:
        server = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                  creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for _ in range(240):
                if server.poll() is not None:
                    raise RuntimeError('Owned GGUF server exited')
                try:
                    if request(port, '/health').get('status') == 'ok':
                        break
                except Exception:
                    pass
                time.sleep(0.5)
            else:
                raise TimeoutError('GGUF server startup timed out')
            if Path(request(port, '/props')['model_path']).resolve() != model:
                raise ValueError('Server loaded a different model')
            for index, case in enumerate(cases, 1):
                if server.poll() is not None:
                    raise RuntimeError('Owned GGUF server exited during evaluation')
                started = time.monotonic()
                response = request(port, '/completion', dict(decoding, prompt=case['prompt']))
                raw = response['content']
                if not isinstance(raw, str):
                    raise ValueError('Completion is not text')
                write(folder / 'cases' / (case['id'] + '.json'), dict(id=case['id'], output=raw.strip(),
                      model_sha256=model_sha, prompt_sha256=case['prompt_sha256'], raw_response=response,
                      runtime_seconds=time.monotonic() - started))
                records.append(grade(case, raw.strip()))
                write(folder / 'state.json', dict(status='RUNNING', completed=index,
                      total=len(cases), last_case=case['id'], server_pid=server.pid))
        finally:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill(); server.wait(timeout=15)
    report = dict(summary(records), execution_status='COMPLETE', model_sha256=model_sha,
                  completed_at=now(), quality_status='PASS' if all(r['passed'] for r in records) else 'FAILURES_FOUND',
                  cases=records, physical_device_inference='NOT_RUN')
    write(folder / 'report.json', report)
    write(folder / 'state.json', dict(status='COMPLETE', completed=len(records), total=len(cases),
                                     quality_status=report['quality_status']))
    print(json.dumps(dict(evaluated=len(records), passed=sum(r['passed'] for r in records),
                          quality_status=report['quality_status'])))


if __name__ == '__main__':
    main()
