"""Package the frozen v3 fine-tune, with honest curation and evaluation scope."""
import argparse
import gzip
import importlib.metadata
import json
from pathlib import Path
import shutil
import zipfile

from prepare_russian import digest


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def package(args):
    if args.output.exists():
        raise ValueError("Use a new release directory")
    training = json.loads((args.run / "training.json").read_text(encoding="utf-8"))
    exported = json.loads((args.assets / "manifest.json").read_text(encoding="utf-8"))
    integrity = json.loads((args.corpus / "integrity.json").read_text(encoding="utf-8"))
    summary = json.loads((args.corpus / "dataset_summary.json").read_text(encoding="utf-8"))
    if training["test_status"] != "FINAL_EVALUATED" or not training["training_complete"]:
        raise ValueError("Incomplete training/evaluation")
    if training["weights_sha256"] != digest(args.run / "encoder.pt"):
        raise ValueError("Changed weights")
    if exported["model_sha256"] != digest(args.assets / "encoder.onnx") or exported["weights_sha256"] != training["weights_sha256"]:
        raise ValueError("Export provenance mismatch")
    if training["corpus_sha256"] != digest(args.corpus / "corpus.json") or integrity["corpus_sha256"] != training["corpus_sha256"]:
        raise ValueError("Changed corpus")
    source_root = Path(__file__).resolve().parent
    for name, expected in training["training_source_hashes"].items():
        if digest(source_root / name) != expected:
            raise ValueError(f"Changed training source: {name}")
    for name, key in (("ru_v3_words.json", "curation_sha256"),
                      ("ru_v3_acoustic_exclusions.json", "acoustic_exclusions_sha256")):
        if digest(source_root / "curation" / name) != training[key]:
            raise ValueError(f"Changed curation: {name}")
    args.output.mkdir(parents=True)
    for name in ("encoder.pt", "training.json", "error_analysis.json", "v2_new_test_scores.npz", "v3_new_test_scores.npz"):
        shutil.copyfile(args.run / name, args.output / name)
    for name in ("encoder.onnx", "LICENSE-CC-BY-4.0.txt", "ATTRIBUTION.md"):
        shutil.copyfile(args.assets / name, args.output / name)
    for name in ("dataset_summary.json", "quality_report.json", "integrity.json"):
        shutil.copyfile(args.corpus / name, args.output / name)
    for name in ("ru_v3_words.json", "ru_v3_acoustic_exclusions.json"):
        shutil.copyfile(source_root / "curation" / name, args.output / name)
    (args.output / "corpus.json.gz").write_bytes(gzip.compress((args.corpus / "corpus.json").read_bytes(), mtime=0))
    config = dict(schema_version=3, model_version=training["model_version"],
        model_file="encoder.onnx", model_sha256=exported["model_sha256"], model_bytes=exported["model_bytes"],
        weights_file="encoder.pt", weights_sha256=training["weights_sha256"],
        initial_weights_sha256=training["initial_weights_sha256"], initialization=training["initialization"],
        model_license="CC-BY-4.0", architecture=training["architecture"],
        feature_version=training["feature_version"], sample_rate=16000, embedding_size=64,
        input=dict(name="features", dtype="float32", shape=[1, 101, 40]),
        output=dict(name="embedding", dtype="float32", shape=[1, 64], normalization="L2 after inference"),
        threshold=training["dev"]["threshold"], threshold_source="New dev same-owner wrong-word pair FAR <= 1%; never final test",
        additional_dev_calibrations=training["dev"]["operating_points"],
        new_source_test=training["test"], test_strata=training["comparison"]["v3"]["test_strata"],
        python_onnx_parity=exported["python_parity"], activation_validated=False,
        evidence="Isolated Russian corpus words; distinct enrollment/query recordings per owner/word",
        limits=training["limitations"],
        frontend="Unchanged MetricKwsFeatureExtractor; MFCC normalization and C0 centering are inside ONNX; do not apply twice",
        decision="Keyword threshold AND independently configured Voice ID; no blended score",
        profile_compatibility="Enroll again when encoder SHA changes; never compare embeddings from different models")
    write_json(args.output / "model.json", config)
    old_env = json.loads((source_root / "experiments/ru-mswc-v1/environment.json").read_text())
    environment = {key: importlib.metadata.version(key) for key in old_env if key != "note"}
    environment["note"] = old_env["note"]
    write_json(args.output / "environment.json", environment)
    shutil.copyfile(source_root / "experiments/ru-mswc-v1/host_wheels.json", args.output / "host_wheels.json")
    sources = ["prepare_russian.py", "prepare_russian_v3.py", "rebalance_russian_v3_eval.py", "ru_model_v2.py",
               "train_russian_v3.py", "evaluate_personal.py", "finalize_russian_v3.py", "export_russian_v3.py",
               "package_russian_v3.py", "ru_model.py", "train_russian.py", "test_curation_contract.py"]
    provenance = dict(source_hashes={name: digest(source_root / name) for name in sources},
        training_sources_at_run_start=training["training_source_hashes"], corpus_sha256=training["corpus_sha256"],
        initialization=training["initialization"], initial_weights_sha256=training["initial_weights_sha256"],
        curation_sha256=training["curation_sha256"], acoustic_exclusions_sha256=training["acoustic_exclusions_sha256"])
    write_json(args.output / "provenance.json", provenance)
    v2 = training["comparison"]["v2"]["test"]["operating_points"]["0.01"]
    v3 = training["test"]["operating_points"]["0.01"]
    readme = f"""# Russian personal metric encoder v3

ONNX and PyTorch artifacts, fine-tuned from the project's own CC BY 4.0 v2 weights.
Training: {summary['train']['clips']:,} human recordings, {summary['train']['words']:,} Russian words,
{summary['train']['speakers']} speakers. Exactly 51,200 recordings were added, preserving all 51,200 v2 clips.
The 384 added word labels and 49 difficult pairs were manually authored. One potentially
acoustically ambiguous pair was excluded from negative training (48 active pairs).
Audio selection/quality checks were automated. Zero clips were individually listened to
by a human in this curation pass; MSWC labels were not manually re-transcribed.

Model/data license: CC BY 4.0; attribution and full terms included. Architecture:
RussianMetricEncoderV2, the project's own residual CNN, not original KAIST ResNet15 weights.
No external pretrained weights were introduced; the v2 ancestor was trained from random.

Use encoder.onnx with unchanged metric-cli-mfcc40-1s-v1 Kotlin MFCC and L2-normalize
the 64-component result. Input normalization is embedded in ONNX. Default dev-only
threshold: {config['threshold']}. Re-enroll after changing the model SHA.

Same new-source test: 177 owner-word positives and 3,979 same-owner wrong-word pairs.
Both thresholds were calibrated on the same new dev split, targeting <=1% dev pair FAR.
v2: misses {v2['false_rejects']}/177 ({v2['false_reject_rate']:.2%}), false accepts
{v2['false_accepts']}/3979 ({v2['false_accept_rate']:.2%}).
v3: misses {v3['false_rejects']}/177 ({v3['false_reject_rate']:.2%}), false accepts
{v3['false_accepts']}/3979 ({v3['false_accept_rate']:.2%}).
The result is mixed: fewer misses, more false accepts at the selected operating point.
This is not proof of an unconditional quality improvement or a <=1% final-test FAR.

Only 64 of the 256 new test clips cover eight never-previously-evaluated words;
the other 192 are unused source recordings of twelve previously evaluated word labels.
training.json reports these strata separately, plus the old v2 regression set with
separate old-dev calibration. All new test source sentences were absent from every
previous selected recording. Words/speakers/source sentences do not cross splits.
Training-only acoustic negative exclusions never modify test labels or score rules.

Pair FAR is not false activations per hour. No full phrases, live microphone,
far-field or continuous activation acceptance has been established. AppModule still
provides UnavailableMetricKwsEngine. This is an experimental integration artifact.
See docs/METRIC_KWS_RU_V3.md and METRIC_KWS_RU_V3_VALIDATION.json for Android evidence.

The compact ZIP includes weights, config, comparison, manual curation and integrity
reports. The experiment directory additionally contains corpus.json.gz with recording
and speaker provenance, raw scores, host dependency versions and wheel hashes.
The corpus decompressed SHA is pinned in provenance.json. Synthetic augmentation
is not counted among the 102,400 human recordings. Source and environment hashes
support reproduction; the host venv inherits dependencies as environment.json states.

ONNX SHA-256: {config['model_sha256']}
PyTorch SHA-256: {config['weights_sha256']}
"""
    (args.output / "README.md").write_text(readme, encoding="utf-8")
    compact = ["encoder.onnx", "encoder.pt", "model.json", "training.json", "README.md",
        "LICENSE-CC-BY-4.0.txt", "ATTRIBUTION.md", "provenance.json", "error_analysis.json",
        "ru_v3_words.json", "ru_v3_acoustic_exclusions.json", "dataset_summary.json", "integrity.json", "quality_report.json"]
    checksums = "\n".join(f"{digest(args.output / name)}  {name}" for name in compact) + "\n"
    (args.output / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    archive = args.output / "ru-mswc-personal-v3.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name in compact + ["SHA256SUMS.txt"]:
            output.write(args.output / name, arcname=name)
    print(json.dumps(dict(archive=str(archive), bytes=archive.stat().st_size, sha256=digest(archive))))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--assets", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    package(p.parse_args())
