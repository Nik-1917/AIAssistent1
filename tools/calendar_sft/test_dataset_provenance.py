"""Unit tests for the dataset-rights gate. Run with Python's standard unittest."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from dataset_contract import DatasetContractError, file_sha256
from dataset_provenance import verify_dataset_provenance


class DatasetProvenanceTests(unittest.TestCase):
    def write_register(self, directory: Path, train: Path, validation: Path) -> Path:
        register = {
            "format_version": 1,
            "status": "VERIFIED",
            "dataset_id": "calendar-sft-test-v1",
            "review": {
                "reviewed_by": "test-reviewer",
                "reviewed_on": "2026-08-25",
                "decision_evidence": "test-only",
            },
            "artifacts": {
                "train": {"sha256": file_sha256(train)},
                "validation": {"sha256": file_sha256(validation)},
            },
            "records": [
                {
                    "source_type": "internal_authored",
                    "source_reference": "test-fixtures",
                    "rights_evidence": "test-only",
                    "permits_model_training": True,
                    "permits_derivative_weight_distribution": True,
                    "contains_personal_data": False,
                }
            ],
        }
        path = directory / "provenance.json"
        path.write_text(json.dumps(register), encoding="utf-8")
        return path

    def test_verified_register_matches_exact_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            train = directory / "train.jsonl"
            validation = directory / "validation.jsonl"
            train.write_text("train\n", encoding="utf-8")
            validation.write_text("validation\n", encoding="utf-8")
            register = self.write_register(directory, train, validation)

            result = verify_dataset_provenance(register, train, validation)

            self.assertEqual("VERIFIED", result["status"])
            self.assertEqual(file_sha256(train), result["train_sha256"])
            self.assertEqual(file_sha256(validation), result["validation_sha256"])

    def test_pending_register_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            train = directory / "train.jsonl"
            validation = directory / "validation.jsonl"
            train.write_text("train\n", encoding="utf-8")
            validation.write_text("validation\n", encoding="utf-8")
            register = self.write_register(directory, train, validation)
            payload = json.loads(register.read_text(encoding="utf-8"))
            payload["status"] = "PENDING_RIGHTS_REVIEW"
            register.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(DatasetContractError, "must be VERIFIED"):
                verify_dataset_provenance(register, train, validation)

    def test_changed_artifact_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            train = directory / "train.jsonl"
            validation = directory / "validation.jsonl"
            train.write_text("train\n", encoding="utf-8")
            validation.write_text("validation\n", encoding="utf-8")
            register = self.write_register(directory, train, validation)
            train.write_text("changed\n", encoding="utf-8")

            with self.assertRaisesRegex(DatasetContractError, "does not match"):
                verify_dataset_provenance(register, train, validation)


if __name__ == "__main__":
    unittest.main()
