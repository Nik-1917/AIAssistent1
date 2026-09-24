"""Offline reference harness. Never downloads weights or accepts an unaudited manifest."""
import argparse
import hashlib
import importlib.util
import json
import math
import pathlib
import subprocess
import sys


def sha256(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_manifest(manifest, weights):
    for name in ("weights_license_evidence", "training_data_evidence", "model_version"):
        if not isinstance(manifest.get(name), str) or not manifest[name].strip():
            raise ValueError(f"Blocked: missing {name}")
    if manifest.get("schema_version") != 1 or manifest.get("status") != "reference_approved":
        raise ValueError("Blocked: reference is not approved")
    if sha256(weights) != manifest.get("weights_sha256"):
        raise ValueError("Weights SHA-256 mismatch")
    if manifest.get("architecture") != "ResNet15":
        raise ValueError("Only the audited ResNet15 adapter is implemented")
    for key in ("n_maps", "embedding_size"):
        value = manifest.get(key)
        if type(value) is not int or not 1 <= value <= 4096:
            raise ValueError(f"Missing/invalid checkpoint parameter: {key}")
    prep = manifest.get("preprocessing")
    expected = {"sample_rate", "n_mfcc", "dct_type", "norm", "log_mels", "melkwargs", "audio_policy"}
    if not isinstance(prep, dict) or set(prep) != expected:
        raise ValueError("Full checkpoint preprocessing must be specified")
    if prep["sample_rate"] != 16000 or prep["audio_policy"] != "upstream_loadWAV":
        raise ValueError("Unsupported audio policy")
    required_mel = {"n_fft", "win_length", "hop_length", "f_min", "f_max", "pad", "n_mels",
                    "power", "normalized", "center", "pad_mode", "onesided", "norm", "mel_scale"}
    if set(prep["melkwargs"]) != required_mel:
        raise ValueError("Explicit mel parameters required; no inferred defaults")
    return manifest


def normalized(values):
    if not values or len(values) > 4096 or any(not math.isfinite(v) for v in values):
        raise ValueError("Invalid embedding")
    norm = math.sqrt(sum(v * v for v in values))
    if not math.isfinite(norm) or norm == 0:
        raise ValueError("Zero/non-finite norm")
    return [v / norm for v in values]


def load_reference(manifest_path, weights, upstream):
    manifest = validate_manifest(json.loads(pathlib.Path(manifest_path).read_text(encoding="utf-8")), weights)
    upstream = pathlib.Path(upstream).resolve()
    revision = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if revision != manifest["upstream_commit"]:
        raise ValueError("Upstream commit mismatch")
    if subprocess.check_output(["git", "-C", str(upstream), "status", "--porcelain"], text=True).strip():
        raise ValueError("Reference checkout must be clean")
    # Import only reviewed local code. No training pipeline / CUDA-only KeywordNet construction.
    sys.path.insert(0, str(upstream))
    import torch
    import torchaudio
    spec = importlib.util.spec_from_file_location("metric_resnet15", upstream / "models/ResNet15.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    model = module.MainModel(nOut=manifest["embedding_size"], n_maps=manifest["n_maps"])
    state = torch.load(weights, map_location="cpu", weights_only=True)
    encoder = {}
    for name, value in state.items():
        name = name.removeprefix("module.")
        if name.startswith("__S__."):
            encoder[name.removeprefix("__S__.")] = value
    model.load_state_dict(encoder or state, strict=True)
    model.eval()
    p = manifest["preprocessing"]
    features = torchaudio.transforms.MFCC(sample_rate=p["sample_rate"], n_mfcc=p["n_mfcc"],
        dct_type=p["dct_type"], norm=p["norm"], log_mels=p["log_mels"],
        melkwargs=dict(p["melkwargs"], window_fn=torch.hann_window))
    return manifest, model, features


def extract(wav, manifest, model, features):
    import torch
    import wave
    # Reject inputs requiring a different preprocessing policy rather than silently resampling.
    with wave.open(str(wav), "rb") as audio:
        if audio.getframerate() != 16000 or audio.getnchannels() != 1:
            raise ValueError("Provide 16 kHz mono WAV")
        if not 8000 <= audio.getnframes() <= 128000:
            raise ValueError("Provide a 0.5-8 second utterance")
    from DatasetLoader import loadWAV
    samples = torch.as_tensor(loadWAV(str(wav)), dtype=torch.float32)
    if samples.ndim != 1 or not torch.isfinite(samples).all():
        raise ValueError("Invalid waveform")
    with torch.inference_mode():
        tensor = features(samples).transpose(0, 1).unsqueeze(0)
        embedding = model(tensor).reshape(-1).tolist()
    if len(embedding) != manifest["embedding_size"]:
        raise ValueError("Output dimension mismatch")
    return tensor, normalized(embedding)


def arguments():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--weights", required=True)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--wav", required=True)
    parser.add_argument("--output", required=True)
    return parser


if __name__ == "__main__":
    args = arguments().parse_args()
    manifest, model, features = load_reference(args.manifest, args.weights, args.upstream)
    tensor, embedding = extract(args.wav, manifest, model, features)
    pathlib.Path(args.output).write_text(json.dumps({"weights_sha256": manifest["weights_sha256"],
        "wav_sha256": sha256(args.wav), "feature_shape": list(tensor.shape),
        "features": tensor.tolist(), "embedding": embedding}, allow_nan=False), encoding="utf-8")
