"""Add Q8_0 after the frozen V12.67 pipeline; evaluate with its full system prompt."""
from __future__ import annotations

import argparse
import ctypes
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import traceback

HERE = Path(__file__).resolve().parent
BASE = HERE.parent / "qwen3_06b_v12_67"
sys.path.insert(0, str(BASE))
import run_context as profile
import release_evaluation as baseline
from build_gguf import inspect
from build_v12_2_gguf import verify_tools
from evaluate_v12_67 import case_digest, score_cached
from v12_67_contract import SYSTEM_RE
from v12_67_evaluation import summary
from v12_67_training import CONTRACT, PROVIDER

ROOT, RUN, CONFIG = profile.ROOT, profile.RUN, profile.CONFIG
read, write, digest, now = profile.read, profile.write, profile.digest, profile.now
OUT = ROOT / "build/calendar_v12_67_qwen3_06b_q8_comparison"
DOC = ROOT / "docs/CALENDAR_V12_67_QWEN3_06B_Q8_COMPARISON.md"
ENGINE = ROOT / "app/src/main/java/com/example/aiassistent1/data/engine/LlamatikEngine.kt"
PARENT = RUN / "pipeline.json"
PREPARED, FREEZE, STATE = (OUT / name for name in ("prepared_inputs.json", "freeze.json", "pipeline.json"))
QUANTIZER = ROOT / "build/llama-b10621-bin-win-cpu-x64/llama-quantize.exe"
STAGES = ["training", "gguf_build", "f16_evaluation", "q4_evaluation", "quantization_comparison"]
os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", TOKENIZERS_PARALLELISM="false")


def settings():
    return dict(decoding=dict(n_predict=CONFIG["max_new_tokens"], temperature=0, top_k=1,
                             top_p=1, min_p=0, seed=CONFIG["seed"], repeat_penalty=1,
                             cache_prompt=False, stream=False),
                server_sha256=digest(baseline.BINARY), context=baseline.CONTEXT,
                grammar_constraint=False, holdout_used_for_selection=False,
                physical_device_inference="NOT_RUN")


def validate_packs(packs):
    """Require the whole current contract and literal system/user transport, without a gold answer."""
    provider_source = PROVIDER.read_text(encoding="utf-8")
    engine_source = ENGINE.read_text(encoding="utf-8")
    if '"cегодня $currentDateTime день недели $dayOfWeek ответ JSON\\n$CALENDAR_CONTRACT"' not in provider_source:
        raise ValueError("Android system header implementation changed")
    for source_line in ('append("<|im_start|>$role\\n")', "append(message.content)",
                        'append("\\n<|im_end|>\\n")', 'append("<|im_start|>assistant\\n")'):
        if source_line not in engine_source:
            raise ValueError("Android prompt implementation changed")
    if set(packs) != set(CONFIG["training_transports"]):
        raise ValueError("Different evaluation transports")
    ids = []
    for transport, pack in packs.items():
        binding = pack["binding"]
        if (len(pack["cases"]) != 412 or len(pack["prompts"]) != 412
                or binding["rows"] != 412 or len(binding["suites"]) != 13
                or binding["transport"] != transport or binding["holdout_used_for_selection"]
                or binding["provider_sha256"] != digest(PROVIDER)
                or binding["cases_sha256"] != case_digest(pack["cases"])):
            raise ValueError("Independent inputs or system provider binding changed")
        for case, item in zip(pack["cases"], pack["prompts"]):
            anchor = SYSTEM_RE.fullmatch(case["source_system"])
            if not anchor:
                raise ValueError("Invalid frozen date/time anchor")
            header = case["source_system"] if transport == "standard_chatml" else (
                f"cегодня {anchor[1]} {anchor[3]} день недели {anchor[2]} ответ JSON")
            if case["system"] != header + "\n" + CONTRACT:
                raise ValueError("Missing or changed full system prompt")
            newline = "\n" if transport == "android_runtime" else ""
            expected = ("<|im_start|>system\n" + case["system"] + newline + "<|im_end|>\n"
                        + "<|im_start|>user\n" + case["user"] + newline + "<|im_end|>\n"
                        + "<|im_start|>assistant\n")
            if (item["case_id"] != case["id"] or item["case_sha256"] != case_digest(case)
                    or item["prompt"] != expected):
                raise ValueError("System/user transport changed or an answer was added to the prompt")
            if case["suite"] in ("train", "validation", "development"):
                raise ValueError("Selection/training data cannot become independent tests")
            ids.append(case["id"])
    if len(ids) != 824 or len(set(ids)) != 824:
        raise ValueError("Independent evaluation inventory must contain 824 unique cases")
    return dict(full_system_prompt=True, cases_per_format=824, cases_per_transport=412,
                system_contract_text_sha256=sha256(CONTRACT.encode("utf-8")).hexdigest(),
                provider_sha256=digest(PROVIDER), engine_sha256=digest(ENGINE),
                model_input_roles=["system", "user"], assistant_gold_in_model_input=False,
                android_transport_verified_against_source=True,
                fixed_per_case_date_time=True, physical_device_inference="NOT_RUN")


