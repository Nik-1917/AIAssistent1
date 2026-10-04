"""Offline Qwen3.5-2B token/mask audit, including actual Android system text."""
from __future__ import annotations
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import re

from prepare_v12_65 import ROOT, OUTPUT, read, digest


def main(output):
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", TOKENIZERS_PARALLELISM="false")
    from transformers import AutoTokenizer
    from small_models_common import encode_row
    lock_path = ROOT / "build/calendar_small_models_20261003_v2/Qwen3.5-2B_source_lock.json"
    lock = read(lock_path)
    model = Path(lock["directory"])
    for name in ("tokenizer.json", "tokenizer_config.json", "merges.txt", "vocab.json"):
        if digest(model / name) != lock["files"][name]["sha256"]:
            raise ValueError(f"source tokenizer changed: {name}")
    tokenizer = AutoTokenizer.from_pretrained(model, local_files_only=True, trust_remote_code=False)
    reports = {}
    variants = ("standard_chatml", "android_transport", "android_runtime")
    for path in sorted(OUTPUT.glob("*.jsonl")):
        rows = [json.loads(line) for line in path.read_bytes().splitlines()]
        maximum = dict.fromkeys(variants, 0)
        for row in rows:
            for variant in variants:
                candidate = deepcopy(row)
                if variant == "android_runtime":
                    system = candidate["messages"][0]["content"]
                    match = re.fullmatch(r"Сегодня дата и время:(\S+) \((.+)\) (\S+) Europe/Samara ответ JSON", system)
                    if not match:
                        raise ValueError("unexpected corpus context")
                    candidate["messages"][0]["content"] = f"cегодня {match[1]} {match[3]} день недели {match[2]} ответ JSON"
                encoded = encode_row(tokenizer, candidate, android=variant != "standard_chatml", max_length=384)
                ids, labels = encoded["input_ids"], encoded["labels"]
                start = next(i for i, value in enumerate(labels) if value != -100)
                if start == 0 or any(v != -100 for v in labels[:start]) or labels[start:] != ids[start:]:
                    raise ValueError("incorrect completion-only mask")
                maximum[variant] = max(maximum[variant], len(ids))
        reports[path.name] = dict(rows=len(rows), sha256=digest(path), max_tokens=maximum)
    report = dict(status="PASS", model_repository=lock["repository"], source_revision=lock["revision"],
                  tokenizer_lock_sha256=digest(lock_path), source_encoder_sha256=digest(ROOT / "tools/calendar_sft/small_models_common.py"),
                  total_rows=sum(r["rows"] for r in reports.values()),
                  encoded_variants=sum(r["rows"] for r in reports.values()) * len(variants),
                  max_tokens={v: max(r["max_tokens"][v] for r in reports.values()) for v in variants},
                  max_length=384, truncated_rows=0, assistant_only_mask="PASS", weights_loaded=False,
                  training="NOT_RUN", inference="NOT_RUN", files=reports)
    if output.exists():
        raise ValueError("refusing to overwrite tokenization evidence")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    main(parser.parse_args().output)
