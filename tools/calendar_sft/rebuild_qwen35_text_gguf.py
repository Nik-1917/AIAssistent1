"""Re-export verified Qwen3.5 text weights without nonexistent MTP tensors."""
from __future__ import annotations

import gc
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

from build_small_model_v2 import BIN, SOURCE, inspect
from build_v12_2_gguf import verify_tools
from evaluate_v12_63_gguf import request
from small_models_v2_common import ROOT, WORK, digest, freeze_inputs, now, read, write


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_move(source, destination):
    boundary = WORK.resolve()
    require(boundary.is_relative_to(ROOT.resolve()), 'Unexpected workflow boundary')
    for path in (source, destination):
        require(path.resolve().is_relative_to(boundary), 'Move escapes workflow directory')
        require(not path.is_symlink() and not path.is_junction(), 'Move target is a link')
    require(source.exists() and not destination.exists(), 'Move would overwrite history')
    source.rename(destination)


def tensor_evidence(path):
    from gguf import GGUFReader
    reader = GGUFReader(path)
    result = {
        'block_count': int(reader.fields['qwen35.block_count'].contents()),
        'nextn_predict_layers': int(reader.fields['qwen35.nextn_predict_layers'].contents())
            if 'qwen35.nextn_predict_layers' in reader.fields else 0,
        'tensors': {tensor.name: {'shape': tensor.shape.tolist(), 'type': tensor.tensor_type.name,
                     'sha256': hashlib.sha256(memoryview(tensor.data)).hexdigest()}
                    for tensor in reader.tensors},
    }
    del reader
    gc.collect()
    return result


def load_smoke(model, folder):
    binary = BIN / 'llama-server.exe'
    port = 8765
    command = [str(binary), '--offline', '-m', str(model), '-c', '512', '-np', '1',
               '-t', '12', '-b', '512', '-ub', '512', '-ngl', '0', '--host', '127.0.0.1',
               '--port', str(port), '--no-webui', '--no-context-shift', '--cache-reuse', '0']
    with (folder / 'load.stdout.log').open('xb') as out, (folder / 'load.stderr.log').open('xb') as err:
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for _ in range(240):
                require(process.poll() is None, 'Text GGUF loader exited; inspect load.stderr.log')
                try:
                    if request(port, '/health').get('status') == 'ok':
                        break
                except Exception:
                    pass
                time.sleep(0.5)
            else:
                raise TimeoutError('Text GGUF startup timed out')
            properties = request(port, '/props')
            require(Path(properties['model_path']).resolve() == model.resolve(), 'Wrong smoke server model')
            require(process.poll() is None, 'Owned smoke server exited')
            evidence = {'status': 'COMPLETE', 'verified_at': now(), 'pid': process.pid,
                        'command': command, 'model_sha256': digest(model),
                        'server_sha256': digest(binary), 'properties': properties,
                        'generation_quality_test': False}
            write(folder / 'load_smoke.json', evidence)
            return evidence
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=15)


