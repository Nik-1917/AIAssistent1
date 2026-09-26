"""Create a read-only ARM ART parity bundle from built application DEX/JNI.

No installs, network or device operations. Host artifacts only; adb commands are
explicit separate actions. Only fixed native entries and top-level DEX are copied.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import zipfile

from prepare_russian import digest


def prepare(args):
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((args.assets / "manifest.json").read_text(encoding="utf-8"))
    if digest(args.assets / "encoder.onnx") != manifest["model_sha256"]:
        raise ValueError("Changed model")
    with zipfile.ZipFile(args.apk) as source, zipfile.ZipFile(args.output / "app-code.jar", "w", zipfile.ZIP_DEFLATED) as target:
        for name in source.namelist():
            if re.fullmatch(r"classes(?:[0-9]+)?\.dex", name):
                target.writestr(name, source.read(name))
        for name in ("libassistant_metric_kws.so", "libonnxruntime.so", "libc++_shared.so"):
            (args.output / name).write_bytes(source.read("lib/arm64-v8a/" + name))
    with zipfile.ZipFile(args.output / "parity.jar", "w", zipfile.ZIP_DEFLATED) as target:
        target.writestr("classes.dex", args.dex.read_bytes())
    shutil.copyfile(args.assets / "encoder.onnx", args.output / "encoder.onnx")
    lines = []
    for case in manifest["cases"]:
        name = Path(case["asset"]).name
        source = args.assets / name if (args.assets / name).is_file() else args.analytic / name
        if digest(source) != case["sha256"]:
            raise ValueError("Changed fixture")
        shutil.copyfile(source, args.output / name)
        lines.append("\t".join([name, case["sha256"]] + [str(v) for v in case["expected_embedding"]]))
    (args.output / "cases.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    evidence = dict(source_apk_sha256=digest(args.apk), model_sha256=manifest["model_sha256"],
                    abi="arm64-v8a", files={p.name: digest(p) for p in sorted(args.output.iterdir()) if p.is_file()})
    (args.output.parent / "bundle-provenance.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(files=len(evidence["files"]), model_sha256=evidence["model_sha256"])))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--assets", type=Path, required=True)
    p.add_argument("--dex", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--analytic", type=Path, default=Path("app/src/test/resources/metric_kws"))
    prepare(p.parse_args())
