"""Durable isolated train/build/evaluate pipeline; never overwrite a previous run."""
import os
import subprocess
import sys
import traceback

from run_context import HERE, ROOT, RUN, CONFIG, read, write, digest, now, verify_preservation
from v12_66_launch_integrity import verify_launch_inputs
from gpu_power import PowerGuard


def main():
    state = RUN / "pipeline.json"
    if state.exists() or (RUN / "training.json").exists():
        raise ValueError("Run already exists; explicit checkpoint recovery is required")
    smoke = read(RUN / f"smoke-batch{CONFIG['default_micro_batch']}.json")
    if smoke["status"] != "COMPLETE" or smoke["weights_saved"]:
        raise ValueError("Fresh successful GPU smoke is required")
    if smoke["training_config_sha256"] != digest(HERE / "config.json"):
        raise ValueError("GPU smoke belongs to a different configuration")
    power_guard = PowerGuard(CONFIG["gpu_power"], RUN / "controller_gpu_power_samples.jsonl")
    power_guard.check(force=True)
    preservation = verify_preservation()
    launch = verify_launch_inputs()
    stages = (
        ("training", "train.py", ["--mode", "train"]),
        ("gguf_build", "build_gguf.py", ["--model", CONFIG["model"]]),
        ("f16_evaluation", "release_evaluation.py", ["--format", "f16"]),
        ("q4_evaluation", "release_evaluation.py", ["--format", "q4"]),
        ("quantization_comparison", "release_evaluation.py", ["--compare"]),
        ("final_report", "final_report.py", []),
    )
    record = dict(status="RUNNING", started_at=now(), controller_pid=os.getpid(), workspace=str(ROOT),
                  python=sys.executable, training_inputs=launch, preservation=preservation,
                  gpu_power_constraint=CONFIG["gpu_power"], stage=None, steps=[], physical_device_inference="NOT_RUN")
    write(state, record)
    try:
        for stage, script, arguments in stages:
            power_guard.check(force=True)
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
        print("Training, GGUF build and independent evaluations complete", flush=True)
    except BaseException:
        record.update(status="FAILED", failed_at=now(), error=traceback.format_exc())
        write(state, record)
        raise


if __name__ == "__main__":
    main()
