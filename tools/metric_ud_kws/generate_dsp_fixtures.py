"""Offline numeric MFCC reference, no weights, microphones, speech or downloads.

These analytic fixtures are authored here, not taken from an audio dataset. This uses
the upstream CLI defaults, not an inferred configuration for an unapproved checkpoint.
"""
import argparse
import hashlib
import json
import math
import pathlib
import platform
import struct

FEATURE_VERSION = "metric-cli-mfcc40-1s-v1"
UPSTREAM_COMMIT = "b26b3dffa3ab19255963329eab9e733e441d7486"


def centered(samples):
    import numpy as np
    if len(samples) < 16000:
        left = (16000 - len(samples) + 1) // 2
        return np.pad(samples, (left, 16000 - len(samples) - left))
    start = (len(samples) - 16000) // 2
    return samples[start:start + 16000]


def synthetic_cases():
    import numpy as np

    def noise(length, amplitude=0.2):
        # Fully specified integer LCG, independent of numpy RNG versions.
        state = 0x1A2B3C4D
        values = []
        for _ in range(length):
            state = (1664525 * state + 1013904223) & 0xFFFFFFFF
            values.append(((state >> 8) / 16777216.0 * 2.0 - 1.0) * amplitude)
        return np.asarray(values, dtype=np.float32)

    time = np.arange(16000, dtype=np.float64) / 16000.0
    impulse = np.zeros(16000, dtype=np.float32)
    impulse[[0, 1, 239, 240, 15998, 15999]] = [1.0, -0.5, 0.75, -1.0, 0.5, -0.25]
    yield "silence", np.zeros(16000, dtype=np.float32)
    yield "edge_impulses", impulse
    yield "tone_440", (0.7 * np.sin(2 * math.pi * 440 * time)).astype(np.float32)
    yield "chirp", (0.8 * np.sin(2 * math.pi * (80 * time + 3800 * time**2))).astype(np.float32)
    yield "mixed_tones", (0.2 * np.sin(2 * math.pi * 231 * time) +
                          0.15 * np.cos(2 * math.pi * 3151 * time)).astype(np.float32)
    yield "noise", noise(16000)
    yield "quiet_noise", noise(16000, 1e-7)
    yield "dc", np.full(16000, 0.25, dtype=np.float32)
    yield "nyquist", np.where(np.arange(16000) % 2 == 0, .9, -.9).astype(np.float32)
    envelope = np.ones(16000, dtype=np.float32)
    envelope[:8000] = 1e-6
    yield "global_db_floor", noise(16000) * envelope
    # Odd/even, shortest/longest and one-sample crop/pad boundaries all use distinct PCM.
    for length in (8000, 8001, 15999, 16001, 16002, 32001, 128000):
        yield f"length_{length}", noise(length)


def generate(output):
    import numpy as np
    import torch
    import torchaudio
    if torch.__version__ != "2.8.0+cpu" or torchaudio.__version__ != "2.8.0+cpu":
        raise ValueError("Use the pinned CPU reference: torch/torchaudio 2.8.0+cpu")
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.set_default_dtype(torch.float32)
    mel = dict(n_fft=480, win_length=480, hop_length=160, f_min=0.0, f_max=8000.0,
               pad=0, n_mels=40, power=2.0, normalized=False, center=True,
               pad_mode="reflect", norm=None, mel_scale="htk")
    transform = torchaudio.transforms.MFCC(sample_rate=16000, n_mfcc=40,
        dct_type=2, norm="ortho", log_mels=False, melkwargs=dict(mel, window_fn=torch.hann_window))
    output.mkdir(parents=True, exist_ok=True)
    cases = []
    for name, pcm in synthetic_cases():
        with torch.inference_mode():
            expected = transform(torch.from_numpy(centered(pcm))).transpose(0, 1).contiguous().numpy()
        assert expected.shape == (101, 40) and np.isfinite(expected).all()
        # Header: ASCII MKF1, int32 sample count, int32 feature count, then little-endian float32.
        data = b"MKF1" + struct.pack("<II", len(pcm), expected.size)
        data += pcm.astype("<f4").tobytes() + expected.astype("<f4").tobytes()
        (output / f"{name}.bin").write_bytes(data)
        cases.append(dict(name=name, samples=len(pcm), bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    manifest = dict(schema_version=1, feature_version=FEATURE_VERSION,
        status="dsp_reference_only_not_checkpoint_validation", upstream_commit=UPSTREAM_COMMIT,
        source="Locally authored analytic signals; no speech, personal recording or training data",
        reference=dict(python=platform.python_version(), platform=platform.platform(),
                       torch=torch.__version__, torchaudio=torchaudio.__version__, numpy=np.__version__,
                       dtype="float32", threads=1, device="cpu"),
        recipe=dict(sample_rate=16000, input_bounds=[8000, 128000],
                    audio_policy="upstream_loadWAV_center_one_second", n_mfcc=40,
                    dct_type=2, norm="ortho", log_mels=False, melkwargs=mel,
                    window="periodic_hann", db_amin=1e-10, db_reference=1.0, top_db=80.0),
        feature_shape=[101, 40], max_absolute_error_limit=0.002, cases=cases)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"Generated {len(cases)} analytic cases; no model inference performed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    generate(parser.parse_args().output)
