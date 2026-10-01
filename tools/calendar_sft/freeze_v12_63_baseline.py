"""Freeze the existing workspace before the narrowly authorized V12.63 edits."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/calendar_v12_63_manual/baseline_lock.json'

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def main():
    if OUT.exists():
        raise SystemExit('Baseline already frozen; refusing replacement')
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    files = {}
    for relative in paths:
        path = ROOT / relative
        if relative and path.is_file():
            files[relative] = digest(path)
    release = ROOT / 'build/calendar_sft_qwen3_v12_62_gguf_20260929/calendar-assistant-v12.62-Q4_K_M.gguf'
    files[str(release.relative_to(ROOT)).replace('\\', '/')] = digest(release)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({'created': '2026-10-01', 'git_head': subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(), 'files': files,
        'authorized_existing_file_edits': ['docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md']},
        ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    snapshot = ROOT / 'build/v12_63_preparation/baseline_rules'
    snapshot.mkdir(parents=True, exist_ok=True)
    for path in (ROOT / 'docs').glob('CALENDAR_ASSISTANT*.md'):
        if 'V12_63' not in path.name:
            (snapshot / path.name).write_bytes(path.read_bytes())
    print(json.dumps({'frozen_files': len(files), 'baseline': str(OUT)}))

if __name__ == '__main__':
    main()
