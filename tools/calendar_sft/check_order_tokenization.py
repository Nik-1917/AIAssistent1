"""Check lengths and loss masks offline; no weights loaded or model inference."""
import argparse
import hashlib
import os
from pathlib import Path

from dataset_contract import load_jsonl, file_sha256
from prepare_order_coverage import OUTPUT
from prepare_v12_51 import read_json, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tokenizer', type=Path, required=True)
    args = parser.parse_args()
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', TOKENIZERS_PARALLELISM='false')
    from transformers import AutoTokenizer
    from train_qlora import tokenize_messages
    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, local_files_only=True)
    template_hash = hashlib.sha256(tokenizer.chat_template.encode('utf-8')).hexdigest()
    if template_hash != '64f85b198065d0fba2a81f37e10ed68161ce2c19a754c7100e67e0ca2ee9c326':
        raise ValueError('locked template changed')
    index, files = read_json(OUTPUT / 'coverage_index.json'), {}
    for path in sorted(OUTPUT.glob('*.jsonl')):
        lengths, new_lengths = [], []
        for row in load_jsonl(path):
            try:
                data = tokenize_messages(tokenizer, row['messages'], 256)
            except ValueError as error:
                raise ValueError(f"{row.get('case_id')}: {error}") from error
            ids, labels = data['input_ids'], data['labels']
            boundary = next(i for i,v in enumerate(labels) if v != -100)
            if boundary == 0 or labels[:boundary] != [-100] * boundary or labels[boundary:] != ids[boundary:]:
                raise ValueError('loss mask changed')
            lengths.append(len(ids))
            if row.get('case_id') in index:
                new_lengths.append(len(ids))
        files[path.name] = {'rows':len(lengths), 'max_tokens':max(lengths), 'new_rows':len(new_lengths), 'new_max_tokens':max(new_lengths, default=None), 'sha256':file_sha256(path)}
    report = {'status':'PASS', 'max_seq_length':256, 'total_rows':sum(v['rows'] for v in files.values()), 'truncated_rows':0, 'assistant_only_mask':'PASS', 'chat_template_sha256':template_hash, 'files':files, 'weights_loaded':False, 'training':'NOT_RUN', 'model_evaluation':'NOT_RUN'}
    write_json(OUTPUT / 'tokenization_report.json', report)
    print(f"PASS: {report['total_rows']} rows, no truncation")


if __name__ == '__main__':
    main()
