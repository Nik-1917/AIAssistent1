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
import math
from pathlib import Path
import random
import sys
from typing import Any

from dataset_contract import DatasetContractError, load_jsonl
from dataset_provenance import verify_dataset_provenance
from verify_source_snapshot import verify_staged_source


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL_MANIFEST_PATH = ROOT / "tools" / "calendar_sft" / "clean_room_qwen3_source_lock.json"
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
    parser.add_argument(
        "--model-manifest",
        type=Path,
        default=DEFAULT_MODEL_MANIFEST_PATH,
        help="source-lock JSON; defaults to the verified clean-room Qwen3 lock",
    )
    parser.add_argument("--model-dir", required=True, type=Path, help="locked BF16 Safetensors snapshot")
    parser.add_argument("--train-file", required=True, type=Path)
    parser.add_argument("--validation-file", required=True, type=Path)
    parser.add_argument(
        "--dataset-provenance",
        type=Path,
        help="VERIFIED register binding rights approval to the exact train and validation JSONL files; required for training",
    )
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
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--dry-run",
        action="store_true",
        help="validate the source snapshot and tokenize all rows without loading model weights",
    )
    mode.add_argument(
        "--smoke-test",
        action="store_true",
        help="load the 4-bit model and run one no-gradient forward pass without changing weights",
    )
    return parser.parse_args()


