"""Verify and fingerprint a staged clean-room model snapshot without loading weights."""

from __future__ import annotations

import argparse
from hashlib import sha256
import json
from pathlib import Path
import sys
from typing import Any

from dataset_contract import DatasetContractError, file_sha256


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL_MANIFEST_PATH = ROOT / "tools" / "calendar_sft" / "clean_room_qwen3_source_lock.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path, help="full locked Safetensors snapshot")
    parser.add_argument("--model-manifest", type=Path, default=DEFAULT_MODEL_MANIFEST_PATH)
    parser.add_argument("--write-manifest", type=Path, help="new integrity-manifest JSON path")
    parser.add_argument(
        "--replace-manifest",
        action="store_true",
        help="replace an existing --write-manifest file after a new successful verification",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DatasetContractError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise DatasetContractError(f"{path} must contain a JSON object")
    return value


def verify_staged_source(model_dir: Path, source_lock: dict[str, Any]) -> dict[str, Any]:
    """Check the full locked checkpoint and return its immutable fingerprint."""

    if source_lock["license"]["status"] != "VERIFIED":
        raise DatasetContractError("source lock licence status must be VERIFIED")
    if not model_dir.is_dir() or model_dir.suffix.lower() == ".gguf":
        raise DatasetContractError("--model-dir must be an extracted full Safetensors snapshot, never a GGUF file")
    expected_files = source_lock["source"]["expected_files"]
    missing = [name for name in expected_files if not (model_dir / name).is_file()]
    if missing:
        raise DatasetContractError(f"model snapshot is missing required files: {', '.join(missing)}")

    upstream_metadata = source_lock["integrity"]["upstream_metadata"]
    expected_sizes = upstream_metadata["file_sizes_bytes"]
    for name in expected_files:
        expected_size = expected_sizes.get(name)
        if not isinstance(expected_size, int):
            raise DatasetContractError(f"source lock has no byte size for {name}")
        actual_size = (model_dir / name).stat().st_size
        if actual_size != expected_size:
            raise DatasetContractError(f"file size for {name} is {actual_size}, expected {expected_size}")

    source_files_sha256 = {
        name: file_sha256(model_dir / name)
        for name in sorted(expected_files)
    }
    expected_lfs_sha256 = upstream_metadata["lfs_sha256"]
    for name, expected_sha256 in expected_lfs_sha256.items():
        actual_sha256 = source_files_sha256.get(name)
        if actual_sha256 != expected_sha256:
            raise DatasetContractError(
                f"SHA-256 for {name} is {actual_sha256}, expected upstream {expected_sha256}"
            )

    config = load_json(model_dir / "config.json")
    expected_architecture = source_lock["architecture"]
    if config.get("model_type") != expected_architecture["model_type"]:
        raise DatasetContractError(
            f"model_type is {config.get('model_type')!r}, expected {expected_architecture['model_type']!r}"
        )
    if config.get("vocab_size") != expected_architecture["vocab_size"]:
        raise DatasetContractError(
            f"vocab_size is {config.get('vocab_size')!r}, expected {expected_architecture['vocab_size']!r}"
        )
    if expected_architecture["architecture"] not in config.get("architectures", []):
        raise DatasetContractError(f"missing expected architecture {expected_architecture['architecture']!r}")

    tokenizer_config = load_json(model_dir / "tokenizer_config.json")
    chat_template = tokenizer_config.get("chat_template")
    if not isinstance(chat_template, str) or not chat_template:
        raise DatasetContractError("tokenizer_config.json has no chat_template")
    missing_tokens = [
        token
        for token in source_lock["chat_format"]["required_special_tokens"]
        if token not in chat_template
    ]
    if missing_tokens:
        raise DatasetContractError(f"chat_template is missing required tokens: {', '.join(missing_tokens)}")

    license_text = (model_dir / "LICENSE").read_text(encoding="utf-8")
    if source_lock["license"]["name"] == "Apache-2.0" and "Apache License" not in license_text:
        raise DatasetContractError("LICENSE does not contain Apache License text")

    return {
        "format_version": 1,
        "source": source_lock["source"],
        "source_files_sha256": source_files_sha256,
        "upstream_verification": {
            "metadata_url": upstream_metadata["url"],
            "retrieved_on": upstream_metadata["retrieved_on"],
            "verified_file_sizes_bytes": expected_sizes,
            "verified_lfs_sha256": expected_lfs_sha256,
        },
        "verified_architecture": {
            "model_type": config["model_type"],
            "architectures": config["architectures"],
            "vocab_size": config["vocab_size"],
            "hidden_size": config.get("hidden_size"),
            "num_hidden_layers": config.get("num_hidden_layers"),
        },
        "tokenizer": {
            "chat_template_sha256": sha256(chat_template.encode("utf-8")).hexdigest(),
            "required_special_tokens": source_lock["chat_format"]["required_special_tokens"],
        },
        "license": {
            "name": source_lock["license"]["name"],
            "sha256": source_files_sha256["LICENSE"],
            "evidence_url": source_lock["license"]["evidence_url"],
        },
    }


def main() -> int:
    args = parse_args()
    try:
        if args.replace_manifest and not args.write_manifest:
            raise DatasetContractError("--replace-manifest requires --write-manifest")
        source_lock_path = args.model_manifest.resolve()
        integrity_manifest = verify_staged_source(args.model_dir, load_json(source_lock_path))
        integrity_manifest["source_lock_path"] = str(source_lock_path)
        integrity_manifest["source_lock_sha256"] = file_sha256(source_lock_path)
        if args.write_manifest:
            output_path = args.write_manifest.resolve()
            if output_path.exists() and not args.replace_manifest:
                raise DatasetContractError(f"integrity manifest already exists: {output_path}")
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(
                json.dumps(integrity_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        print(json.dumps(integrity_manifest, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (DatasetContractError, KeyError, OSError) as error:
        print(f"Source verification failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
