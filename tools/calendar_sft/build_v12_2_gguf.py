"""Finish the approved V12.2 GGUF build using pinned local conversion tools.

No training or question generation. Calibration copies existing train records;
smoke inputs retain the original context and never include reference answers.
Completed subprocesses are hash-checked on resume and never silently replaced.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict, deque
from copy import deepcopy
from datetime import datetime, timezone
import gc
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from time import perf_counter
import zipfile

from dataset_contract import file_sha256, load_jsonl, RUNTIME_SYSTEM_RE
from dataset_provenance import verify_dataset_provenance
from evaluate_v12_2_adapter import score_rows

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "docs/calendar_sft_v12_2"
RUN = ROOT / "build/calendar_sft_qwen3_v12_2_epoch1_20260907"
BASE = ROOT / "build/calendar_sft_models/Qwen3-4B-Instruct-2507/cdbee75f17c01a7cc42f958dc650907174af0554"
MERGED = ROOT / "build/calendar_sft_qwen3_v12_2_merged_bf16_20260908"
OUT = ROOT / "build/calendar_sft_qwen3_v12_2_gguf_20260908"
SOURCE = ROOT / "build/llama.cpp-v0.3.0"
BIN = ROOT / "build/llama-b10621-bin-win-cpu-x64"
PYTHON = ROOT / "build/calendar_sft_local_gpu_venv/Scripts/python.exe"
LOCK = ROOT / "tools/calendar_sft/clean_room_qwen3_source_lock.json"
PREFIX = "calendar-assistant-v12.2"
QUANTS = {"Q8_0": 7, "Q5_K_M": 17, "Q4_K_M": 15, "Q3_K_M": 12, "Q3_K_S": 11, "IQ3_XXS": 23}
SMOKE_IDS = ("H004", "H008", "H011", "H12101", "H12102", "V122H02", "V122H07")
ADAPTER_SHA = "81ac921094ac02311386fc1082acfafb5e434a95fb20690534af6842760b4a96"
COMMIT = "c1d0e7a004015f23bc0233470b747b596f29b264"


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict | list) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def hashes(paths) -> dict:
    return {str(path.resolve()): file_sha256(path) for path in paths}


def select_calibration(train: list[dict], count: int = 256) -> list[int]:
    if count <= 0 or count > len(train):
        raise ValueError("invalid calibration count")
    buckets = defaultdict(deque)
    for index, row in enumerate(train):
        buckets[row["category"]].append(index)
    result = []
    while len(result) < count:
        for category in sorted(buckets):
            if buckets[category]:
                result.append(buckets[category].popleft())
                if len(result) == count:
                    break
    return result


def select_smoke(train: list[dict], holdout: list[dict]) -> list[dict]:
    predicates = (
        ("IDENTITY", lambda row: row["category"] == "manual_v10_identity" and row["messages"][1]["content"].isupper()),
        ("NOTE", lambda row: row["category"] == "manual_v10_note_add"),
        ("DURATION", lambda row: row["category"] == "manual_v6_duration_mixed" and json.loads(row["messages"][-1]["content"])["params"].get("duration_min") == 200),
    )
    result = []
    for label, predicate in predicates:
        index = next(index for index, row in enumerate(train) if predicate(row))
        row = deepcopy(train[index])
        row.update({"case_id": f"SMOKE_TRAIN_{label}", "source_split": "train", "source_row_1based": index + 1})
        result.append(row)
    by_id = {row["case_id"]: row for row in holdout}
    if len(by_id) != len(holdout):
        raise ValueError("duplicate holdout IDs")
    for case_id in SMOKE_IDS:
        row = deepcopy(by_id[case_id])
        row["source_split"] = "holdout" if case_id.startswith("V122") else "regression_holdout"
        result.append(row)
    return result


def completion_text(raw: str) -> str:
    text = raw.strip()
    marker = "[end of text]"
    return text[:-len(marker)].rstrip() if text.endswith(marker) else text


def run_step(name: str, command: list, outputs: tuple[Path, ...] = (), *, timeout: int = 7200, cwd: Path = ROOT) -> dict:
    logs = OUT / "logs"
    logs.mkdir(exist_ok=True)
    record_path = logs / f"{name}.json"
    command = [str(item) for item in command]
    if record_path.exists():
        record = read_json(record_path)
        if record["exit_code"] != 0 or record["command"] != command or record["cwd"] != str(cwd):
            raise ValueError(f"cannot silently replace recorded step: {name}")
        if hashes(Path(path) for path in record["output_sha256"]) != record["output_sha256"]:
            raise ValueError(f"completed output changed: {name}")
        return record
    stdout, stderr = logs / f"{name}.stdout.log", logs / f"{name}.stderr.log"
    if any(path.exists() for path in (*outputs, stdout, stderr)):
        raise ValueError(f"unrecorded partial outputs exist for {name}; inspect before retrying")
    environment = os.environ.copy()
    environment.update({"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "PYTHONDONTWRITEBYTECODE": "1", "PYTHONIOENCODING": "utf-8", "OMP_NUM_THREADS": "12"})
    started = perf_counter()
    print(f"Starting {name}", flush=True)
    with stdout.open("wb") as out, stderr.open("wb") as err:
        completed = subprocess.run(command, cwd=cwd, env=environment, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                   timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), check=False)
    record = {"command": command, "cwd": str(cwd), "exit_code": completed.returncode,
              "seconds": perf_counter() - started, "stdout": str(stdout), "stderr": str(stderr)}
    record["output_sha256"] = hashes(path for path in (*outputs, stdout, stderr) if path.is_file())
    write_json(record_path, record)
    if completed.returncode != 0 or any(not path.is_file() for path in outputs):
        raise RuntimeError(f"{name} failed; inspect {stderr}")
    print(f"Completed {name}: {record['seconds']:.1f}s", flush=True)
    return record


def verify_tools() -> dict:
    previous = read_json(ROOT / "build/calendar_sft_qwen3_v12_gguf_20260907/gguf_manifest.json")["converter"]
    archive = ROOT / "build/llama-b10621-bin-win-cpu-x64.zip"
    if file_sha256(archive) != previous["windows_archive_sha256"]:
        raise ValueError("pinned llama.cpp archive mismatch")
    checked = {}
    with zipfile.ZipFile(archive) as bundle:
        for info in bundle.infolist():
            if info.is_dir():
                continue
            path = BIN / info.filename
            if not path.is_file():
                path = BIN / Path(info.filename).name
            if not path.is_file() or BIN.resolve() not in path.resolve().parents:
                raise ValueError("missing or unsafe archive member")
            expected = sha256(bundle.read(info)).hexdigest()
            if file_sha256(path) != expected:
                raise ValueError(f"binary differs from verified archive: {path}")
            checked[str(path)] = expected
    commit = subprocess.check_output(["git", "-C", str(SOURCE), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.check_output(["git", "-C", str(SOURCE), "status", "--porcelain"], text=True).strip()
    if commit != COMMIT or dirty or file_sha256(SOURCE / "convert_hf_to_gguf.py") != previous["convert_script_sha256"]:
        raise ValueError("converter source must match the pinned clean checkout")
    return {"commit": commit, "source_clean": True, "archive_sha256": file_sha256(archive), "verified_binary_sha256": checked}


def prepare() -> dict:
    source_integrity = read_json(OUT / "source_integrity.json")
    training = read_json(RUN / "run_manifest.json")
    if source_integrity["source_files_sha256"] != training["source_integrity"]["source_files_sha256"]:
        raise ValueError("base source differs from the training run")
    if file_sha256(RUN / "adapter/adapter_model.safetensors") != ADAPTER_SHA:
        raise ValueError("wrong V12.2 adapter")
    if training["result"]["completed_optimizer_steps"] != 119:
        raise ValueError("V12.2 training is incomplete")
    tools = verify_tools()
    provenance = verify_dataset_provenance(DATA / "provenance.json", DATA / "train.jsonl", DATA / "validation.jsonl")
    protected = [DATA / name for name in ("train.jsonl", "validation.jsonl", "holdout.jsonl", "regression_holdout.jsonl", "manifest.json", "provenance.json")]
    protected += [RUN / "adapter/adapter_model.safetensors", RUN / "adapter/adapter_config.json", RUN / "run_manifest.json", LOCK]
    protected_hashes = hashes(protected)
    protected_hashes.update({str(BASE / name): digest for name, digest in source_integrity["source_files_sha256"].items()})
    path = OUT / "build_inputs.json"
    if path.exists():
        previous = read_json(path)
        if previous["protected_sha256"] != protected_hashes:
            raise ValueError("source inputs changed while resuming")
        if hashes(Path(file) for file in previous["prepared_sha256"]) != previous["prepared_sha256"]:
            raise ValueError("prepared prompts changed while resuming")
        return previous
    from transformers import AutoTokenizer

    train = load_jsonl(DATA / "train.jsonl")
    held = load_jsonl(DATA / "regression_holdout.jsonl") + load_jsonl(DATA / "holdout.jsonl")
    indices = select_calibration(train)
    tokenizer = AutoTokenizer.from_pretrained(BASE, local_files_only=True, trust_remote_code=False)
    calibration = "\n".join(tokenizer.apply_chat_template(train[index]["messages"], tokenize=False, add_generation_prompt=False) for index in indices)
    calibration_path = OUT / "imatrix_calibration_train_v12_2.txt"
    calibration_path.write_text(calibration, encoding="utf-8")
    smoke = select_smoke(train, held)
    prepared = [calibration_path]
    for row in smoke:
        if RUNTIME_SYSTEM_RE.fullmatch(row["messages"][0]["content"]) is None:
            raise ValueError("smoke context must remain the original minimal prompt")
        prompt = tokenizer.apply_chat_template(row["messages"][:-1], tokenize=False, add_generation_prompt=True)
        prompt_path = OUT / f"smoke_{row['case_id']}.txt"
        prompt_path.write_text(prompt, encoding="utf-8")
        prepared.append(prompt_path)
    smoke_path = OUT / "smoke_inputs.json"
    write_json(smoke_path, smoke)
    prepared.append(smoke_path)
    shutil.copy2(BASE / "LICENSE", OUT / "LICENSE")
    for notice in BASE.glob("NOTICE*"):
        if notice.is_file():
            shutil.copy2(notice, OUT / notice.name)
    (OUT / "MODIFICATIONS.txt").write_text(
        "Calendar Assistant V12.2\nDerived from Qwen/Qwen3-4B-Instruct-2507, revision "
        "cdbee75f17c01a7cc42f958dc650907174af0554.\n"
        "Modified with the project's V12.2 supervised adapter, merged to BF16, converted to F16 GGUF and quantized.\n"
        "Source license: Apache-2.0; see LICENSE. No endorsement by the upstream authors is implied.\n"
        "Runtime context remains unchanged. GGUF conversion does not repair model errors.\n", encoding="utf-8")
    old = ROOT / "build/calendar_sft_qwen3_v12_gguf_20260907"
    result = {
        "created_at_utc": datetime.now(timezone.utc).isoformat(), "version": "v12.2", "adapter_sha256": ADAPTER_SHA,
        "source_integrity": source_integrity, "tools": tools, "provenance": provenance,
        "protected_sha256": protected_hashes, "prepared_sha256": hashes(prepared),
        "previous_v12_files": {str(file): {"bytes": file.stat().st_size, "mtime_ns": file.stat().st_mtime_ns} for file in old.iterdir() if file.is_file()},
        "calibration": {"split": "train", "source_rows": len(train), "selected_rows": len(indices),
                        "selected_categories": len({train[index]["category"] for index in indices}),
                        "selection": "sorted-category round-robin in original source order",
                        "source_row_numbers_1based": [index + 1 for index in indices],
                        "rendered_tokens_hf": len(tokenizer.encode(calibration, add_special_tokens=False)),
                        "context_size": 512, "requested_chunks": "all complete chunks; trailing incomplete chunk excluded by llama-imatrix",
                        "validation_used": False, "holdout_used": False},
        "smoke": {"cases": len(smoke), "train_cases": 3, "holdout_cases": len(SMOKE_IDS),
                  "scope": "conversion smoke, not independent full holdout or Android evaluation",
                  "settings": {"context_size": 512, "max_new_tokens": 192, "temperature": 0, "top_k": 1, "top_p": 1, "threads": 12}},
    }
    write_json(path, result)
    return result


def verify_merged() -> dict:
    import torch
    from safetensors import safe_open

    path = OUT / "merged_validation.json"
    if path.exists():
        result = read_json(path)
        if hashes(Path(file) for file in result["sha256"]) != result["sha256"]:
            raise ValueError("merged checkpoint changed")
        return result
    merged_manifest = read_json(MERGED / "merge_manifest.json")
    if merged_manifest["adapter_config_sha256"] != file_sha256(RUN / "adapter/adapter_config.json") or merged_manifest["source_lock_sha256"] != file_sha256(LOCK):
        raise ValueError("merged checkpoint has wrong inputs")
    config = read_json(MERGED / "config.json")
    if config["model_type"] != "qwen3" or read_json(MERGED / "tokenizer_config.json")["chat_template"] != read_json(BASE / "tokenizer_config.json")["chat_template"]:
        raise ValueError("merged architecture or chat template changed")
    index = read_json(MERGED / "model.safetensors.index.json")["weight_map"]
    seen = set()
    for shard in sorted(set(index.values())):
        with safe_open(MERGED / shard, framework="pt", device="cpu") as reader:
            for name in reader.keys():
                if name in seen or "lora_" in name or index.get(name) != shard:
                    raise ValueError("unexpected merged tensor")
                seen.add(name)
                tensor = reader.get_tensor(name).reshape(-1)
                for start in range(0, tensor.numel(), 1048576):
                    if not torch.isfinite(tensor[start:start + 1048576]).all().item():
                        raise ValueError(f"nonfinite merged tensor: {name}")
                del tensor
    if len(seen) != 398 or seen != set(index):
        raise ValueError("wrong merged tensor count")
    result = {"tensor_count": len(seen), "all_tensors_finite": True, "sha256": hashes(file for file in MERGED.iterdir() if file.is_file())}
    write_json(path, result)
    return result


def inspect_gguf(path: Path, expected_type: int | None = None) -> dict:
    sys.path.insert(0, str(SOURCE / "gguf-py"))
    from gguf import GGUFReader
    import numpy as np

    reader = GGUFReader(path)
    fields = reader.fields
    size = path.stat().st_size
    for tensor in reader.tensors:
        if tensor.data_offset + tensor.n_bytes > size or tensor.n_elements <= 0:
            raise ValueError("invalid GGUF tensor bounds")
        if np.issubdtype(tensor.data.dtype, np.floating):
            flat = tensor.data.reshape(-1)
            for start in range(0, len(flat), 1048576):
                if not np.isfinite(flat[start:start + 1048576]).all():
                    raise ValueError("nonfinite GGUF tensor")
    result = {"file": path.name, "bytes": size, "sha256": file_sha256(path),
              "gguf_version": fields["GGUF.version"].contents(), "tensor_count": len(reader.tensors)}
    if expected_type is not None:
        template = fields["tokenizer.chat_template"].contents()
        if fields["general.architecture"].contents() != "qwen3" or fields["general.file_type"].contents() != expected_type or len(reader.tensors) != 398:
            raise ValueError("wrong GGUF architecture, type or tensor count")
        if len(fields["tokenizer.ggml.tokens"].data) != 151936:
            raise ValueError("GGUF vocabulary size changed")
        if template != read_json(BASE / "tokenizer_config.json")["chat_template"]:
            raise ValueError("GGUF chat template changed")
        result.update({"file_type": expected_type, "chat_template_sha256": sha256(template.encode()).hexdigest(),
                       "tensor_types": dict(Counter(tensor.tensor_type.name for tensor in reader.tensors))})
    else:
        if fields["general.type"].contents() != "imatrix":
            raise ValueError("expected an importance matrix")
        result.update({"chunks": fields["imatrix.chunk_count"].contents(), "chunk_size": fields["imatrix.chunk_size"].contents(),
                       "datasets": fields["imatrix.datasets"].contents()})
        if result["chunks"] <= 0 or result["chunk_size"] != 512:
            raise ValueError("empty or unexpected calibration")
    del reader
    gc.collect()
    return result


def build() -> dict:
    inputs = prepare()
    merged = verify_merged()
    f16 = OUT / f"{PREFIX}-F16.gguf"
    run_step("convert_f16", [PYTHON, "-B", SOURCE / "convert_hf_to_gguf.py", MERGED, "--outfile", f16, "--outtype", "f16", "--model-name", "Calendar Assistant V12.2"], (f16,))
    models = {"F16": inspect_gguf(f16, 1)}
    matrix = OUT / "imatrix-train-v12.2.gguf"
    run_step("imatrix", [BIN / "llama-imatrix.exe", "--offline", "-m", f16, "-f", OUT / "imatrix_calibration_train_v12_2.txt",
                         "-o", matrix, "--output-format", "gguf", "-c", "512", "-b", "512", "-ub", "512", "--chunks", "-1",
                         "-t", "12", "-ngl", "0", "--parse-special", "--no-ppl", "--no-escape", "--log-colors", "off"], (matrix,))
    matrix_info = inspect_gguf(matrix)
    for quant, code in QUANTS.items():
        path = OUT / f"{PREFIX}-{quant}.gguf"
        extra = ["--imatrix", matrix] if quant == "IQ3_XXS" else []
        run_step(f"quantize_{quant}", [BIN / "llama-quantize.exe", *extra, f16, path, quant, "12"], (path,))
        models[quant] = inspect_gguf(path, code)
        write_json(OUT / "models_validation.json", models)
    smoke = read_json(OUT / "smoke_inputs.json")
    reports, timings = {}, {}
    for quant in models:
        predictions, runs = [], []
        for row in smoke:
            record = run_step(f"smoke_{quant}_{row['case_id']}", [BIN / "llama-completion.exe", "--offline", "-m", OUT / models[quant]["file"],
                              "-f", OUT / f"smoke_{row['case_id']}.txt", "-c", "512", "-n", "192", "-t", "12", "-b", "512", "-ub", "512",
                              "-ngl", "0", "--temp", "0", "--top-k", "1", "--top-p", "1", "--min-p", "0", "--seed", "20260825",
                              "--repeat-penalty", "1", "--no-conversation", "--no-display-prompt", "--no-escape", "--simple-io", "--color", "off", "--log-colors", "off"], timeout=600)
            raw = Path(record["stdout"]).read_text(encoding="utf-8")
            predictions.append({"case_id": row["case_id"], "output": completion_text(raw)})
            runs.append({"case_id": row["case_id"], "seconds": record["seconds"], "exit_code": record["exit_code"], "stdout": record["stdout"], "stderr": record["stderr"]})
        report = score_rows(smoke, predictions)
        reference = reports.get("F16", report)
        report["same_intent_params_as_f16"] = sum(a["actual"].get("intent") == b["actual"].get("intent") and a["actual"].get("params") == b["actual"].get("params") for a, b in zip(report["cases"], reference["cases"]))
        reports[quant], timings[quant] = report, runs
        write_json(OUT / f"smoke_{quant}_report.json", report)
        print(json.dumps({"model": quant, "metrics": report["metrics"], "same_intent_params_as_f16": report["same_intent_params_as_f16"]}), flush=True)
    write_json(OUT / "smoke_results.json", {"reports": reports, "timings": timings, "method": inputs["smoke"]})
    current = hashes(Path(path) for path in inputs["protected_sha256"])
    if current != inputs["protected_sha256"]:
        raise ValueError("source model, adapter or dataset changed")
    old_now = {path: {"bytes": Path(path).stat().st_size, "mtime_ns": Path(path).stat().st_mtime_ns} for path in inputs["previous_v12_files"]}
    if old_now != inputs["previous_v12_files"]:
        raise ValueError("previous V12 artifacts changed")
    manifest = {"version": "v12.2", "status": "COMPLETE_ARTIFACTS_SMOKE_EVALUATED", "completed_at_utc": datetime.now(timezone.utc).isoformat(),
                "inputs": inputs, "merged": merged, "importance_matrix": matrix_info, "models": models,
                "smoke_metrics": {quant: {"total": report["total"], "metrics": report["metrics"], "same_intent_params_as_f16": report["same_intent_params_as_f16"]} for quant, report in reports.items()},
                "source_inputs_unchanged": True, "previous_v12_files_unchanged": True,
                "runtime_context_changed": False, "training_run": False, "data_generator_run": False,
                "full_gguf_holdout": "NOT_RUN", "android_device_test": "NOT_RUN", "script_sha256": file_sha256(Path(__file__))}
    (OUT / "GGUF_SHA256SUMS.txt").write_text("".join(f"{model['sha256']}  {model['file']}\n" for model in models.values()), encoding="ascii")
    manifest["supporting_files_sha256"] = hashes(file for file in OUT.iterdir() if file.is_file() and file.name != "gguf_manifest.json" and file.suffix != ".gguf")
    write_json(OUT / "gguf_manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if args.prepare_only:
        result = prepare()
        print(json.dumps({"prepared": True, "calibration": result["calibration"], "smoke": result["smoke"]}), flush=True)
    else:
        result = build()
        print(json.dumps({"status": result["status"], "output_directory": str(OUT)}), flush=True)


if __name__ == "__main__":
    main()
