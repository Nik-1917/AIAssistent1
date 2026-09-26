"""Package a completed v2 run with explicit scope, licenses and exact provenance."""
import argparse
import gzip
import importlib.metadata
import json
from pathlib import Path
import shutil
import zipfile

from prepare_russian import digest


def package(args):
    if args.output.exists():
        raise ValueError("Use a new release directory")
    report = json.loads((args.run / "training.json").read_text(encoding="utf-8"))
    export = json.loads((args.assets / "manifest.json").read_text(encoding="utf-8"))
    if report["test_status"] != "FINAL_EVALUATED" or report["weights_sha256"] != digest(args.run / "encoder.pt"):
        raise ValueError("Incomplete or changed training artifact")
    if export["model_sha256"] != digest(args.assets / "encoder.onnx") or export["weights_sha256"] != report["weights_sha256"]:
        raise ValueError("Export provenance mismatch")
    if report["corpus_sha256"] != digest(args.corpus / "corpus.json"):
        raise ValueError("Changed corpus")
    args.output.mkdir(parents=True)
    for name in ("encoder.pt", "training.json", "error_analysis.json", "v1_fresh_test_scores.npz", "v2_fresh_test_scores.npz"):
        shutil.copyfile(args.run / name, args.output / name)
    for name in ("encoder.onnx", "LICENSE-CC-BY-4.0.txt", "ATTRIBUTION.md"):
        shutil.copyfile(args.assets / name, args.output / name)
    (args.output / "corpus.json.gz").write_bytes(gzip.compress((args.corpus / "corpus.json").read_bytes(), mtime=0))
    shutil.copyfile(args.dataset_summary, args.output / "dataset_summary.json")
    config = dict(schema_version=1, model_version=report["model_version"],
        model_file="encoder.onnx", model_sha256=export["model_sha256"], model_bytes=export["model_bytes"],
        weights_file="encoder.pt", weights_sha256=report["weights_sha256"],
        model_license="CC-BY-4.0", architecture=report["architecture"],
        feature_version=report["feature_version"], sample_rate=16000, embedding_size=64,
        input=dict(name="features", dtype="float32", shape=[1, 101, 40]),
        output=dict(name="embedding", dtype="float32", shape=[1, 64], normalization="L2 after inference"),
        threshold=report["dev"]["threshold"], threshold_source="dev same-owner wrong-word pair FAR <= 1%",
        additional_dev_calibrations=report["dev"]["operating_points"],
        fresh_test=report["test"], python_onnx_parity=export["python_parity"],
        activation_validated=False, supported_evidence="Isolated Russian corpus words, one enrollment recording per word/owner",
        limits=report["limitations"],
        frontend="Unchanged MetricKwsFeatureExtractor; input statistics/C0 centering are already inside ONNX, do not apply twice",
        decision="Keyword threshold AND independently configured Voice ID; no blended score",
        profile_compatibility="Never compare v1 and v2 embeddings; enroll again when model SHA changes")
    (args.output / "model.json").write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    old_env = json.loads(Path("tools/metric_ud_kws/experiments/ru-mswc-v1/environment.json").read_text())
    environment = {key: importlib.metadata.version(key) for key in old_env if key != "note"}
    environment["note"] = old_env["note"]
    (args.output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile("tools/metric_ud_kws/experiments/ru-mswc-v1/host_wheels.json", args.output / "host_wheels.json")
    sources = ["prepare_russian.py", "ru_model_v2.py", "train_russian_v2.py", "evaluate_personal.py",
               "finalize_russian_v2.py", "export_russian.py", "package_russian_v2.py", "ru_model.py", "train_russian.py"]
    provenance = dict(training_sources={name: digest(Path("tools/metric_ud_kws") / name) for name in sources},
                      corpus_sha256=report["corpus_sha256"], initialization=report["initialization"])
    (args.output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    readme = f"""# Russian personal metric encoder v2

Ready ONNX and PyTorch artifacts, trained from random on 800 Russian words /
51,200 MSWC clips. Model license: CC BY 4.0; attribution and full text included.
Architecture: our residual metric CNN, not the original Metric-UD-KWS ResNet15.

Use encoder.onnx with the exact `metric-cli-mfcc40-1s-v1` Kotlin frontend and
L2-normalize its 64-component output. Input normalization is embedded in ONNX.
Default dev-calibrated cosine threshold: {config['threshold']}. `model.json`
records shapes, hashes, additional operating points and their measured errors.
Create a fresh enrollment after changing the encoder; embeddings are versioned.

Fresh test, 563 same-owner positives / 17,774 same-owner wrong-word negatives:
v1 missed 59.50% and falsely accepted 1.51%; v2 missed 19.36% and falsely accepted
0.72%. Both models used their own dev threshold targeting <=1% negative-pair FAR.
At the separately dev-calibrated 2.5% operating point, v2 missed 8.88% and falsely
accepted 2.35%. It is an explicit sensitivity tradeoff, not the default gate.

These figures are isolated-word corpus measurements, not false activations per
hour. Full phrases, noisy rooms, actual microphone enrollment and end-to-end
Voice ID activation are not validated. AppModule activation remains disabled.
This artifact is ready for integration/shadow evaluation, not certified for
unattended production activation. See docs/METRIC_KWS_RU_V2.md for Android evidence.

No upstream, PLiX, ImageNet or other pretrained weights were used. Corpus sources,
speaker separation and recording hashes are in corpus.json.gz outside the compact
deployment ZIP. Its decompressed SHA is in provenance.json. Training history,
fixed test errors and baseline comparison are in training.json/error_analysis.json.
The CPU environment inherits host packages, as stated in environment.json.

ONNX SHA-256: {config['model_sha256']}
PyTorch SHA-256: {config['weights_sha256']}
"""
    (args.output / "README.md").write_text(readme, encoding="utf-8")
    compact = ["encoder.onnx", "encoder.pt", "model.json", "training.json", "README.md",
               "LICENSE-CC-BY-4.0.txt", "ATTRIBUTION.md", "provenance.json", "error_analysis.json"]
    checksums = "\n".join(f"{digest(args.output / name)}  {name}" for name in compact) + "\n"
    (args.output / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    archive = args.output / "ru-mswc-personal-v2.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zip_file:
        for name in compact + ["SHA256SUMS.txt"]:
            zip_file.write(args.output / name, arcname=name)
    print(json.dumps(dict(archive=str(archive), bytes=archive.stat().st_size, sha256=digest(archive))))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--assets", type=Path, required=True)
    p.add_argument("--dataset-summary", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    package(p.parse_args())
