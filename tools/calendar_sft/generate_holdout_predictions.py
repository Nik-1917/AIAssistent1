"""Generate deterministic JSON responses for calendar-assistant holdout cases.

The base checkpoint and a LoRA adapter are run separately so their outputs can
be scored with ``evaluate_predictions.py``.  Loading is local-only: this tool
never downloads model files and never writes model weights.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import sys
from typing import Any

from dataset_contract import DatasetContractError, file_sha256, load_jsonl


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--holdout", required=True, type=Path)
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument(
        "--adapter-dir",
        type=Path,
        help="Local LoRA adapter directory. Omit to score the base checkpoint.",
    )
    parser.add_argument(
        "--run-manifest",
        type=Path,
        help="Optional metadata file for reproducibility.",
    )
    parser.add_argument("--max-new-tokens", type=int, default=128)
    parser.add_argument("--batch-size", type=int, default=4)
    return parser.parse_args()


def _sha256_text(value: str) -> str:
    return sha256(value.encode("utf-8")).hexdigest()


def _load_model(model_dir: Path, adapter_dir: Path | None) -> tuple[Any, Any, dict[str, Any]]:
    try:
        import torch
        from peft import PeftModel
        from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
    except ImportError as error:
        raise RuntimeError(f"Inference dependencies are unavailable: {error}") from error

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU is required for this 4-bit local evaluation")
    compute_capability = torch.cuda.get_device_capability()
    compute_dtype = torch.bfloat16 if compute_capability[0] >= 8 else torch.float16
    tokenizer = AutoTokenizer.from_pretrained(model_dir, local_files_only=True, trust_remote_code=False)
    tokenizer.pad_token = tokenizer.eos_token
    tokenizer.padding_side = "left"
    quantization_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=compute_dtype,
    )
    model = AutoModelForCausalLM.from_pretrained(
        model_dir,
        local_files_only=True,
        trust_remote_code=False,
        device_map="auto",
        quantization_config=quantization_config,
    )
    if adapter_dir is not None:
        model = PeftModel.from_pretrained(model, adapter_dir, local_files_only=True)
    model.eval()
    model.generation_config.do_sample = False
    model.generation_config.temperature = None
    model.generation_config.top_p = None
    model.generation_config.top_k = None
    return model, tokenizer, {
        "adapter_sha256": file_sha256(adapter_dir / "adapter_model.safetensors") if adapter_dir else None,
        "compute_capability": list(compute_capability),
        "compute_dtype": str(compute_dtype).removeprefix("torch."),
        "device": torch.cuda.get_device_name(0),
        "tokenizer_chat_template_sha256": _sha256_text(tokenizer.chat_template),
        "tokenizer_json_sha256": file_sha256(model_dir / "tokenizer.json"),
    }


def _generate_outputs(
    model: Any,
    tokenizer: Any,
    message_batches: list[list[dict[str, str]]],
    max_new_tokens: int,
) -> list[str]:
    import torch

    prompt = tokenizer.apply_chat_template(
        [messages[:-1] for messages in message_batches],
        tokenize=True,
        add_generation_prompt=True,
        return_tensors="pt",
        return_dict=True,
        padding=True,
    ).to("cuda")
    input_length = prompt["input_ids"].shape[1]
    with torch.inference_mode():
        generated = model.generate(
            **prompt,
            do_sample=False,
            max_new_tokens=max_new_tokens,
            pad_token_id=tokenizer.pad_token_id,
        )
    return [
        tokenizer.decode(generated[index, input_length:], skip_special_tokens=True).strip()
        for index in range(len(message_batches))
    ]


def _write_jsonl(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows),
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    if args.max_new_tokens <= 0 or args.batch_size <= 0:
        print("--max-new-tokens and --batch-size must be positive", file=sys.stderr)
        return 2
    try:
        rows = load_jsonl(args.holdout)
        model, tokenizer, environment = _load_model(args.model_dir, args.adapter_dir)
    except (DatasetContractError, OSError, RuntimeError) as error:
        print(f"Cannot prepare holdout generation: {error}", file=sys.stderr)
        return 2

    predictions: list[dict[str, str]] = []
    for start in range(0, len(rows), args.batch_size):
        batch = rows[start : start + args.batch_size]
        case_ids = [row.get("case_id") for row in batch]
        if not all(case_ids):
            print(f"Holdout row {start + 1} has no case_id", file=sys.stderr)
            return 2
        outputs = _generate_outputs(model, tokenizer, [row["messages"] for row in batch], args.max_new_tokens)
        for offset, (case_id, output) in enumerate(zip(case_ids, outputs), start=1):
            predictions.append({"case_id": case_id, "output": output})
            print(
                json.dumps(
                    {"completed": start + offset, "total": len(rows), "case_id": case_id},
                    ensure_ascii=False,
                )
            )

    _write_jsonl(args.predictions, predictions)
    if args.run_manifest:
        run_manifest = {
            "format_version": 1,
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
            "model_dir": str(args.model_dir.resolve()),
            "holdout_sha256": file_sha256(args.holdout),
            "predictions_sha256": file_sha256(args.predictions),
            "total_cases": len(rows),
            "max_new_tokens": args.max_new_tokens,
            "batch_size": args.batch_size,
            "adapter_dir": str(args.adapter_dir.resolve()) if args.adapter_dir else None,
            "environment": environment,
        }
        args.run_manifest.parent.mkdir(parents=True, exist_ok=True)
        args.run_manifest.write_text(
            json.dumps(run_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
