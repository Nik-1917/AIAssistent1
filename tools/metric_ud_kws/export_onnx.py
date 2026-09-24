"""Encoder-only export; an audited local manifest and checkpoint are required."""
from verify_reference import arguments, load_reference, extract, sha256
import json
import pathlib

if __name__ == "__main__":
    args = arguments().parse_args()
    manifest, model, features = load_reference(args.manifest, args.weights, args.upstream)
    tensor, _ = extract(args.wav, manifest, model, features)
    import torch
    torch.onnx.export(model, (tensor,), args.output, opset_version=17, dynamo=False,
        input_names=["features"], output_names=["embedding"],
        dynamic_axes={"features": {1: "frames"}})
    import onnx
    onnx.checker.check_model(onnx.load(args.output))
    pathlib.Path(args.output + ".json").write_text(json.dumps({
        "sha256": sha256(args.output), "opset": 17, "input": "features",
        "output": "embedding", "feature_shape": [1, "frames", manifest["preprocessing"]["n_mfcc"]],
        "embedding_size": manifest["embedding_size"], "parity_passed": False
    }, indent=2), encoding="utf-8")