def check_context(packs):
    from transformers import AutoTokenizer
    _, source = profile.verify_source(CONFIG["model"])
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True, trust_remote_code=False)
    sizes = {}
    for transport, pack in packs.items():
        counts = [len(tokenizer.encode(item["prompt"], add_special_tokens=False))
                  for item in pack["prompts"]]
        if max(counts) + CONFIG["max_new_tokens"] + 1 > baseline.CONTEXT:
            raise ValueError("Full system prompt and answer would exceed context")
        sizes[transport] = dict(prompts=len(counts), maximum_prompt_tokens=max(counts),
                               answer_budget=CONFIG["max_new_tokens"], context=baseline.CONTEXT,
                               truncated_prompts=0)
    return sizes


def check_hashes(paths):
    for name, expected in paths.items():
        if digest(name) != expected:
            raise ValueError("Frozen input changed: " + name)


def parent_ready(parent):
    if parent["status"] == "FAILED":
        raise RuntimeError("Original training/build/evaluation pipeline failed; preserved its evidence")
    if parent["status"] == "RUNNING":
        return False
    if (parent["status"] != "COMPLETE"
            or [step["stage"] for step in parent["steps"]] != STAGES
            or any(step["exit_code"] != 0 for step in parent["steps"])):
        raise ValueError("Original pipeline completion is incomplete")
    return True


def prepare():
    if OUT.exists():
        raise ValueError("Refusing to overwrite an existing Q8 comparison")
    parent = read(PARENT)
    parent_ready(parent)
    check_hashes(parent["frozen_inputs"])
    profile.verify_launch_inputs()
    preservation = profile.verify_preservation()
    tools = verify_tools()
    packs = baseline.independent_inputs()
    attestation = validate_packs(packs)
    token_budget = check_context(packs)
    OUT.mkdir()
    write(PREPARED, dict(version="v12.67", model=CONFIG["model"], prepared_at=now(),
          baseline_pipeline=str(PARENT), baseline_frozen_inputs_sha256=case_digest(parent["frozen_inputs"]),
          packs=packs, settings=settings(), system_prompt_attestation=attestation,
          token_budget=token_budget, tools=tools, preservation=preservation))
    frozen = dict(parent["frozen_inputs"])
    for path in [*sorted(HERE.glob("*.py")), DOC, ENGINE, PREPARED]:
        frozen[str(path)] = digest(path)
    write(FREEZE, frozen)
    print("Prepared 824 identical full-system prompts; no inference or training change", flush=True)


def verify_plan(expected_freeze_sha=None):
    if expected_freeze_sha is not None and digest(FREEZE) != expected_freeze_sha:
        raise ValueError("Q8 launch freeze changed")
    check_hashes(read(FREEZE))
    plan = read(PREPARED)
    if plan["version"] != "v12.67" or plan["model"] != CONFIG["model"] or plan["settings"] != settings():
        raise ValueError("Wrong model, version or decoding settings")
    if validate_packs(plan["packs"]) != plan["system_prompt_attestation"]:
        raise ValueError("Full system prompt attestation changed")
    parent = read(PARENT)
    if case_digest(parent["frozen_inputs"]) != plan["baseline_frozen_inputs_sha256"]:
        raise ValueError("Original launch binding changed")
    profile.verify_launch_inputs()
    profile.verify_preservation()
    if verify_tools() != plan["tools"]:
        raise ValueError("Pinned inference/quantization tools changed")
    return plan


