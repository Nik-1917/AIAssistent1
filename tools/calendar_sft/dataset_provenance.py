"""Verify that a reviewed data register authorizes the exact SFT artifacts."""

from __future__ import annotations

import argparse
from datetime import date
import json
from pathlib import Path
import re
import sys
from typing import Any

from dataset_contract import DatasetContractError, file_sha256


SOURCE_TYPES = frozenset({"internal_authored", "separately_licensed", "approved_synthetic"})
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def _require_non_empty_string(value: Any, location: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise DatasetContractError(f"{location} must be a non-empty string")
    return value


def _require_true(value: Any, location: str) -> None:
    if value is not True:
        raise DatasetContractError(f"{location} must be true")


def _require_false(value: Any, location: str) -> None:
    if value is not False:
        raise DatasetContractError(f"{location} must be false")


def _load_register(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DatasetContractError(f"cannot read provenance register {path}: {error}") from error
    if not isinstance(value, dict):
        raise DatasetContractError("provenance register must contain a JSON object")
    return value


def _verify_artifact(register: dict[str, Any], key: str, path: Path) -> str:
    artifacts = register.get("artifacts")
    if not isinstance(artifacts, dict):
        raise DatasetContractError("artifacts must be an object")
    artifact = artifacts.get(key)
    if not isinstance(artifact, dict) or set(artifact) != {"sha256"}:
        raise DatasetContractError(f"artifacts.{key} must contain exactly sha256")
    expected_sha256 = artifact["sha256"]
    if not isinstance(expected_sha256, str) or not SHA256_RE.fullmatch(expected_sha256):
        raise DatasetContractError(f"artifacts.{key}.sha256 must be a lowercase SHA-256 value")
    actual_sha256 = file_sha256(path)
    if actual_sha256 != expected_sha256:
        raise DatasetContractError(
            f"artifacts.{key}.sha256 does not match {path}: {actual_sha256} != {expected_sha256}"
        )
    return actual_sha256


def _verify_review(register: dict[str, Any]) -> None:
    review = register.get("review")
    if not isinstance(review, dict) or set(review) != {"reviewed_by", "reviewed_on", "decision_evidence"}:
        raise DatasetContractError("review must contain exactly reviewed_by, reviewed_on, and decision_evidence")
    _require_non_empty_string(review["reviewed_by"], "review.reviewed_by")
    reviewed_on = _require_non_empty_string(review["reviewed_on"], "review.reviewed_on")
    try:
        date.fromisoformat(reviewed_on)
    except ValueError as error:
        raise DatasetContractError("review.reviewed_on must use YYYY-MM-DD") from error
    _require_non_empty_string(review["decision_evidence"], "review.decision_evidence")


def _verify_records(register: dict[str, Any]) -> int:
    records = register.get("records")
    if not isinstance(records, list) or not records:
        raise DatasetContractError("records must be a non-empty array")
    required_fields = {
        "source_type",
        "source_reference",
        "rights_evidence",
        "permits_model_training",
        "permits_derivative_weight_distribution",
        "contains_personal_data",
    }
    for index, record in enumerate(records):
        location = f"records[{index}]"
        if not isinstance(record, dict) or set(record) != required_fields:
            raise DatasetContractError(f"{location} has an invalid field set")
        if record["source_type"] not in SOURCE_TYPES:
            raise DatasetContractError(f"{location}.source_type is not approved")
        _require_non_empty_string(record["source_reference"], f"{location}.source_reference")
        _require_non_empty_string(record["rights_evidence"], f"{location}.rights_evidence")
        _require_true(record["permits_model_training"], f"{location}.permits_model_training")
        _require_true(
            record["permits_derivative_weight_distribution"],
            f"{location}.permits_derivative_weight_distribution",
        )
        _require_false(record["contains_personal_data"], f"{location}.contains_personal_data")
    return len(records)


def verify_dataset_provenance(register_path: Path, train_file: Path, validation_file: Path) -> dict[str, Any]:
    """Validate a VERIFIED register and bind it to exact training artifacts."""

    register_path = register_path.resolve()
    register = _load_register(register_path)
    if register.get("format_version") != 1:
        raise DatasetContractError("provenance register format_version must be 1")
    if register.get("status") != "VERIFIED":
        raise DatasetContractError("provenance register status must be VERIFIED before real training")
    dataset_id = _require_non_empty_string(register.get("dataset_id"), "dataset_id")
    _verify_review(register)
    record_count = _verify_records(register)
    train_sha256 = _verify_artifact(register, "train", train_file)
    validation_sha256 = _verify_artifact(register, "validation", validation_file)
    return {
        "dataset_id": dataset_id,
        "provenance_register_path": str(register_path),
        "provenance_register_sha256": file_sha256(register_path),
        "record_count": record_count,
        "status": "VERIFIED",
        "train_sha256": train_sha256,
        "validation_sha256": validation_sha256,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--register", required=True, type=Path)
    parser.add_argument("--train-file", required=True, type=Path)
    parser.add_argument("--validation-file", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        result = verify_dataset_provenance(args.register, args.train_file, args.validation_file)
    except (DatasetContractError, OSError) as error:
        print(f"Dataset provenance verification failed: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
