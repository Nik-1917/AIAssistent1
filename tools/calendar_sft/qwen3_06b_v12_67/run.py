"""Durable isolated V12.67 train/build/evaluate pipeline."""
import os
import subprocess
import sys
import traceback

from run_context import (HERE, ROOT, RUN, WORK, CONFIG, CONFIG_PATH, SOURCE_LOCK,
                         read, write, digest, now, verify_preservation, verify_launch_inputs, frozen_paths)
from build_v12_2_gguf import verify_tools


def main():
    state = RUN / "pipeline.json"
    if state.exists() or (RUN / "training.json").exists():
        raise ValueError("Run already exists; explicit checkpoint recovery is required")
    audit = read(WORK / "token_audit.json")
    if (audit["status"] != "PASS" or audit["conversations"] != 6616
            or audit["configuration_sha256"] != digest(CONFIG_PATH)
            or audit["source_lock_sha256"] != digest(SOURCE_LOCK) or audit["truncated_records"]):
        raise ValueError("Fresh complete 0.6B token audit is required")
    smoke = read(RUN / f"smoke-batch{CONFIG['default_micro_batch']}.json")
    frozen = {str(path): digest(path) for path in frozen_paths()}
    if (smoke["status"] != "COMPLETE" or smoke["weights_saved"]
            or smoke["frozen_inputs"] != frozen or smoke["micro_batch"] != CONFIG["default_micro_batch"]):
        raise ValueError("Fresh matching GPU smoke is required")
    preservation = verify_preservation()
    launch = verify_launch_inputs()
    tools = verify_tools()
    stages = (
        ("training", "train.py", ["--mode", "train"]),
        ("gguf_build", "build_gguf.py", ["--model", CONFIG["model"]]),
        ("f16_evaluation", "release_evaluation.py", ["--format", "f16"]),
        ("q4_evaluation", "release_evaluation.py", ["--format", "q4"]),
        ("quantization_comparison", "release_evaluation.py", ["--compare"]),
    )
    record = dict(status="RUNNING", started_at=now(), controller_pid=os.getpid(), workspace=str(ROOT),
                  python=sys.executable, training_inputs=launch, preservation=preservation, tools=tools,
                  frozen_inputs=frozen, stage=None, steps=[], physical_device_inference="NOT_RUN")
    write(state, record)
    try:
        for stage, script, arguments in stages:
            for path, expected in frozen.items():
                if digest(path) != expected:
                    raise ValueError("Frozen input changed: " + path)
            path = HERE / script
            command = [sys.executable, "-X", "utf8", "-B", str(path), *arguments]
            step = dict(stage=stage, script_sha256=digest(path), command=command, started_at=now())
            with (RUN / (stage + ".stdout.log")).open("xb") as out, (RUN / (stage + ".stderr.log")).open("xb") as err:
                child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                record.update(stage=stage, child_pid=child.pid)
                write(state, record)
                print(stage + ": started", flush=True)
                step["exit_code"] = child.wait()
            step["completed_at"] = now()
            record["steps"].append(step)
            write(state, record)
            if step["exit_code"] != 0:
                raise RuntimeError(stage + " failed; logs and checkpoints preserved")
        comparison = read(RUN / "quantization_comparison.json")
        record.update(status="COMPLETE", completed_at=now(), child_pid=None,
                      quality_status=comparison["q4"]["quality_status"], preservation=verify_preservation(),
                      model=read(RUN / "release/gguf_manifest.json")["model"])
        write(state, record)
        print("V12.67 0.6B training, GGUF build and independent evaluations complete", flush=True)
    except BaseException:
        record.update(status="FAILED", failed_at=now(), error=traceback.format_exc())
        write(state, record)
        raise


if __name__ == "__main__":
    main()