def baseline_artifacts(plan):
    parent = read(PARENT)
    if not parent_ready(parent):
        raise ValueError("Q8 cannot build or test before the original pipeline completes")
    if case_digest(parent["frozen_inputs"]) != plan["baseline_frozen_inputs_sha256"]:
        raise ValueError("Different original training launch")
    training = read(RUN / "training.json")
    manifest = read(RUN / "release/gguf_manifest.json")
    if (training["status"] != "COMPLETE"
            or training["completed_optimizer_steps"] != training["config"]["planned_optimizer_steps"]
            or manifest["status"] != "COMPLETE" or manifest["dataset_version"] != "v12.67"
            or manifest["adapter_sha256"] != training["adapter_sha256"]
            or manifest["selected_step"] != training["selected_step"]
            or digest(RUN / "adapter/adapter_model.safetensors") != training["adapter_sha256"]
            or manifest["tools"] != plan["tools"]
            or manifest["source_revision"] != CONFIG["source_revision"]
            or manifest["source_repository"] != CONFIG["repository"]):
        raise ValueError("Completed baseline checkpoint/build binding changed")
    check_hashes(training["frozen_inputs"])
    for key, file_type in (("intermediate", 1), ("model", 15)):
        artifact = manifest[key]
        if (artifact["architecture"] != "qwen3" or artifact["file_type"] != file_type
                or digest(artifact["file"]) != artifact["sha256"]):
            raise ValueError("F16/Q4 baseline artifact changed")
    return manifest