def main():
    freeze_inputs()
    tools = verify_tools()
    folder = WORK / 'qwen35_2b'
    training = read(folder / 'training.json')
    require(training['status'] == 'COMPLETE', 'Training is incomplete')
    require(digest(folder / 'adapter/adapter_model.safetensors') == training['adapter_sha256'],
            'Selected trained adapter changed')
    merged = folder / 'build/merged'
    merge = read(folder / 'build/merge.json')
    require(merge['adapter_sha256'] == training['adapter_sha256'], 'Merge used another adapter')
    for name, expected in merge['files'].items():
        require(digest(merged / name) == expected, 'Previously verified merged file changed: ' + name)
    config = read(merged / 'config.json')
    from safetensors import safe_open
    names = set()
    for shard in merged.glob('*.safetensors'):
        with safe_open(shard, framework='pt', device='cpu') as handle:
            names.update(handle.keys())
    layers = {int(match[1]) for name in names if (match := re.search(r'(?:^|\.)layers\.(\d+)\.', name))}
    require(layers == set(range(config['num_hidden_layers'])) == set(range(24)), 'Unexpected text layer inventory')
    require(not any('mtp.' in name or 'visual' in name for name in names), 'Merged weights contain MTP or vision')
    previous = read(folder / 'release/gguf_manifest.json')
    require(digest(previous['model']['file']) == previous['model']['sha256'], 'Failed GGUF changed')
    previous_f16 = Path(previous['intermediate']['file'])
    require(digest(previous_f16) == previous['intermediate']['sha256'], 'Failed F16 changed')
    build, staged = folder / 'build_text_only', folder / 'release_text_only'
    require(not build.exists() and not staged.exists(), 'Text export attempt already exists')
    build.mkdir()
    staged.mkdir()
    prefix = 'calendar-assistant-v12.63-Qwen3.5-2B'
    f16, final = build / (prefix + '-F16.gguf'), staged / (prefix + '-Q4_K_M.gguf')
    commands = [
        ('convert', [sys.executable, '-B', str(SOURCE / 'convert_hf_to_gguf.py'), str(merged),
                     '--outfile', str(f16), '--outtype', 'f16', '--no-mtp',
                     '--model-name', 'Calendar Assistant V12.63 Qwen3.5-2B']),
        ('quantize', [str(BIN / 'llama-quantize.exe'), str(f16), str(final), 'Q4_K_M', '12']),
    ]
    for name, command in commands:
        started = time.monotonic()
        with (build / (name + '.stdout.log')).open('xb') as out, (build / (name + '.stderr.log')).open('xb') as err:
            result = subprocess.run(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        write(build / (name + '.json'), {'command': command, 'exit_code': result.returncode,
                                       'seconds': time.monotonic() - started})
        require(result.returncode == 0, 'Text ' + name + ' failed')
    intermediate, artifact = inspect(f16, 'qwen35', 1), inspect(final, 'qwen35', 15)
    old_tensors, new_tensors, quantized = map(tensor_evidence, (previous_f16, f16, final))
    require(old_tensors['block_count'] == 25 and old_tensors['nextn_predict_layers'] == 1,
            'The observed MTP metadata defect is not present')
    require(old_tensors['tensors'] == new_tensors['tensors'], 'Text tensor payload changed during re-export')
    for evidence in (new_tensors, quantized):
        require(evidence['block_count'] == 24 and evidence['nextn_predict_layers'] == 0,
                'Text-only export still declares MTP layers')
        require(len(evidence['tensors']) == 320, 'Text tensor inventory changed')
    smoke = load_smoke(final, build)
    canonical = folder / 'release'
    failed = folder / 'release_failed_mtp'
    evaluation = WORK / 'qwen35_2b_evaluation'
    failed_evaluation = WORK / 'qwen35_2b_evaluation_failed_mtp'
    require(not list((evaluation / 'cases').glob('*.json')), 'Failed evaluation contains responses; review before moving')
    for name in ('LICENSE', 'MODIFICATIONS.txt'):
        shutil.copy2(canonical / name, staged / name)
    for notice in canonical.glob('NOTICE*'):
        if notice.is_file():
            shutil.copy2(notice, staged / notice.name)
    with (staged / 'MODIFICATIONS.txt').open('a', encoding='utf-8') as out:
        out.write('Re-exported unchanged merged text tensors with official --no-mtp; the CausalLM has no MTP weights.\n')
    (staged / 'SHA256SUMS.txt').write_text(artifact['sha256'] + '  ' + final.name + '\n', encoding='ascii')
    artifact['file'] = str(canonical / final.name)
    repair = {'status': 'COMPLETE', 'completed_at': now(), 'adapter_sha256': training['adapter_sha256'],
              'script_sha256': digest(__file__), 'unchanged_merged_files': merge['files'],
              'identical_f16_tensor_payloads': len(new_tensors['tensors']),
              'old_block_count': 25, 'old_mtp_layers': 1, 'new_block_count': 24, 'new_mtp_layers': 0,
              'old_artifact': previous['model'], 'preserved_old_artifact': str(failed / final.name),
              'new_artifact': artifact, 'loader_smoke': smoke,
              'preserved_failed_evaluation': str(failed_evaluation), 'tools': tools}
    write(build / 'repair.json', repair)
    write(staged / 'gguf_manifest.json', dict(previous, completed_at=now(), model=artifact,
          intermediate=intermediate, export_repair=str(build / 'repair.json'),
          export_repair_sha256=digest(build / 'repair.json'), inference_tests='NOT_RUN'))
    safe_move(evaluation, failed_evaluation)
    safe_move(canonical, failed)
    safe_move(staged, canonical)
    require(digest(canonical / final.name) == artifact['sha256'], 'Promoted file changed')
    require(digest(failed / final.name) == previous['model']['sha256'], 'Failed artifact was not preserved')
    print('Qwen3.5 text GGUF loaded successfully; 320 F16 tensor payloads unchanged; failed artifact preserved.')


if __name__ == '__main__':
    main()
