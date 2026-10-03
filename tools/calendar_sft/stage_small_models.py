"""Download and verify the two explicitly selected official model snapshots."""
from __future__ import annotations
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
MODELS = {
    'Qwen/Qwen3-1.7B': '70d244cc86ccca08cf5af4e1e306ecf908b1ad5e',
    'Qwen/Qwen3.5-2B': '15852e8c16360a2fea060d615a32b45270f8a8fc',
}

def digest(path):
    h = sha256()
    with Path(path).open('rb') as f:
        for part in iter(lambda: f.read(8 * 1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()

def main():
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    os.environ['HF_HUB_DISABLE_IMPLICIT_TOKEN'] = '1'
    os.environ['HF_HUB_DISABLE_XET'] = '1'
    from huggingface_hub import HfApi, hf_hub_download
    output = ROOT / 'build/calendar_small_models_20261003'
    output.mkdir(parents=True, exist_ok=True)
    for repository, revision in MODELS.items():
        name = repository.split('/')[-1]
        target = ROOT / 'build/calendar_sft_models' / name / revision
        lock_path = output / (name + '_source_lock.json')
        if lock_path.exists():
            lock = json.loads(lock_path.read_text(encoding='utf-8'))
            if lock['revision'] != revision or any(digest(target / n) != m['sha256'] for n, m in lock['files'].items()):
                raise ValueError('Completed snapshot does not match its lock')
            print(name + ': already verified', flush=True)
            continue
        info = HfApi(token=False).model_info(repository, revision=revision, files_metadata=True)
        if info.sha != revision:
            raise ValueError('Upstream revision differs')
        print(name + ': downloading pinned official snapshot', flush=True)
        files = {}
        # Configuration/tokenizer files first, so compatibility can be inspected
        # while the large Safetensors download is still running.
        for sibling in sorted(info.siblings, key=lambda s: (s.rfilename.endswith('.safetensors'), s.rfilename)):
            if sibling.rfilename == '.gitattributes':
                continue
            path = Path(hf_hub_download(repository, sibling.rfilename, revision=revision, local_dir=target, token=False))
            actual = digest(path)
            if path.stat().st_size != sibling.size:
                raise ValueError('Wrong size: ' + sibling.rfilename)
            if sibling.lfs and actual != sibling.lfs.sha256:
                raise ValueError('Wrong upstream SHA256: ' + sibling.rfilename)
            files[sibling.rfilename] = {'bytes': sibling.size, 'sha256': actual,
                'upstream_lfs_sha256': sibling.lfs.sha256 if sibling.lfs else None}
        license_text = (target / 'LICENSE').read_text(encoding='utf-8')
        if 'Apache License' not in license_text or 'Version 2.0' not in license_text:
            raise ValueError('Unexpected source license')
        config = json.loads((target / 'config.json').read_text(encoding='utf-8'))
        lock = {'repository': repository, 'revision': revision, 'directory': str(target),
            'retrieved_at_utc': datetime.now(timezone.utc).isoformat(), 'files': files,
            'config': config, 'license': 'Apache-2.0', 'remote_code_executed': False}
        lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(name + ': complete; all files verified', flush=True)

if __name__ == '__main__':
    main()
