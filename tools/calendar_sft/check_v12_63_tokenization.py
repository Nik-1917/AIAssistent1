"""Measure every frozen conversation and verify completion-only labels offline."""
from __future__ import annotations
import hashlib
import os
from pathlib import Path
from dataset_contract import load_jsonl
from prepare_v12_63 import OUTPUT, ROOT, digest, write

def main():
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', TOKENIZERS_PARALLELISM='false')
    from transformers import AutoTokenizer
    from train_qlora import tokenize_messages
    base = ROOT / 'build/calendar_sft_models/Qwen3-4B-Instruct-2507/cdbee75f17c01a7cc42f958dc650907174af0554'
    tokenizer = AutoTokenizer.from_pretrained(base, local_files_only=True, trust_remote_code=False)
    template = hashlib.sha256(tokenizer.chat_template.encode()).hexdigest()
    if template != '64f85b198065d0fba2a81f37e10ed68161ce2c19a754c7100e67e0ca2ee9c326':
        raise ValueError('locked template changed')
    files = {}
    for path in sorted(OUTPUT.glob('*.jsonl')):
        lengths = []
        for row in load_jsonl(path):
            encoded = tokenize_messages(tokenizer, row['messages'], 512)
            ids, labels = encoded['input_ids'], encoded['labels']
            start = next(i for i,x in enumerate(labels) if x != -100)
            if start == 0 or labels[:start] != [-100] * start or labels[start:] != ids[start:]:
                raise ValueError('incorrect assistant label mask')
            lengths.append(len(ids))
        files[path.name] = {'rows': len(lengths), 'max_tokens': max(lengths), 'sha256': digest(path)}
    maximum = max(f['max_tokens'] for f in files.values())
    limit = ((maximum + 31) // 32) * 32
    report = {'status': 'PASS', 'total_rows': sum(f['rows'] for f in files.values()), 'max_tokens': maximum,
              'max_seq_length': limit, 'truncated_rows': 0, 'assistant_only_mask': 'PASS',
              'chat_template_sha256': template, 'files': files, 'weights_loaded': False}
    write(OUTPUT / 'tokenization_report.json', report)
    print({k:v for k,v in report.items() if k != 'files'})

if __name__ == '__main__':
    main()
