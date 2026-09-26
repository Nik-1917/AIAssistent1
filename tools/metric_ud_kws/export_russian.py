"""Export our hash-pinned Russian experiment; generate test-only parity artifacts.

Does not change the blocked upstream model manifest or enable Android activation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import time

import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch.nn import functional as F

from prepare_russian import digest
from ru_model import RussianResNet15
from ru_model_v2 import RussianMetricEncoderV2
from train_russian import load_corpus


def normalized(array):
    array = np.asarray(array, dtype=np.float64)
    norms = np.linalg.norm(array, axis=-1, keepdims=True)
    if not np.isfinite(array).all() or np.any(norms <= 0):
        raise ValueError("Invalid embedding")
    return (array / norms).astype(np.float32)


def export(args):
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    report = json.loads((args.run / "training.json").read_text(encoding="utf-8"))
    if report["initialization"] != "random; no pretrained weights" or report["model_license"] != "CC-BY-4.0":
        raise ValueError("Unreviewed local model")
    if report["weights_sha256"] != digest(args.run / "encoder.pt") or report["corpus_sha256"] != digest(args.corpus / "corpus.json"):
        raise ValueError("Artifact provenance mismatch")
    corpus, features, rows = load_corpus(args.corpus)
    if report["architecture"] == "ResNet15":
        model = RussianResNet15(report["n_maps"], report["embedding_size"])
    elif report["architecture"] == "RussianMetricEncoderV2" and report.get("test_status") == "FINAL_EVALUATED":
        model = RussianMetricEncoderV2()
    else:
        raise ValueError("Unreviewed architecture or missing final evaluation")
    model.load_state_dict(torch.load(args.run / "encoder.pt", map_location="cpu", weights_only=True), strict=True)
    model.eval()
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / "encoder.onnx"
    torch.onnx.export(model, (torch.zeros(1, 101, 40),), target,
        opset_version=17, dynamo=False, input_names=["features"], output_names=["embedding"])
    graph = onnx.load(target)
    onnx.checker.check_model(graph, full_check=True)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(target), options, providers=["CPUExecutionProvider"])
    maximum_error, minimum_cosine, total = 0., 1., 0
    started = time.monotonic()
    for split in ("dev", "test"):
        for tensor in features[split]:
            tensor = tensor.unsqueeze(0)
            with torch.inference_mode():
                expected = normalized(model(tensor).numpy())[0]
            actual = normalized(session.run(["embedding"], {"features": tensor.numpy()})[0])[0]
            maximum_error = max(maximum_error, float(np.abs(actual - expected).max()))
            minimum_cosine = min(minimum_cosine, float(np.dot(actual.astype(np.float64), expected) /
                                (np.linalg.norm(actual.astype(np.float64)) * np.linalg.norm(expected.astype(np.float64)))))
            total += 1
    if maximum_error > 1e-4 or minimum_cosine < .9999:
        raise ValueError(f"ONNX parity failed: {maximum_error}, {minimum_cosine}")
    cases = []

    def fixture(name, pcm, feature, metadata, existing_file=None):
        tensor = torch.from_numpy(feature.copy()).unsqueeze(0)
        with torch.inference_mode():
            expected = normalized(model(tensor).numpy())[0]
        if existing_file is None:
            data = b"MKF1" + struct.pack("<II", len(pcm), feature.size)
            data += pcm.astype("<f4").tobytes() + feature.astype("<f4").tobytes()
            filename = name + ".bin"
            (args.output / filename).write_bytes(data)
            asset = args.asset_prefix + "/" + filename
            sha = hashlib.sha256(data).hexdigest()
        else:
            asset = existing_file.name
            sha = digest(existing_file)
        cases.append(dict(name=name, asset=asset, sha256=sha,
                          expected_embedding=expected.tolist(), **metadata))

    analytic = json.loads((args.analytic / "manifest.json").read_text(encoding="utf-8"))
    for case in analytic["cases"]:
        path = args.analytic / (case["name"] + ".bin")
        if digest(path) != case["sha256"]:
            raise ValueError("Changed analytic fixture")
        data = path.read_bytes()
        if data[:4] != b"MKF1":
            raise ValueError("Bad fixture")
        samples, count = struct.unpack_from("<II", data, 4)
        pcm = np.frombuffer(data, "<f4", count=samples, offset=12)
        feature = np.frombuffer(data, "<f4", count=count, offset=12 + samples * 4).reshape(101, 40)
        fixture(case["name"], pcm, feature, dict(source="locally authored analytic signal"), path)
    pcm = np.load(args.corpus / "test_pcm.npy", mmap_mode="r", allow_pickle=False)
    if digest(args.corpus / "test_pcm.npy") != corpus["files"]["test_pcm.npy"]:
        raise ValueError("Changed test PCM")
    # Two distinct utterances for each of twelve unseen Russian words; test APK only.
    per_label = {}
    for i, row in enumerate(rows["test"]):
        label = row["label"]
        index = per_label.get(label, 0)
        if label >= 12 or index >= 2:
            continue
        fixture(f"ru_{label:02d}_{index}", pcm[i], features["test"][i].numpy(),
            dict(source="MSWC v1.0 Russian (CC BY 4.0)", word=row["word"],
                 source_path=row["path"], opus_sha256=row["opus_sha256"],
                 pcm_sha256=row["pcm_sha256"], label=label, role="support" if index == 0 else "query"))
        per_label[label] = index + 1

    class WrongShape(torch.nn.Module):
        def forward(self, x):
            return x.mean(1)[:, :5]

    invalid = args.output / "invalid_shape.onnx"
    torch.onnx.export(WrongShape(), (torch.zeros(1, 101, 40),), invalid,
        opset_version=17, dynamo=False, input_names=["features"], output_names=["embedding"])
    manifest = dict(schema_version=1, model_version=report["model_version"], model_sha256=digest(target),
        model_bytes=target.stat().st_size, model_license="CC-BY-4.0", activation_validated=False,
        feature_version=report["feature_version"], embedding_size=report["embedding_size"],
        threshold=report["dev"]["threshold"], threshold_scope="experimental dev calibration, not production",
        weights_sha256=report["weights_sha256"], corpus_sha256=report["corpus_sha256"],
        onnx=dict(version=onnx.__version__, opset=17, ir_version=graph.ir_version,
                  input_shape=[1, 101, 40], output_shape=[1, 64]),
        python_parity=dict(cases=total, max_absolute_error=maximum_error, min_cosine=minimum_cosine,
            max_absolute_error_limit=1e-4, min_cosine_limit=.9999, onnxruntime=ort.__version__,
            elapsed_s=time.monotonic() - started, provider="CPUExecutionProvider"),
        android_parity_limits=dict(max_absolute_error=1e-4, min_cosine=.9999),
        invalid_shape_sha256=digest(invalid), cases=cases)
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(onnx_sha256=manifest["model_sha256"], bytes=target.stat().st_size,
                         python_parity=manifest["python_parity"], android_fixture_count=len(cases))), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--analytic", type=Path, default=Path("app/src/test/resources/metric_kws"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--asset-prefix", default="metric_kws_ru")
    export(parser.parse_args())
