"""Run a reproducible QLoRA pilot for the local calendar-assistant model.

The script is intentionally offline after its inputs are staged. It accepts a
full locked Hugging Face snapshot, never a quantised Android GGUF file. It saves
only an adapter; merge and GGUF conversion are separate release steps.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from hashlib import sha256
import json
from pathlib import Path
import random
import sys
from typing import Any

from dataset_contract import DatasetContractError, file_sha256, load_jsonl


ROOT = Path(__file__).resolve().parents[2]
MODEL_MANIFEST_PATH = ROOT / "tools" / "calendar_sft" / "model_manifest.json"
LORA_TARGET_MODULES = (
    "q_proj",
    "k_proj",
    "v_proj",
    "o_proj",
    "gate_proj",
    "up_proj",
    "down_proj",
)


@dataclass(frozen=True)
class PilotConfig:
    max_seq_length: int
    epochs: float
    per_device_batch_size: int
    gradient_accumulation_steps: int
    learning_rate: float
    seed: int
    lora_rank: int
    lora_alpha: int
    lora_dropout: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path, help="locked BF16 Safetensors snapshot")
    parser.add_argument("--train-file", required=True, type=Path)
    parser.add_argument("--validation-file", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--max-seq-length", type=int, default=768)
    parser.add_argument("--epochs", type=float, default=3.0)
    parser.add_argument("--per-device-batch-size", type=int, default=1)
    parser.add_argument("--gradient-accumulation-steps", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=1.0e-4)
    parser.add_argument("--seed", type=int, default=20260825)
    parser.add_argument("--lora-rank", type=int, default=16)
    parser.add_argument("--lora-alpha", type=int, default=32)
    parser.add_argument("--lora-dropout", type=float, default=0.05)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="validate the source snapshot and tokenize all rows without loading model weights",
    )
    return parser.parse_args()


def load_manifest() -> dict[str, Any]:
    return json.loads(MODEL_MANIFEST_PATH.read_text(encoding="utf-8"))


def require_pilot_values(args: argparse.Namespace) -> PilotConfig:
    values = PilotConfig(
        max_seq_length=args.max_seq_length,
        epochs=args.epochs,
        per_device_batch_size=args.per_device_batch_size,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        learning_rate=args.learning_rate,
        seed=args.seed,
        lora_rank=args.lora_rank,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
    )
    if values.max_seq_length <= 0:
        raise DatasetContractError("max_seq_length must be positive")
    if values.epochs <= 0 or values.per_device_batch_size <= 0:
        raise DatasetContractError("epochs and per-device batch size must be positive")
    if values.gradient_accumulation_steps <= 0 or values.learning_rate <= 0:
        raise DatasetContractError("gradient accumulation and learning rate must be positive")
    if values.lora_rank <= 0 or values.lora_alpha <= 0 or not 0 <= values.lora_dropout < 1:
        raise DatasetContractError("invalid LoRA parameters")
    return values


def verify_snapshot(model_dir: Path, manifest: dict[str, Any]) -> None:
    if model_dir.suffix.lower() == ".gguf" or not model_dir.is_dir():
        raise DatasetContractError("--model-dir must be an extracted full Safetensors snapshot, never a GGUF file")
    missing = [name for name in manifest["source"]["expected_files"] if not (model_dir / name).is_file()]
    if missing:
        raise DatasetContractError(f"model snapshot is missing required files: {', '.join(missing)}")


def tokenize_messages(tokenizer: Any, messages: list[dict[str, str]], max_seq_length: int) -> dict[str, list[int]]:
    prompt_ids = tokenizer.apply_chat_template(
        messages[:-1],
        tokenize=True,
        add_generation_prompt=True,
    )
    full_ids = tokenizer.apply_chat_template(
        messages,
        tokenize=True,
        add_generation_prompt=False,
    )
    if full_ids[: len(prompt_ids)] != prompt_ids:
        raise DatasetContractError("checkpoint chat template does not make the assistant completion a prompt suffix")
    if len(full_ids) > max_seq_length:
        raise DatasetContractError(
            f"example has {len(full_ids)} tokens, exceeding max_seq_length={max_seq_length}; do not silently truncate JSON supervision",
        )
    return {
        "input_ids": full_ids,
        "attention_mask": [1] * len(full_ids),
        "labels": [-100] * len(prompt_ids) + full_ids[len(prompt_ids) :],
    }


def load_tokenized_rows(tokenizer: Any, path: Path, max_seq_length: int) -> list[dict[str, list[int]]]:
    rows = load_jsonl(path)
    return [tokenize_messages(tokenizer, row["messages"], max_seq_length) for row in rows]


def write_run_manifest(
    output_dir: Path,
    model_dir: Path,
    train_file: Path,
    validation_file: Path,
    pilot: PilotConfig,
    tokenizer: Any,
    config: Any,
    result: dict[str, Any],
) -> None:
    file_hashes = {
        path.name: file_sha256(path)
        for path in model_dir.iterdir()
        if path.is_file() and path.name in json.loads(MODEL_MANIFEST_PATH.read_text(encoding="utf-8"))["source"]["expected_files"]
    }
    run_manifest = {
        "format_version": 1,
        "source_lock_sha256": file_sha256(MODEL_MANIFEST_PATH),
        "source_files_sha256": dict(sorted(file_hashes.items())),
        "training_data_sha256": file_sha256(train_file),
        "validation_data_sha256": file_sha256(validation_file),
        "pilot": asdict(pilot),
        "tokenizer": {
            "class": tokenizer.__class__.__name__,
            "vocab_size": len(tokenizer),
            "eos_token": tokenizer.eos_token,
            "chat_template_sha256": sha256(tokenizer.chat_template.encode("utf-8")).hexdigest(),
        },
        "model": {
            "model_type": config.model_type,
            "vocab_size": config.vocab_size,
            "architectures": config.architectures,
        },
        "result": result,
    }
    (output_dir / "run_manifest.json").write_text(
        json.dumps(run_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    try:
        pilot = require_pilot_values(args)
        manifest = load_manifest()
        verify_snapshot(args.model_dir, manifest)
        train_rows = load_jsonl(args.train_file)
        validation_rows = load_jsonl(args.validation_file)
    except (DatasetContractError, OSError, json.JSONDecodeError) as error:
        print(f"Preflight failed: {error}", file=sys.stderr)
        return 2
    if manifest["license"]["status"] != "VERIFIED" and not args.dry_run:
        print(
            "Preflight failed: source license is UNVERIFIED. Record an authoritative licence chain and update the source lock before a real run.",
            file=sys.stderr,
        )
        return 2
    if args.output_dir.exists():
        print(f"Preflight failed: output directory already exists: {args.output_dir}", file=sys.stderr)
        return 2

    try:
        import torch
        from datasets import Dataset
        from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
        from transformers import AutoConfig, AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, Trainer, TrainingArguments
    except ImportError as error:
        print(f"Training dependencies are unavailable: {error}", file=sys.stderr)
        return 2

    tokenizer = AutoTokenizer.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False)
    config = AutoConfig.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False)
    if config.model_type != manifest["architecture"]["model_type"]:
        print(f"Preflight failed: model_type is {config.model_type!r}, expected qwen2", file=sys.stderr)
        return 2
    if config.vocab_size != manifest["architecture"]["vocab_size"]:
        print(f"Preflight failed: vocab_size is {config.vocab_size}, expected 147097", file=sys.stderr)
        return 2
    if not tokenizer.chat_template:
        print("Preflight failed: tokenizer has no chat_template", file=sys.stderr)
        return 2
    tokenizer.pad_token = tokenizer.eos_token

    try:
        train_inputs = [tokenize_messages(tokenizer, row["messages"], pilot.max_seq_length) for row in train_rows]
        validation_inputs = [tokenize_messages(tokenizer, row["messages"], pilot.max_seq_length) for row in validation_rows]
    except DatasetContractError as error:
        print(f"Tokenization failed: {error}", file=sys.stderr)
        return 2
    token_lengths = [len(item["input_ids"]) for item in train_inputs + validation_inputs]
    if args.dry_run:
        print(
            json.dumps(
                {
                    "train_rows": len(train_inputs),
                    "validation_rows": len(validation_inputs),
                    "max_tokens": max(token_lengths),
                    "min_tokens": min(token_lengths),
                    "chat_template_sha256": sha256(tokenizer.chat_template.encode("utf-8")).hexdigest(),
                },
                ensure_ascii=False,
                sort_keys=True,
            ),
        )
        return 0
    if not torch.cuda.is_available():
        print("Preflight failed: QLoRA pilot requires a CUDA GPU.", file=sys.stderr)
        return 2

    class CompletionCollator:
        def __call__(self, features: list[dict[str, list[int]]]) -> dict[str, Any]:
            max_length = max(len(feature["input_ids"]) for feature in features)
            batch_size = len(features)
            input_ids = torch.full((batch_size, max_length), tokenizer.pad_token_id, dtype=torch.long)
            attention_mask = torch.zeros((batch_size, max_length), dtype=torch.long)
            labels = torch.full((batch_size, max_length), -100, dtype=torch.long)
            for index, feature in enumerate(features):
                length = len(feature["input_ids"])
                input_ids[index, :length] = torch.tensor(feature["input_ids"], dtype=torch.long)
                attention_mask[index, :length] = torch.tensor(feature["attention_mask"], dtype=torch.long)
                labels[index, :length] = torch.tensor(feature["labels"], dtype=torch.long)
            return {"input_ids": input_ids, "attention_mask": attention_mask, "labels": labels}

    random.seed(pilot.seed)
    torch.manual_seed(pilot.seed)
    compute_dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    quantization_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=compute_dtype,
    )
    model = AutoModelForCausalLM.from_pretrained(
        args.model_dir,
        local_files_only=True,
        trust_remote_code=False,
        quantization_config=quantization_config,
        torch_dtype=compute_dtype,
    )
    model.config.use_cache = False
    model = prepare_model_for_kbit_training(model)
    model = get_peft_model(
        model,
        LoraConfig(
            r=pilot.lora_rank,
            lora_alpha=pilot.lora_alpha,
            lora_dropout=pilot.lora_dropout,
            bias="none",
            task_type="CAUSAL_LM",
            target_modules=list(LORA_TARGET_MODULES),
        ),
    )
    train_dataset = Dataset.from_list(train_inputs)
    validation_dataset = Dataset.from_list(validation_inputs)
    training_args = TrainingArguments(
        output_dir=str(args.output_dir),
        num_train_epochs=pilot.epochs,
        per_device_train_batch_size=pilot.per_device_batch_size,
        per_device_eval_batch_size=pilot.per_device_batch_size,
        gradient_accumulation_steps=pilot.gradient_accumulation_steps,
        learning_rate=pilot.learning_rate,
        logging_steps=10,
        evaluation_strategy="steps",
        eval_steps=50,
        save_strategy="steps",
        save_steps=50,
        save_total_limit=2,
        bf16=compute_dtype == torch.bfloat16,
        fp16=compute_dtype == torch.float16,
        optim="paged_adamw_8bit",
        report_to=[],
        remove_unused_columns=False,
        seed=pilot.seed,
    )
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=validation_dataset,
        data_collator=CompletionCollator(),
        tokenizer=tokenizer,
    )
    training_result = trainer.train()
    evaluation_result = trainer.evaluate()
    adapter_dir = args.output_dir / "adapter"
    model.save_pretrained(adapter_dir, safe_serialization=True)
    tokenizer.save_pretrained(adapter_dir)
    write_run_manifest(
        args.output_dir,
        args.model_dir,
        args.train_file,
        args.validation_file,
        pilot,
        tokenizer,
        config,
        {
            "train_metrics": training_result.metrics,
            "evaluation_metrics": evaluation_result,
            "adapter_directory": "adapter",
        },
    )
    print(json.dumps(evaluation_result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
