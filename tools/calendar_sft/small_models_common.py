"""Frozen data and runtime-message transport for the two compact candidates."""
from __future__ import annotations
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'build/calendar_small_models_20261003'
DATA = ROOT / 'docs/calendar_sft_v12_63'
NAMES = {'qwen3_1_7b': 'Qwen3-1.7B', 'qwen35_2b': 'Qwen3.5-2B'}
SEED = 20261003

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))

def digest(path):
    h = sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for attempt in range(12):
        try:
            os.replace(temporary, path)
            return
        except PermissionError:
            if attempt == 11:
                raise
            time.sleep(0.2 * (attempt + 1))

def now():
    return datetime.now(timezone.utc).isoformat()

def prompt(messages, android=True):
    # Verbatim transport in LlamatikEngine.buildPrompt. Only the optional
    # newline differs from the existing 4B tokenizer transport; text is intact.
    separator = '\n' if android else ''
    return ''.join('<|im_start|>' + m['role'] + '\n' + m['content'] + separator + '<|im_end|>\n'
                   for m in messages) + '<|im_start|>assistant\n'

def encode_row(tokenizer, row, android=True, max_length=384):
    messages = row['messages']
    if [m['role'] for m in messages] != ['system', 'user', 'assistant']:
        raise ValueError('Expected complete system/user/assistant conversation')
    prefix = prompt(messages[:-1], android)
    text = prefix + messages[-1]['content'] + ('\n' if android else '') + '<|im_end|>\n'
    ids = tokenizer.encode(text, add_special_tokens=False)
    prefix_ids = tokenizer.encode(prefix, add_special_tokens=False)
    if ids[:len(prefix_ids)] != prefix_ids:
        raise ValueError('Assistant boundary crosses a token')
    if len(ids) > max_length:
        raise ValueError(f'Token count {len(ids)} exceeds {max_length}; truncation is forbidden')
    labels = [-100] * len(prefix_ids) + ids[len(prefix_ids):]
    decoded = tokenizer.decode(ids[len(prefix_ids):], skip_special_tokens=False)
    if decoded != messages[-1]['content'] + ('\n' if android else '') + '<|im_end|>\n':
        raise ValueError('Assistant supervision changed during tokenization')
    return {'input_ids': ids, 'attention_mask': [1] * len(ids), 'labels': labels}

def freeze_inputs():
    destination = WORK / 'protected_inputs.json'
    if destination.exists():
        result = read(destination)
        for rel, expected in result['files'].items():
            if digest(ROOT / rel) != expected:
                raise ValueError('Protected input changed: ' + rel)
        return result
    files = []
    for folder in (ROOT / 'docs', ROOT / 'tools/calendar_sft', ROOT / 'app/src', ROOT / 'gradle'):
        files.extend(p for p in folder.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc')
    result = {'created_at': now(), 'files': {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(files)}}
    write(destination, result)
    return result

def verify_source(key):
    lock = read(WORK / (NAMES[key] + '_source_lock.json'))
    folder = Path(lock['directory'])
    for name, item in lock['files'].items():
        if digest(folder / name) != item['sha256']:
            raise ValueError('Source changed: ' + name)
    return lock, folder