def build_q8(plan):
    manifest = baseline_artifacts(plan)
    _, source = profile.verify_source(CONFIG["model"])
    folder, release = OUT / "build", OUT / "release"
    folder.mkdir(exist_ok=False)
    release.mkdir(exist_ok=False)
    f16 = Path(manifest["intermediate"]["file"])
    model = release / "calendar-assistant-v12.67-Qwen3-0.6B-Q8_0.gguf"
    command = [str(QUANTIZER), str(f16), str(model), "Q8_0", "12"]
    started = time.monotonic()
    with (folder / "quantize.stdout.log").open("xb") as out, (folder / "quantize.stderr.log").open("xb") as err:
        result = subprocess.run(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    write(folder / "quantize.json", dict(command=command, exit_code=result.returncode,
                                       runtime_seconds=time.monotonic() - started))
    if result.returncode != 0:
        raise RuntimeError("Q8_0 quantization failed; see preserved logs")
    artifact = inspect(model, "qwen3", 7)
    from gguf import GGUFReader
    inventories = []
    for path in (f16, Path(manifest["model"]["file"]), model):
        reader = GGUFReader(path)
        names = {tensor.name for tensor in reader.tensors}
        blocks = {int(match[1]) for name in names if (match := re.match(r"blk\.(\d+)\.", name))}
        if int(reader.fields["qwen3.block_count"].contents()) != 28 or blocks != set(range(28)):
            raise ValueError("Qwen3-0.6B layer inventory changed")
        inventories.append((names, reader.fields["tokenizer.chat_template"].contents()))
        del reader
    if inventories[0] != inventories[1] or inventories[0] != inventories[2]:
        raise ValueError("Quantization changed tensor names or the Android chat template")
    if digest(f16) != manifest["intermediate"]["sha256"]:
        raise ValueError("F16 changed during Q8 quantization")
    loader = baseline.load_smoke(model, folder)
    shutil.copy2(source / "LICENSE", release / "LICENSE")
    for notice in source.glob("NOTICE*"):
        if notice.is_file():
            shutil.copy2(notice, release / notice.name)
    (release / "MODIFICATIONS.txt").write_text(
        "Calendar Assistant V12.67 Qwen3-0.6B\n"
        f"Derived from {CONFIG['repository']} at {CONFIG['source_revision']}.\n"
        f"Same selected V12.67 adapter as F16/Q4_K_M: {manifest['adapter_sha256']}.\n"
        "Quantized directly from that F16 artifact to Q8_0; direct-JSON Android ChatML template retained.\n"
        "Text-only model. Source license: Apache-2.0, see LICENSE.\n", encoding="utf-8")
    (release / "SHA256SUMS.txt").write_text(artifact["sha256"] + "  " + model.name + "\n", encoding="ascii")
    write(release / "gguf_manifest.json", dict(status="COMPLETE", completed_at=now(), model=artifact,
          intermediate=manifest["intermediate"], adapter_sha256=manifest["adapter_sha256"],
          selected_step=manifest["selected_step"], source_repository=manifest["source_repository"],
          source_revision=manifest["source_revision"], tools=plan["tools"], dataset_version="v12.67",
          baseline_manifest_sha256=digest(RUN / "release/gguf_manifest.json"),
          prepared_inputs_sha256=digest(PREPARED), loader_smoke=loader,
          generation_quality_at_build="NOT_TESTED", physical_device_inference="NOT_RUN"))
    print("Q8_0 built from the identical selected F16 checkpoint", flush=True)


def assert_same_inputs(inputs, plan):
    if inputs["packs"] != plan["packs"]:
        raise ValueError("Full system prompts, temporal anchors or cases differ")
    for key, expected in plan["settings"].items():
        if inputs.get(key) != expected:
            raise ValueError("Inference settings differ: " + key)


def evaluate_q8(plan, record):
    manifest = read(OUT / "release/gguf_manifest.json")
    model = Path(manifest["model"]["file"])
    model_sha = digest(model)
    if (manifest["status"] != "COMPLETE" or manifest["model"]["sha256"] != model_sha
            or manifest["prepared_inputs_sha256"] != digest(PREPARED)):
        raise ValueError("Q8 artifact/input binding changed")
    folder = OUT / "evaluation_q8_0"
    folder.mkdir(exist_ok=False)
    (folder / "cases").mkdir()
    write(folder / "inputs.json", dict(model=str(model), model_sha256=model_sha, packs=plan["packs"],
                                      **plan["settings"]))
    answers = {transport: [] for transport in plan["packs"]}
    completed = 0
    with baseline.owned_server(model, folder) as (port, process, command, properties):
        write(folder / "runtime.json", dict(server_pid=process.pid, command=command, properties=properties))
        for transport, pack in plan["packs"].items():
            for case, item in zip(pack["cases"], pack["prompts"]):
                if process.poll() is not None:
                    raise RuntimeError("Owned Q8 server exited")
                started = time.monotonic()
                response = baseline.request(port, "/completion",
                                            dict(plan["settings"]["decoding"], prompt=item["prompt"]))
                raw = response["content"]
                if not isinstance(raw, str) or response.get("truncated"):
                    raise ValueError("Invalid or truncated Q8 completion")
                answer = dict(case_id=case["id"], case_sha256=item["case_sha256"], raw_output=raw)
                answers[transport].append(answer)
                write(folder / "cases" / (case["id"] + ".json"),
                      dict(**answer, model_sha256=model_sha, raw_response=response,
                           runtime_seconds=time.monotonic() - started))
                completed += 1
                progress = dict(status="RUNNING", completed=completed, total=824,
                                last_case=case["id"], server_pid=process.pid)
                write(folder / "state.json", progress)
                record.update(completed=completed, total=824, last_case=case["id"], server_pid=process.pid)
                write(STATE, record)
    records = []
    for transport, pack in plan["packs"].items():
        cached = dict(binding=pack["binding"], model_sha256=model_sha, answers=answers[transport])
        write(folder / ("answers_" + transport + ".json"), cached)
        scored = score_cached(pack, cached)
        write(folder / ("report_" + transport + ".json"), scored)
        records.extend(scored["cases"])
    report = dict(summary(records), execution_status="COMPLETE", model_sha256=model_sha,
                  completed_at=now(), quality_status="PASS" if all(r["passed"] for r in records) else "FAILURES_FOUND",
                  cases=records, physical_device_inference="NOT_RUN")
    write(folder / "report.json", report)
    write(folder / "state.json", dict(status="COMPLETE", completed=824, total=824,
                                     quality_status=report["quality_status"]))
    print(f"Q8_0: {sum(r['passed'] for r in records)}/824 full passes", flush=True)


def verify_cached(pack, cached, model_sha):
    if cached["model_sha256"] != model_sha:
        raise ValueError("Cached answers belong to a different model")
    return score_cached(pack, cached)


def compare(plan):
    base = baseline_artifacts(plan)
    q8 = read(OUT / "release/gguf_manifest.json")
    if (q8["intermediate"] != base["intermediate"] or q8["adapter_sha256"] != base["adapter_sha256"]
            or q8["selected_step"] != base["selected_step"] or q8["tools"] != plan["tools"]
            or q8["prepared_inputs_sha256"] != digest(PREPARED)
            or q8["baseline_manifest_sha256"] != digest(RUN / "release/gguf_manifest.json")):
        raise ValueError("Q8 comparison uses a different checkpoint/build")
    folders = dict(f16=RUN / "evaluation_f16", q4=RUN / "evaluation_q4", q8_0=OUT / "evaluation_q8_0")
    artifacts = dict(f16=base["intermediate"], q4=base["model"], q8_0=q8["model"])
    result = dict(status="COMPLETE", completed_at=now(), cases_per_format=824,
                  adapter_sha256=base["adapter_sha256"], selected_step=base["selected_step"],
                  same_prompts_and_decoding=True, system_prompt_attestation=plan["system_prompt_attestation"],
                  prepared_inputs_sha256=digest(PREPARED), physical_device_inference="NOT_RUN", formats={})
    records = {}
    for name, folder in folders.items():
        inputs, report = read(folder / "inputs.json"), read(folder / "report.json")
        assert_same_inputs(inputs, plan)
        artifact = artifacts[name]
        if (report["execution_status"] != "COMPLETE" or report["total"] != 824
                or Path(inputs["model"]).resolve() != Path(artifact["file"]).resolve()
                or inputs["model_sha256"] != artifact["sha256"]
                or report["model_sha256"] != artifact["sha256"]
                or digest(inputs["model"]) != artifact["sha256"]):
            raise ValueError("Evaluation/model binding is incomplete: " + name)
        rows, transports = [], {}
        for transport, pack in plan["packs"].items():
            scored = verify_cached(pack, read(folder / ("answers_" + transport + ".json")), artifact["sha256"])
            if scored != read(folder / ("report_" + transport + ".json")):
                raise ValueError("Saved transport report differs from freshly scored bound answers")
            rows.extend(scored["cases"])
            transports[transport] = dict(scored["report"],
                                         full_passes=sum(r["passed"] for r in scored["cases"]))
        if report["cases"] != rows:
            raise ValueError("Aggregate report differs from its bound per-case outputs")
        records[name] = {row["case_id"]: row for row in rows}
        if len(records[name]) != 824:
            raise ValueError("Incomplete or duplicate comparison cases")
        result["formats"][name] = dict(artifact=artifact, transports=transports, full_passes=sum(r["passed"] for r in rows),
                                       quality_status="PASS" if all(r["passed"] for r in rows) else "FAILURES_FOUND")
    result["paired_changes"] = {}
    for first, second in (("f16", "q4"), ("f16", "q8_0"), ("q4", "q8_0")):
        if records[first].keys() != records[second].keys():
            raise ValueError("Different comparison case inventories")
        result["paired_changes"][first + "_to_" + second] = dict(
            lost_passes=[key for key in records[first] if records[first][key]["passed"] and not records[second][key]["passed"]],
            gained_passes=[key for key in records[first] if not records[first][key]["passed"] and records[second][key]["passed"]])
    profile.verify_preservation()
    write(OUT / "quantization_comparison.json", result)
    print("Identical full-system F16/Q4_K_M/Q8_0 comparison complete", flush=True)


def pid_alive(pid):
    if os.name != "nt":
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, pid)  # Query this tracked PID; no process inventory.
    if not handle:
        if ctypes.get_last_error() == 87:
            return False
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        code = wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
            raise ctypes.WinError(ctypes.get_last_error())
        return code.value == 259
    finally:
        kernel.CloseHandle(handle)


