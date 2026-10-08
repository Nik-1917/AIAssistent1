"""Resume only stopped held-out evaluation; retain trained weights and cached answers."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import time
import traceback

from run_context import HERE, RUN, CONFIG, read, write, digest, now, verify_preservation
from release_evaluation import BINARY, owned_server, prepared_inputs, request, grade, summary, VERSION, compare


def bound_cached(case, saved, model_sha):
    if (saved.get("id") != case["id"] or saved.get("prompt_sha256") != case["prompt_sha256"]
            or saved.get("model_sha256") != model_sha):
        raise ValueError("Cached answer does not match prompt/model: " + case["id"])
    raw = saved.get("output")
    response = saved.get("raw_response")
    if (not isinstance(raw, str) or not isinstance(response, dict)
            or not isinstance(response.get("content"), str) or response["content"].strip() != raw):
        raise ValueError("Cached raw response differs: " + case["id"])
    return grade(case, raw)


def prepared(format_name):
    manifest = read(RUN / "release/gguf_manifest.json")
    artifact = manifest["intermediate" if format_name == "f16" else "model"]
    model = Path(artifact["file"]).resolve()
    model_sha = digest(model)
    if manifest["status"] != "COMPLETE" or model_sha != artifact["sha256"] or model.stat().st_size != artifact["bytes"]:
        raise ValueError("Built GGUF changed")
    packs = {name: prepared_inputs(split=name) for name in (
        "intent_holdout", "reply_consistency_holdout", "offset_reply_holdout")}
    cases = [case for pack in packs.values() for case in pack["cases"]]
    if len(cases) != 200 or len({case["id"] for case in cases}) != 200:
        raise ValueError("Wrong independent inventory")
    decoding = dict(n_predict=CONFIG["max_new_tokens"], temperature=0, top_k=1, top_p=1, min_p=0,
                    seed=CONFIG["seed"], repeat_penalty=1, cache_prompt=False, stream=False)
    expected = dict(grader_version=VERSION, model=str(model), model_sha256=model_sha, packs=packs,
                    decoding=decoding, server_sha256=digest(BINARY), grammar_constraint=False,
                    physical_device_inference="NOT_RUN")
    folder = RUN / ("evaluation_" + format_name)
    if (folder / "inputs.json").exists() and read(folder / "inputs.json") != expected:
        raise ValueError("Saved prompts, decoding, binary or GGUF changed")
    cached_files = list((folder / "cases").glob("*.json"))
    ids = {case["id"] for case in cases}
    if any(path.stem not in ids for path in cached_files):
        raise ValueError("Unexpected cached case")
    cached = {}
    for case in cases:
        path = folder / "cases" / (case["id"] + ".json")
        if path.exists():
            cached[case["id"]] = bound_cached(case, read(path), model_sha)
    return folder, model, model_sha, cases, expected, cached


def verify_training_result():
    training = read(RUN / "training.json")
    if training["status"] != "COMPLETE" or training["completed_optimizer_steps"] != 1128:
        raise ValueError("Training incomplete")
    if digest(RUN / "adapter/adapter_model.safetensors") != training["adapter_sha256"]:
        raise ValueError("Selected adapter changed")
    for path, expected in training["frozen_inputs"].items():
        if digest(path) != expected:
            raise ValueError("Frozen training input changed: " + path)
    verify_preservation()


def evaluate_remaining(format_name, recovery):
    folder, model, model_sha, cases, inputs, cached = prepared(format_name)
    folder.mkdir(exist_ok=True)
    (folder / "cases").mkdir(exist_ok=True)
    if not (folder / "inputs.json").exists():
        write(folder / "inputs.json", inputs)
    original_hashes = {path.name: digest(path) for path in (folder / "cases").glob("*.json")}
    write(recovery / (format_name + "_cached_answers.json"), original_hashes)
    prefix_index = 1
    while (folder / f"server_resume_{prefix_index:04}.stdout.log").exists():
        prefix_index += 1
    prefix = f"server_resume_{prefix_index:04}"
    records = []
    with owned_server(model, folder, prefix=prefix) as (port, process, _, _):
        for index, case in enumerate(cases, 1):
            if case["id"] in cached:
                records.append(cached[case["id"]])
                continue
            if process.poll() is not None:
                raise RuntimeError("Owned evaluation server exited")
            started = time.monotonic()
            response = request(port, "/completion", dict(inputs["decoding"], prompt=case["prompt"]))
            raw = response["content"]
            if not isinstance(raw, str):
                raise ValueError("Completion is not text")
            answer = dict(id=case["id"], output=raw.strip(), model_sha256=model_sha,
                          prompt_sha256=case["prompt_sha256"], raw_response=response,
                          runtime_seconds=time.monotonic() - started)
            path = folder / "cases" / (case["id"] + ".json")
            if path.exists():
                raise ValueError("Refusing to overwrite a cached answer")
            write(path, answer)
            records.append(bound_cached(case, answer, model_sha))
            write(folder / "state.json", dict(status="RUNNING", completed=len(records), total=200,
                  last_case=case["id"], server_pid=process.pid, reused_cached_answers=len(cached)))
    for name, expected in original_hashes.items():
        if digest(folder / "cases" / name) != expected:
            raise ValueError("Original cached answer changed")
    verify_preservation()
    if len(records) != 200:
        raise ValueError("Evaluation incomplete")
    report = dict(summary(records), execution_status="COMPLETE", model_sha256=model_sha,
                  completed_at=now(), quality_status="PASS" if all(r["passed"] for r in records) else "FAILURES_FOUND",
                  cases=records, physical_device_inference="NOT_RUN")
    write(folder / "report.json", report)
    write(folder / "state.json", dict(status="COMPLETE", completed=200, total=200,
          quality_status=report["quality_status"], reused_cached_answers=len(cached)))
    print(f"{format_name}: {sum(r['passed'] for r in records)}/200; reused {len(cached)} unchanged answers", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    verify_training_result()
    inventory = {kind: len(prepared(kind)[-1]) for kind in ("f16", "q4")}
    print(json.dumps({"validated_cached_answers": inventory, "training_repeated": False}), flush=True)
    if args.validate_only:
        return
    import psutil
    original = read(RUN / "pipeline.json")
    for pid in (original.get("controller_pid"), original.get("child_pid")):
        if pid and pid != os.getpid() and psutil.pid_exists(pid):
            process = psutil.Process(pid)
            if any(Path(a).name in ("run.py", "resume_evaluation.py", "release_evaluation.py") for a in process.cmdline()):
                raise ValueError("An owned evaluator is still active")
    recovery = RUN / ("recovery_" + now().replace(":", "").replace(".", "_"))
    recovery.mkdir(exist_ok=False)
    for path in (RUN / "pipeline.json", RUN / "evaluation_f16/state.json", RUN / "evaluation_q4/state.json"):
        if path.exists():
            shutil.copy2(path, recovery / (path.parent.name + "_" + path.name))
    record = dict(original, status="RUNNING", controller_pid=os.getpid(), child_pid=None,
                  resumed_at=now(), recovery_directory=str(recovery), training_repeated=False,
                  interrupted_processes_detected=True, interruption_reason="UNKNOWN")
    write(RUN / "pipeline.json", record)
    try:
        for format_name in ("f16", "q4"):
            record.update(stage=format_name + "_evaluation")
            write(RUN / "pipeline.json", record)
            evaluate_remaining(format_name, recovery)
            record["steps"].append(dict(stage=format_name + "_evaluation", exit_code=0, completed_at=now(),
                  recovery_script_sha256=digest(Path(__file__)), original_evaluator_sha256=digest(HERE / "release_evaluation.py")))
        record.update(stage="quantization_comparison")
        write(RUN / "pipeline.json", record)
        compare()
        comparison = read(RUN / "quantization_comparison.json")
        record["steps"].append(dict(stage="quantization_comparison", exit_code=0, completed_at=now()))
        record.update(status="COMPLETE", completed_at=now(), child_pid=None,
                      quality_status=comparison["q4"]["quality_status"], preservation=verify_preservation(),
                      model=read(RUN / "release/gguf_manifest.json")["model"])
        write(RUN / "pipeline.json", record)
        print("Pipeline complete; trained weights and original cached answers preserved", flush=True)
    except BaseException:
        record.update(status="FAILED", failed_at=now(), error=traceback.format_exc())
        write(RUN / "pipeline.json", record)
        raise


if __name__ == "__main__":
    main()