def load_manifest(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


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


def optimizer_steps_for_epochs(
    train_rows: int,
    per_device_batch_size: int,
    gradient_accumulation_steps: int,
    epochs: float,
) -> int:
    """Return optimizer steps without dropping an epoch's final partial accumulation."""
    if train_rows <= 0:
        raise DatasetContractError("training data must contain at least one row")
    if per_device_batch_size <= 0 or gradient_accumulation_steps <= 0 or epochs <= 0:
        raise DatasetContractError("batch size, gradient accumulation, and epochs must be positive")
    batches_per_epoch = math.ceil(train_rows / per_device_batch_size)
    updates_per_epoch = math.ceil(batches_per_epoch / gradient_accumulation_steps)
    return math.ceil(updates_per_epoch * epochs)


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
    pilot: PilotConfig,
    tokenizer: Any,
    config: Any,
    result: dict[str, Any],
    manifest_path: Path,
    source_integrity: dict[str, Any],
    dataset_provenance: dict[str, Any],
) -> None:
    run_manifest = {
        "format_version": 1,
        "source_lock_path": str(manifest_path),
        "source_integrity": source_integrity,
        "dataset_provenance": dataset_provenance,
        "training_data_sha256": dataset_provenance["train_sha256"],
        "validation_data_sha256": dataset_provenance["validation_sha256"],
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
        manifest_path = args.model_manifest.resolve()
        manifest = load_manifest(manifest_path)
        train_rows = load_jsonl(args.train_file)
        validation_rows = load_jsonl(args.validation_file)
    except (DatasetContractError, OSError, json.JSONDecodeError) as error:
        print(f"Preflight failed: {error}", file=sys.stderr)
        return 2
    source_license_status = manifest["license"]["status"]
    if source_license_status == "NOT_CLEARED" or (source_license_status != "VERIFIED" and not args.dry_run):
        print(
            f"Preflight failed: source licence status is {source_license_status!r}; it must be VERIFIED before this run.",
            file=sys.stderr,
        )
        return 2
    try:
        source_integrity = verify_staged_source(args.model_dir, manifest)
        source_integrity["source_lock_path"] = str(manifest_path)
        source_integrity["source_lock_sha256"] = sha256(manifest_path.read_bytes()).hexdigest()
        if args.dry_run:
            dataset_provenance: dict[str, Any] | None = None
        elif args.dataset_provenance is None:
            raise DatasetContractError("--dataset-provenance is required before real training")
        else:
            dataset_provenance = verify_dataset_provenance(
                args.dataset_provenance,
                args.train_file,
                args.validation_file,
            )
    except (DatasetContractError, KeyError, OSError) as error:
        print(f"Preflight failed: {error}", file=sys.stderr)
        return 2
    if args.output_dir.exists():
        print(f"Preflight failed: output directory already exists: {args.output_dir}", file=sys.stderr)
        return 2

    try:
        from peft import LoraConfig
        from transformers import AutoConfig, AutoTokenizer
    except ImportError as error:
        print(f"Dry-run dependencies are unavailable: {error}", file=sys.stderr)
        return 2

    tokenizer = AutoTokenizer.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False)
    config = AutoConfig.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False)
    if config.model_type != manifest["architecture"]["model_type"]:
        print(
            f"Preflight failed: model_type is {config.model_type!r}, expected {manifest['architecture']['model_type']!r}",
            file=sys.stderr,
        )
        return 2
    if config.vocab_size != manifest["architecture"]["vocab_size"]:
        print(
            f"Preflight failed: vocab_size is {config.vocab_size}, expected {manifest['architecture']['vocab_size']}",
            file=sys.stderr,
        )
        return 2
    if not tokenizer.chat_template:
        print("Preflight failed: tokenizer has no chat_template", file=sys.stderr)
        return 2
    tokenizer.pad_token = tokenizer.eos_token
    qlora_config = LoraConfig(
        r=pilot.lora_rank,
        lora_alpha=pilot.lora_alpha,
        lora_dropout=pilot.lora_dropout,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=list(LORA_TARGET_MODULES),
    )

    try:
        train_inputs = [tokenize_messages(tokenizer, row["messages"], pilot.max_seq_length) for row in train_rows]
        validation_inputs = [tokenize_messages(tokenizer, row["messages"], pilot.max_seq_length) for row in validation_rows]
    except DatasetContractError as error:
        print(f"Tokenization failed: {error}", file=sys.stderr)
        return 2
    token_lengths = [len(item["input_ids"]) for item in train_inputs + validation_inputs]
    expected_optimizer_steps = optimizer_steps_for_epochs(
        len(train_inputs),
        pilot.per_device_batch_size,
        pilot.gradient_accumulation_steps,
        pilot.epochs,
    )
    if args.dry_run:
        print(
            json.dumps(
                {
                    "train_rows": len(train_inputs),
                    "validation_rows": len(validation_inputs),
                    "max_tokens": max(token_lengths),
                    "min_tokens": min(token_lengths),
                    "optimizer_steps": expected_optimizer_steps,
                    "chat_template_sha256": sha256(tokenizer.chat_template.encode("utf-8")).hexdigest(),
                    "qlora_target_modules": list(qlora_config.target_modules),
                },
                ensure_ascii=False,
                sort_keys=True,
            ),
        )
        return 0
    try:
        import torch
        from datasets import Dataset
        from peft import get_peft_model, prepare_model_for_kbit_training
        from transformers import AutoModelForCausalLM, BitsAndBytesConfig, Trainer, TrainingArguments
    except ImportError as error:
        print(f"Training dependencies are unavailable: {error}", file=sys.stderr)
        return 2
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
    # `torch.cuda.is_bf16_supported()` may report emulated BF16 on older GPUs.
    # QLoRA should use native BF16 only on Ampere-or-newer hardware; Pascal
    # (including the local GTX 1080 Ti, CC 6.1) must use native FP16 instead.
    compute_capability = torch.cuda.get_device_capability()
    compute_dtype = torch.bfloat16 if compute_capability[0] >= 8 else torch.float16
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
        qlora_config,
    )
    if args.smoke_test:
        smoke_input = train_inputs[0]
        device = next(model.parameters()).device
        input_ids = torch.tensor([smoke_input["input_ids"]], device=device, dtype=torch.long)
        attention_mask = torch.tensor([smoke_input["attention_mask"]], device=device, dtype=torch.long)
        model.eval()
        with torch.no_grad():
            output = model(input_ids=input_ids, attention_mask=attention_mask, use_cache=False)
        print(
            json.dumps(
                {
                    "mode": "smoke_test",
                    "compute_dtype": str(compute_dtype).removeprefix("torch."),
                    "compute_capability": list(compute_capability),
                    "device": str(device),
                    "logits_shape": list(output.logits.shape),
                    "cuda_memory_allocated_mib": round(torch.cuda.memory_allocated() / (1024**2), 1),
                    "cuda_max_memory_allocated_mib": round(torch.cuda.max_memory_allocated() / (1024**2), 1),
                },
                ensure_ascii=False,
                sort_keys=True,
            ),
        )
        return 0
    train_dataset = Dataset.from_list(train_inputs)
    validation_dataset = Dataset.from_list(validation_inputs)
    training_args = TrainingArguments(
        output_dir=str(args.output_dir),
        num_train_epochs=pilot.epochs,
        max_steps=expected_optimizer_steps,
        per_device_train_batch_size=pilot.per_device_batch_size,
        per_device_eval_batch_size=pilot.per_device_batch_size,
        gradient_accumulation_steps=pilot.gradient_accumulation_steps,
        learning_rate=pilot.learning_rate,
        logging_steps=10,
        eval_strategy="steps",
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
    if trainer.state.global_step != expected_optimizer_steps:
        print(
            "Training failed: completed "
            f"{trainer.state.global_step} optimizer steps, expected {expected_optimizer_steps}.",
            file=sys.stderr,
        )
        return 2
    evaluation_result = trainer.evaluate()
    adapter_dir = args.output_dir / "adapter"
    model.save_pretrained(adapter_dir, safe_serialization=True)
    tokenizer.save_pretrained(adapter_dir)
    write_run_manifest(
        args.output_dir,
        pilot,
        tokenizer,
        config,
        {
            "train_metrics": training_result.metrics,
            "evaluation_metrics": evaluation_result,
            "planned_optimizer_steps": expected_optimizer_steps,
            "completed_optimizer_steps": trainer.state.global_step,
            "adapter_directory": "adapter",
        },
        manifest_path,
        source_integrity,
        dataset_provenance,
    )
    print(json.dumps(evaluation_result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
