"""Verify every V12.62 conversation against the unchanged training token limit."""
import argparse
import hashlib
import json
import os
from pathlib import Path

from dataset_contract import load_jsonl
from prepare_v12_62 import OUTPUT, digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tokenizer", type=Path, required=True)
    args = parser.parse_args()
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", TOKENIZERS_PARALLELISM="false")
    from transformers import AutoTokenizer
    from train_qlora import tokenize_messages

    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, local_files_only=True)
    template_hash = hashlib.sha256(tokenizer.chat_template.encode("utf-8")).hexdigest()
    if template_hash != "64f85b198065d0fba2a81f37e10ed68161ce2c19a754c7100e67e0ca2ee9c326":
        raise ValueError("locked source chat template changed")
    changed = {x["key"] for x in json.loads((OUTPUT / "correction_log.json").read_text(encoding="utf-8"))}
    files = {}
    failures = []
    for path in sorted(OUTPUT.glob("*.jsonl")):
        lengths, changed_lengths = [], []
        for line, row in enumerate(load_jsonl(path), 1):
            key = f"{path.stem}:{line}"
            try:
                encoded = tokenize_messages(tokenizer, row["messages"], 256)
                ids, labels = encoded["input_ids"], encoded["labels"]
                boundary = next(i for i, label in enumerate(labels) if label != -100)
                if boundary == 0 or labels[:boundary] != [-100] * boundary or labels[boundary:] != ids[boundary:]:
                    raise ValueError("invalid assistant-only mask")
                lengths.append(len(ids))
                if key in changed:
                    changed_lengths.append(len(ids))
            except (ValueError, StopIteration) as error:
                failures.append({"key": key, "case_id": row.get("case_id"), "error": str(error)})
        files[path.name] = {"rows": len(lengths), "max_tokens": max(lengths, default=0),
                            "changed_rows": len(changed_lengths), "changed_max_tokens": max(changed_lengths, default=None),
                            "sha256": digest(path)}
    report = {"status": "FAIL" if failures else "PASS", "max_seq_length": 256,
              "total_rows": sum(f["rows"] for f in files.values()), "truncated_rows": 0,
              "assistant_only_mask": "PASS" if not failures else "SEE_FAILURES", "chat_template_sha256": template_hash,
              "files": files, "failures": failures, "weights_loaded": False, "training": "NOT_RUN", "model_evaluation": "NOT_RUN"}
    write_json(OUTPUT / "tokenization_report.json", report)
    if failures:
        raise ValueError(json.dumps(failures, ensure_ascii=False))
    if report["total_rows"] != 5127 or sum(x["changed_rows"] for x in files.values()) != 749:
        raise ValueError("unexpected coverage")
    print(f"PASS: {report['total_rows']} rows, 749 changed replies, no truncation")


if __name__ == "__main__":
    main()
