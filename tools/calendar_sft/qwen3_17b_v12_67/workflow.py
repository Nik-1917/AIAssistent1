"""Run the isolated Qwen3-1.7B training, Q4 build, full evaluation and Q8 comparison."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback

import run_context as profile
from run_context import HERE, ROOT, RUN, WORK, CONFIG, read, write, digest, now

Q8_SCRIPT = ROOT / "tools/calendar_sft/qwen3_17b_v12_67_q8/comparison.py"
STATE = WORK / "workflow.json"
PARENT = RUN / "pipeline.json"
ATTEMPT = 1
STAGES = [
    ("training", HERE / "train.py", ["--mode", "train", "--micro-batch", "2"]),
    ("gguf_build", HERE / "build_gguf.py", ["--model", CONFIG["model"]]),
    ("f16_evaluation", HERE / "release_evaluation.py", ["--format", "f16"]),
    ("q4_evaluation", HERE / "release_evaluation.py", ["--format", "q4"]),
    ("quantization_comparison", HERE / "release_evaluation.py", ["--compare"]),
]


def invoke(name, script, arguments):
    print(name + ": started", flush=True)
    log_dir = WORK / "stage_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    stdout_path = log_dir / (name + f".attempt{ATTEMPT}.stdout.log")
    stderr_path = log_dir / (name + f".attempt{ATTEMPT}.stderr.log")
    command = [sys.executable, "-X", "utf8", "-B", str(script), *arguments]
    tick = time.monotonic()
    started = now()
    with stdout_path.open("xb") as stdout, stderr_path.open("xb") as stderr:
        result = subprocess.run(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=stdout,
                                stderr=stderr, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    record = dict(stage=name, command=command, started_at=started, completed_at=now(),
                  exit_code=result.returncode, stdout=str(stdout_path), stderr=str(stderr_path))
    print(name + (": complete" if result.returncode == 0 else ": failed") +
          " (" + str(round((time.monotonic() - tick) / 60, 1)) + " min)", flush=True)
    return record


def update_workflow(record):
    write(STATE, record)


def recoverable_verification_failure(parent, workflow):
    error = parent.get("error", "")
    return (parent.get("status") == "FAILED" and workflow.get("status") == "FAILED"
            and parent.get("stage") == "verification"
            and "FileNotFoundError" in error and "token_audit.json" in error
            and not parent.get("steps"))


def verify_cached_token_audit():
    audit_path = WORK / "token_audit.json"
    audit = read(audit_path)
    inputs = audit.get("inputs", {})
    expected = {
        "source_revision": CONFIG["source_revision"],
        "source_lock_sha256": digest(profile.SOURCE_LOCK),
        "configuration_sha256": digest(profile.CONFIG_PATH),
        "runtime_provider_sha256": digest(profile.PROVIDER),
    }
    if (audit.get("status") != "PASS" or audit.get("conversations") != 6616
            or audit.get("supervised_variants_checked") != 13232
            or audit.get("truncated_records") != 0 or audit.get("model_weights_loaded") is not False
            or audit.get("max_tokens", CONFIG["max_length"] + 1) > CONFIG["max_length"]
            or audit.get("max_response_tokens", CONFIG["max_new_tokens"] + 1) > CONFIG["max_new_tokens"]
            or inputs.get("status") != "PASS"
            or inputs.get("dataset_manifest_sha256") != digest(profile.DATA / "manifest.json")
            or inputs.get("runtime_provider_sha256") != expected["runtime_provider_sha256"]
            or any(audit.get(key) != value for key, value in expected.items())):
        raise ValueError("Cached token audit is stale or incomplete")
    return audit


def prepare_attempt():
    has_state = STATE.exists() or PARENT.exists()
    if not has_state:
        if ((RUN / "training.json").exists() or (RUN / "build").exists()
                or (RUN / "release").exists()):
            raise ValueError("Refusing to start over existing training or GGUF artifacts")
        return 1, False
    if not (STATE.exists() and PARENT.exists()):
        raise ValueError("Only one orchestration state file exists; refusing to overwrite it")

    previous_workflow, previous_parent = read(STATE), read(PARENT)
    if not recoverable_verification_failure(previous_parent, previous_workflow):
        raise ValueError("This run already has an owner or a non-recoverable stage; refusing to overwrite")
    if ((RUN / "training.json").exists() or (RUN / "build").exists()
            or (RUN / "release").exists() or (RUN / "smoke-batch2.json").exists()):
        raise ValueError("Refusing recovery because training, smoke, or GGUF artifacts already exist")
    verify_cached_token_audit()

    history = WORK / "attempt_history"
    history.mkdir(parents=True, exist_ok=True)
    existing = list(history.glob("attempt_*.workflow.json"))
    number = max((int(path.name.split("_")[1].split(".")[0]) for path in existing), default=0) + 1
    write(history / f"attempt_{number}.workflow.json", previous_workflow)
    write(history / f"attempt_{number}.pipeline.json", previous_parent)
    return number + 1, True


def run():
    global ATTEMPT
    ATTEMPT, recovering = prepare_attempt()

    WORK.mkdir(parents=True, exist_ok=True)
    RUN.mkdir(parents=True, exist_ok=True)
    parent = dict(status="PREPARING", attempt=ATTEMPT, started_at=now(), controller_pid=os.getpid(),
                  model=CONFIG["model"], source_revision=CONFIG["source_revision"],
                  training_config_sha256=digest(HERE / "config.json"), stage="verification", steps=[],
                  physical_device_inference="NOT_RUN")
    workflow = dict(status="PREPARING", attempt=ATTEMPT, recovering_previous_verification=recovering,
                    started_at=parent["started_at"], model=CONFIG["model"],
                    auxiliary_stages=[], baseline_stages=[], quantization="Q4_K_M",
                    q8_comparison="WAITING_FOR_Q4", manual_temporal_chat_review="PENDING",
                    physical_device_inference="NOT_RUN")
    write(PARENT, parent)
    update_workflow(workflow)
    try:
        profile.verify_source(CONFIG["model"])
        launch = profile.verify_launch_inputs()
        preservation = profile.verify_preservation()
        if recovering:
            audit_path = WORK / "token_audit.json"
            verify_cached_token_audit()
            token_audit = dict(stage="token_audit", started_at=parent["started_at"], completed_at=now(),
                               exit_code=0, stdout=str(WORK / "stage_logs/token_audit.stdout.log"),
                               stderr=str(WORK / "stage_logs/token_audit.stderr.log"), reused_report=True,
                               report_sha256=digest(audit_path))
        else:
            token_audit = invoke("token_audit", HERE / "audit_tokens.py", [])
            if token_audit["exit_code"] != 0:
                raise RuntimeError("Token audit failed; inspect its preserved logs")
        audit = read(WORK / "token_audit.json")
        if (audit.get("status") != "PASS" or audit.get("conversations") != 6616
                or audit.get("truncated_records") != 0):
            raise ValueError("The exact reviewed dataset did not pass the model token audit")

        preflight = invoke("preflight_microbatch_2", HERE / "train.py",
                           ["--mode", "preflight", "--micro-batch", "2"])
        if preflight["exit_code"] != 0:
            raise RuntimeError("Trainer preflight failed; inspect its preserved logs")
        smoke = invoke("gpu_smoke_microbatch_2", HERE / "train.py",
                       ["--mode", "smoke", "--micro-batch", "2"])
        if smoke["exit_code"] != 0:
            raise RuntimeError("GPU smoke failed; preserve evidence and review before changing batch settings")
        smoke_record = read(RUN / "smoke-batch2.json")
        if smoke_record.get("status") != "COMPLETE" or smoke_record.get("weights_saved"):
            raise ValueError("Matching GPU smoke did not complete safely")

        auxiliaries = [token_audit, preflight, smoke]
        frozen_inputs = {str(path): digest(path) for path in profile.frozen_paths()}
        parent.update(status="RUNNING", launch_inputs=launch, preservation=preservation,
                      token_audit_sha256=digest(WORK / "token_audit.json"),
                      auxiliary_stages=auxiliaries, frozen_inputs=frozen_inputs, stage=None)
        write(PARENT, parent)
        workflow.update(status="RUNNING", auxiliary_stages=auxiliaries)
        update_workflow(workflow)

        for stage, script, arguments in STAGES:
            for frozen_path, expected in parent["frozen_inputs"].items():
                if digest(frozen_path) != expected:
                    raise ValueError("Frozen input changed: " + frozen_path)
            parent.update(stage=stage, stage_started_at=now())
            write(PARENT, parent)
            step = invoke(stage, script, arguments)
            parent["steps"].append(step)
            write(PARENT, parent)
            workflow["baseline_stages"] = parent["steps"]
            workflow["stage"] = stage
            update_workflow(workflow)
            if step["exit_code"] != 0:
                raise RuntimeError(stage + " failed; its logs and partial artifacts are preserved")

        comparison = read(RUN / "quantization_comparison.json")
        manifest = read(RUN / "release/gguf_manifest.json")
        if (comparison.get("status") != "COMPLETE" or manifest.get("status") != "COMPLETE"
                or comparison.get("cases_per_format") != 824):
            raise ValueError("The F16/Q4 release comparison is incomplete")
        parent.update(status="COMPLETE", completed_at=now(), stage=None)
        write(PARENT, parent)
        workflow.update(stage="q8_comparison", q8_comparison="RUNNING")
        update_workflow(workflow)

        for name, arguments in (("q8_prepare", ["--prepare"]), ("q8_build_evaluate_compare", ["--run"])):
            step = invoke(name, Q8_SCRIPT, arguments)
            workflow["baseline_stages"].append(step)
            update_workflow(workflow)
            if step["exit_code"] != 0:
                raise RuntimeError(name + " failed; its logs and partial artifacts are preserved")
        q8_comparison_path = WORK.parent / "calendar_v12_67_qwen3_17b_q8_comparison/quantization_comparison.json"
        q8_comparison = read(q8_comparison_path)
        q8_manifest = read(q8_comparison_path.parent / "release/gguf_manifest.json")
        if (q8_comparison.get("status") != "COMPLETE" or q8_manifest.get("status") != "COMPLETE"
                or q8_comparison.get("cases_per_format") != 824):
            raise ValueError("The three-format independent comparison is incomplete")
        workflow.update(status="COMPLETE", completed_at=now(), stage=None,
                        q8_comparison="COMPLETE", quality_status=q8_comparison["quality_status"],
                        baseline_manifest=str(RUN / "release/gguf_manifest.json"),
                        q8_manifest=str(q8_comparison_path.parent / "release/gguf_manifest.json"),
                        comparison=str(q8_comparison_path),
                        preservation=profile.verify_preservation())
        update_workflow(workflow)
        print("V12.67 Qwen3-1.7B training, Q4, Q8 and full-system desktop evaluation complete",
              flush=True)
    except BaseException:
        parent["status"] = "FAILED"
        parent["failed_at"] = now()
        parent["error"] = traceback.format_exc()
        write(PARENT, parent)
        workflow.update(status="FAILED", failed_at=now(), error=traceback.format_exc())
        update_workflow(workflow)
        raise


if __name__ == "__main__":
    run()
