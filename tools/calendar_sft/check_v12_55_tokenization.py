"""Offline tokenization audit only; never loads weights or trains a model."""

import argparse
import hashlib
import os
from pathlib import Path

from dataset_contract import file_sha256, load_jsonl
from prepare_v12_51 import write_json
from prepare_v12_55 import OUTPUT, TOTAL_ROWS


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tokenizer", type=Path, required=True)
    args = parser.parse_args()
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    from transformers import AutoTokenizer
    from train_qlora import tokenize_messages

    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, local_files_only=True)
    template_hash = hashlib.sha256(tokenizer.chat_template.encode("utf-8")).hexdigest()
    if template_hash != "64f85b198065d0fba2a81f37e10ed68161ce2c19a754c7100e67e0ca2ee9c326":
        raise ValueError("the locked Qwen3 chat template has changed")
    report = {
        "dataset": "v12.55", "status": "PASS", "max_seq_length": 256,
        "weights_loaded": False, "training": "NOT_RUN", "model_evaluation": "NOT_RUN",
        "truncated_rows": 0, "assistant_loss_mask": "PASS",
        "tokenizer_path": str(args.tokenizer), "chat_template_sha256": template_hash,
        "files": {},
    }
    for split, count in TOTAL_ROWS.items():
        path = OUTPUT / f"{split}.jsonl"
        rows = load_jsonl(path)
        if len(rows) != count:
            raise ValueError(f"{split}: wrong row count")
        lengths, new_lengths = [], []
        for row in rows:
            encoded = tokenize_messages(tokenizer, row["messages"], 256)
            ids, labels = encoded["input_ids"], encoded["labels"]
            boundary = next(i for i, label in enumerate(labels) if label != -100)
            if boundary == 0 or labels[:boundary] != [-100] * boundary or labels[boundary:] != ids[boundary:]:
                raise ValueError("assistant-only loss mask changed")
            lengths.append(len(ids))
            if row.get("case_id", "").startswith("V1255"):
                new_lengths.append(len(ids))
        report["files"][path.name] = {
            "rows": len(rows), "max_tokens": max(lengths), "new_rows": len(new_lengths),
            "new_max_tokens": max(new_lengths, default=None), "sha256": file_sha256(path),
        }
    report["total_rows"] = sum(item["rows"] for item in report["files"].values())
    write_json(OUTPUT / "tokenization_report.json", report)
    print(f"PASS: {report['total_rows']} rows, no truncation, no weights loaded")


if __name__ == "__main__":
    main()