def run():
    if STATE.exists():
        raise ValueError("Q8 controller already exists; refusing to launch twice")
    frozen_sha = digest(FREEZE)
    plan = verify_plan(frozen_sha)
    record = dict(status="WAITING_FOR_BASELINE", started_at=now(), controller_pid=os.getpid(),
                  baseline_pipeline=str(PARENT), prepared_inputs_sha256=digest(PREPARED),
                  freeze_sha256=frozen_sha, system_prompt_attestation=plan["system_prompt_attestation"],
                  physical_device_inference="NOT_RUN", stage="waiting")
    write(STATE, record)
    try:
        while True:
            parent = read(PARENT)
            if case_digest(parent["frozen_inputs"]) != plan["baseline_frozen_inputs_sha256"]:
                raise ValueError("Original launch binding changed while waiting")
            if parent_ready(parent):
                break
            if not pid_alive(parent["controller_pid"]):
                # Re-read after the liveness check to allow a final atomic completion write.
                if parent_ready(read(PARENT)):
                    break
                raise RuntimeError("Original controller exited without completing its pipeline")
            record.update(last_checked_at=now(), baseline_status=parent["status"], baseline_stage=parent["stage"])
            write(STATE, record)
            time.sleep(30)
        for stage, action in (("q8_build", lambda: build_q8(plan)),
                              ("q8_evaluation", lambda: evaluate_q8(plan, record)),
                              ("three_format_comparison", lambda: compare(plan))):
            verify_plan(frozen_sha)
            record.update(status="RUNNING", stage=stage, stage_started_at=now())
            write(STATE, record)
            action()
        result = read(OUT / "quantization_comparison.json")
        record.update(status="COMPLETE", completed_at=now(), server_pid=None, completed=824, total=824,
                      quality_status=result["formats"]["q8_0"]["quality_status"],
                      model=read(OUT / "release/gguf_manifest.json")["model"],
                      preservation=profile.verify_preservation())
        write(STATE, record)
    except BaseException:
        record.update(status="FAILED", failed_at=now(), error=traceback.format_exc())
        write(STATE, record)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare", action="store_true")
    mode.add_argument("--run", action="store_true")
    args = parser.parse_args()
    prepare() if args.prepare else run()
