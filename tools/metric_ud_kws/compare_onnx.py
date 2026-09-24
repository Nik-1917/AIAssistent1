"""Compare one exported reference fixture. Does not establish Russian quality."""
import argparse
import json
import pathlib
from verify_reference import normalized, sha256

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if sha256(args.model) != args.model_sha256:
        raise ValueError("ONNX SHA-256 mismatch")
    import numpy as np
    import onnxruntime as ort
    fixture = json.loads(pathlib.Path(args.fixture).read_text(encoding="utf-8"))
    session = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    actual = session.run(["embedding"], {"features": np.array(fixture["features"], dtype=np.float32)})[0]
    expected = np.asarray(fixture["embedding"])
    if actual.shape != (1, len(expected)):
        raise ValueError("Output shape mismatch")
    actual = np.asarray(normalized(actual[0].tolist()))
    error = float(np.max(np.abs(expected - actual)))
    cosine = float(np.dot(expected, actual))
    passed = bool(error <= 1e-4 and cosine >= 0.9999)
    pathlib.Path(args.output).write_text(json.dumps({"onnx_sha256": args.model_sha256,
        "fixture_sha256": sha256(args.fixture), "max_abs_error": error,
        "cosine": cosine, "passed": passed}, indent=2, allow_nan=False), encoding="utf-8")
    if not passed:
        raise SystemExit("Parity FAILED")
