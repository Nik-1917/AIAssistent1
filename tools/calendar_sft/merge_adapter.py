"""Merge a trained calendar-adapter into the locked BF16 checkpoint.

The result remains a Hugging Face Safetensors model. Convert that merged output
to GGUF with a separately pinned converter before importing it into Android.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from dataset_contract import DatasetContractError, file_sha256


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL_MANIFEST_PATH = ROOT / "tools" / "calendar_sft" / "model_manifest.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model-manifest",
        type=Path,
        default=DEFAULT_MODEL_MANIFEST_PATH,
        help="source-lock JSON; defaults to the archived RefalMachine audit lock",
    )
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--adapter-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest_path = args.model_manifest.resolve()
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        source_license_status = manifest["license"]["status"]
        if source_license_status == "NOT_CLEARED" or (source_license_status != "VERIFIED" and not args.dry_run):
            raise DatasetContractError(
                f"source licence status is {source_license_status!r}; it must be VERIFIED before this run"
            )
        if not args.model_dir.is_dir() or args.model_dir.suffix.lower() == ".gguf":
            raise DatasetContractError("--model-dir must be the full Safetensors checkpoint")
        if not (args.adapter_dir / "adapter_config.json").is_file():
            raise DatasetContractError("--adapter-dir does not contain adapter_config.json")
        if args.output_dir.exists():
            raise DatasetContractError(f"output already exists: {args.output_dir}")
    except (DatasetContractError, OSError, json.JSONDecodeError) as error:
        print(f"Merge preflight failed: {error}", file=sys.stderr)
        return 2
    if args.dry_run:
        print(
            json.dumps(
                {"base": str(args.model_dir), "adapter": str(args.adapter_dir), "source_lock": str(manifest_path)},
                ensure_ascii=False,
            )
        )
        return 0

    try:
        import torch
        from peft import PeftModel
        from transformers import AutoModelForCausalLM, AutoTokenizer
    except ImportError as error:
        print(f"Merge dependencies are unavailable: {error}", file=sys.stderr)
        return 2
    try:
        tokenizer = AutoTokenizer.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False)
        model = AutoModelForCausalLM.from_pretrained(
            args.model_dir,
            local_files_only=True,
            trust_remote_code=False,
            torch_dtype=torch.bfloat16,
            low_cpu_mem_usage=True,
        )
        merged = PeftModel.from_pretrained(model, args.adapter_dir, local_files_only=True).merge_and_unload()
        args.output_dir.mkdir(parents=True, exist_ok=False)
        merged.save_pretrained(args.output_dir, safe_serialization=True)
        tokenizer.save_pretrained(args.output_dir)
        merge_manifest = {
            "format_version": 1,
            "source_lock_path": str(manifest_path),
            "source_lock_sha256": file_sha256(manifest_path),
            "adapter_config_sha256": file_sha256(args.adapter_dir / "adapter_config.json"),
            "next_step": "Convert this merged checkpoint to GGUF with a separately pinned converter, then run the independent holdout on device.",
        }
        (args.output_dir / "merge_manifest.json").write_text(
            json.dumps(merge_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Merge failed: {error}", file=sys.stderr)
        return 1
    print(str(args.output_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
