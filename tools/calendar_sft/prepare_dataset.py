"""Build deterministic, leakage-checked SFT artifacts for the calendar assistant.

This script never contacts a model provider and never reads calendar databases.
It only transforms checked-in fictional supervision data into ignored build
artifacts that a training job can consume.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sys
from typing import Any

from dataset_contract import (
    DatasetContractError,
    category_counts,
    file_sha256,
    load_jsonl,
    message_signature,
)


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
DEFAULT_OUTPUT_DIR = ROOT / "build" / "calendar_sft_dataset"
DEFAULT_TRAIN = (
    DOCS / "calendar_assistant_train_seed.jsonl",
    DOCS / "calendar_assistant_candidates" / "calendar_assistant_train_candidates.jsonl",
    DOCS / "calendar_assistant_manual_train_v5.jsonl",
)
DEFAULT_VALIDATION = (
    DOCS / "calendar_assistant_eval_seed.jsonl",
    DOCS / "calendar_assistant_candidates" / "calendar_assistant_eval_candidates.jsonl",
    DOCS / "calendar_assistant_manual_eval_v5.jsonl",
)
DEFAULT_HOLDOUT = (DOCS / "calendar_assistant_manual_holdout.jsonl",)
MODEL_MANIFEST = ROOT / "tools" / "calendar_sft" / "clean_room_qwen3_source_lock.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace an existing output directory after all validation succeeds",
    )
    parser.add_argument(
        "--check-only",
        action="store_true",
        help="validate source files and split separation without writing artifacts",
    )
    return parser.parse_args()


def load_split(paths: tuple[Path, ...], name: str) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    rows: list[dict[str, Any]] = []
    sources: list[dict[str, str]] = []
    for path in paths:
        if not path.is_file():
            raise DatasetContractError(f"{name}: missing source file {path}")
        part = load_jsonl(path)
        rows.extend(part)
        sources.append(
            {
                "path": path.relative_to(ROOT).as_posix(),
                "sha256": file_sha256(path),
                "rows": str(len(part)),
            },
        )
    return rows, sources


def assert_no_duplicates(rows: list[dict[str, Any]], split_name: str) -> set[str]:
    signatures: set[str] = set()
    for index, row in enumerate(rows, start=1):
        signature = message_signature(row)
        if signature in signatures:
            raise DatasetContractError(
                f"{split_name}: duplicate system-and-user prompt at normalized row {index}",
            )
        signatures.add(signature)
    return signatures


def assert_disjoint(
    left: set[str],
    left_name: str,
    right: set[str],
    right_name: str,
) -> None:
    overlap = left & right
    if overlap:
        raise DatasetContractError(
            f"{left_name} and {right_name} share {len(overlap)} system-and-user prompts",
        )


def assert_holdout_ids(rows: list[dict[str, Any]]) -> None:
    ids = [row.get("case_id") for row in rows]
    if any(case_id is None for case_id in ids):
        raise DatasetContractError("holdout: every row must have a case_id")
    if len(set(ids)) != len(ids):
        raise DatasetContractError("holdout: case_id values must be unique")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    content = "".join(
        json.dumps(row, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
        for row in rows
    )
    path.write_text(content, encoding="utf-8")


def main() -> int:
    args = parse_args()
    try:
        train, train_sources = load_split(DEFAULT_TRAIN, "train")
        validation, validation_sources = load_split(DEFAULT_VALIDATION, "validation")
        holdout, holdout_sources = load_split(DEFAULT_HOLDOUT, "holdout")
        train_signatures = assert_no_duplicates(train, "train")
        validation_signatures = assert_no_duplicates(validation, "validation")
        holdout_signatures = assert_no_duplicates(holdout, "holdout")
        assert_disjoint(train_signatures, "train", validation_signatures, "validation")
        assert_disjoint(train_signatures, "train", holdout_signatures, "holdout")
        assert_disjoint(validation_signatures, "validation", holdout_signatures, "holdout")
        assert_holdout_ids(holdout)
    except DatasetContractError as error:
        print(f"Dataset contract failed: {error}", file=sys.stderr)
        return 2

    summary = {
        "train": len(train),
        "validation": len(validation),
        "holdout": len(holdout),
    }
    if args.check_only:
        print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
        return 0

    output_dir = args.output_dir.resolve()
    if output_dir.exists():
        if not args.overwrite:
            print(
                f"Refusing to overwrite existing output directory: {output_dir}. Use --overwrite after review.",
                file=sys.stderr,
            )
            return 2
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=False)
    write_jsonl(output_dir / "train.jsonl", train)
    write_jsonl(output_dir / "validation.jsonl", validation)
    write_jsonl(output_dir / "holdout.jsonl", holdout)

    manifest = {
        "format_version": 1,
        "runtime_system_prompt": "Сегодня дата и время:<DATE> (<WEEKDAY>) <TIME> <IANA_ZONE> ответ JSON",
        "model_manifest_sha256": file_sha256(MODEL_MANIFEST),
        "splits": {
            "train": {
                "rows": len(train),
                "category_counts": category_counts(train),
                "sources": train_sources,
                "artifact_sha256": file_sha256(output_dir / "train.jsonl"),
            },
            "validation": {
                "rows": len(validation),
                "category_counts": category_counts(validation),
                "sources": validation_sources,
                "artifact_sha256": file_sha256(output_dir / "validation.jsonl"),
            },
            "holdout": {
                "rows": len(holdout),
                "category_counts": category_counts(holdout),
                "sources": holdout_sources,
                "artifact_sha256": file_sha256(output_dir / "holdout.jsonl"),
            },
        },
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
