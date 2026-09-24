"""Standard-library safety tests; these are NOT trained-model inference tests."""
import hashlib
import json
import pathlib
import tempfile
import unittest
from verify_reference import normalized, validate_manifest
from test_russian import validate_cases


class ReferenceContractTest(unittest.TestCase):
    def test_russian_cases_cannot_be_self_matches_or_change_enrollment(self):
        case = {"enrollment": "owner.wav", "candidate": "repeat.wav", "phrase_id": "assistant",
                "owner_id": "owner", "speaker_id": "owner", "rights_evidence": "consent",
                "language": "ru", "source": "human", "same_keyword": True}
        validate_cases([case])
        with self.assertRaisesRegex(ValueError, "separate recording"):
            validate_cases([dict(case, candidate="owner.wav")])
        with self.assertRaisesRegex(ValueError, "One-shot"):
            validate_cases([case, dict(case, enrollment="owner2.wav")])

    def test_repository_manifest_cannot_load_weights(self):
        manifest = json.loads(pathlib.Path(__file__).with_name("model_manifest.json").read_text())
        with self.assertRaisesRegex(ValueError, "missing weights_license_evidence"):
            validate_manifest(manifest, "nonexistent.model")

    def test_invalid_embeddings(self):
        for value in ([], [0, 0], [float("nan"), 1], [float("inf"), 1], [1] * 4097):
            with self.assertRaises(ValueError):
                normalized(value)

    def test_normalized_embedding(self):
        self.assertEqual([0.6, 0.8], normalized([3, 4]))

    def test_evidence_does_not_bypass_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            weights = pathlib.Path(directory) / "weights.model"
            weights.write_bytes(b"")
            manifest = {"schema_version": 1, "status": "reference_approved",
                        "weights_license_evidence": "test", "training_data_evidence": "test",
                        "model_version": "test", "weights_sha256": "0" * 64}
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                validate_manifest(manifest, weights)

    def test_no_implicit_preprocessing_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            weights = pathlib.Path(directory) / "weights.model"
            weights.write_bytes(b"")
            manifest = {"schema_version": 1, "status": "reference_approved",
                        "weights_license_evidence": "test", "training_data_evidence": "test",
                        "model_version": "test", "weights_sha256": hashlib.sha256(b"").hexdigest(),
                        "architecture": "ResNet15", "n_maps": 45, "embedding_size": 256}
            with self.assertRaisesRegex(ValueError, "Full checkpoint preprocessing"):
                validate_manifest(manifest, weights)


if __name__ == "__main__":
    unittest.main()
