"""Identical held-out F16/Q4_K_M evaluations on an owned local llama.cpp server."""
import argparse
from contextlib import contextmanager
from pathlib import Path
import socket
import subprocess
import time

from run_context import ROOT, RUN, CONFIG, read, write, digest, now, verify_preservation
from v12_66_evaluation import VERSION, grade, summary
from evaluate_v12_66 import prepared_inputs
from evaluate_v12_63_gguf import request

BINARY = ROOT / "build/llama-b10621-bin-win-cpu-x64/llama-server.exe"


@contextmanager
def owned_server(model, folder, prefix="server"):
    model = Path(model).resolve()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    command = [str(BINARY), "--offline", "-m", str(model), "-c", "512", "-np", "1",
               "-t", "12", "-b", "512", "-ub", "512", "-ngl", "0", "--host", "127.0.0.1",
               "--port", str(port), "--no-webui", "--no-context-shift", "--cache-reuse", "0"]
    with (folder / (prefix + ".stdout.log")).open("xb") as out, (folder / (prefix + ".stderr.log")).open("xb") as err:
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            for _ in range(240):
                if process.poll() is not None:
                    raise RuntimeError("Owned GGUF server exited; inspect its stderr log")
                try:
                    if request(port, "/health").get("status") == "ok":
                        break
                except Exception:
                    pass
                time.sleep(0.5)
            else:
                raise TimeoutError("GGUF server startup timed out")
            properties = request(port, "/props")
            if Path(properties["model_path"]).resolve() != model:
                raise ValueError("Server loaded a different model")
            yield port, process, command, properties
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=15)


def load_smoke(model, folder):
    with owned_server(model, folder, prefix="load") as (_, process, command, properties):
        evidence = {"status": "COMPLETE", "verified_at": now(), "pid": process.pid,
                    "command": command, "model_sha256": digest(model), "server_sha256": digest(BINARY),
                    "properties": properties, "generation_quality_test": False}
        write(folder / "load_smoke.json", evidence)
        return evidence


def evaluate(format_name):
    verify_preservation()
    manifest = read(RUN / "release/gguf_manifest.json")
    artifact = manifest["intermediate" if format_name == "f16" else "model"]
    model = Path(artifact["file"]).resolve()
    model_sha = digest(model)
    if manifest["status"] != "COMPLETE" or artifact["sha256"] != model_sha:
        raise ValueError("GGUF does not match the completed build")
    folder = RUN / ("evaluation_" + format_name)
    folder.mkdir(exist_ok=False)
    (folder / "cases").mkdir()
    packs = {name: prepared_inputs(split=name) for name in (
        "intent_holdout", "reply_consistency_holdout", "offset_reply_holdout")}
    cases = [case for pack in packs.values() for case in pack["cases"]]
    if len(cases) != 200 or len({case["id"] for case in cases}) != 200:
        raise ValueError("Wrong independent evaluation inventory")
    decoding = dict(n_predict=CONFIG["max_new_tokens"], temperature=0, top_k=1, top_p=1, min_p=0,
                    seed=CONFIG["seed"], repeat_penalty=1, cache_prompt=False, stream=False)
    write(folder / "inputs.json", dict(grader_version=VERSION, model=str(model), model_sha256=model_sha,
          packs=packs, decoding=decoding, server_sha256=digest(BINARY), grammar_constraint=False,
          physical_device_inference="NOT_RUN"))
    records = []
    with owned_server(model, folder) as (port, process, _, _):
        for index, case in enumerate(cases, 1):
            if process.poll() is not None:
                raise RuntimeError("Owned GGUF server exited during evaluation")
            started = time.monotonic()
            response = request(port, "/completion", dict(decoding, prompt=case["prompt"]))
            raw = response["content"]
            if not isinstance(raw, str):
                raise ValueError("Completion is not text")
            write(folder / "cases" / (case["id"] + ".json"), dict(id=case["id"], output=raw.strip(),
                  model_sha256=model_sha, prompt_sha256=case["prompt_sha256"], raw_response=response,
                  runtime_seconds=time.monotonic() - started))
            records.append(grade(case, raw.strip()))
            write(folder / "state.json", dict(status="RUNNING", completed=index, total=200,
                  last_case=case["id"], server_pid=process.pid))
    verify_preservation()
    report = dict(summary(records), execution_status="COMPLETE", model_sha256=model_sha,
                  completed_at=now(), quality_status="PASS" if all(r["passed"] for r in records) else "FAILURES_FOUND",
                  cases=records, physical_device_inference="NOT_RUN")
    write(folder / "report.json", report)
    write(folder / "state.json", dict(status="COMPLETE", completed=200, total=200,
          quality_status=report["quality_status"]))
    print(f"{format_name}: {sum(r['passed'] for r in records)}/200 full passes", flush=True)


def compare():
    folders = {name: RUN / ("evaluation_" + name) for name in ("f16", "q4")}
    inputs = {name: read(folder / "inputs.json") for name, folder in folders.items()}
    if inputs["f16"]["packs"] != inputs["q4"]["packs"] or inputs["f16"]["decoding"] != inputs["q4"]["decoding"]:
        raise ValueError("Evaluation prompts or decoding differ")
    reports = {name: read(folder / "report.json") for name, folder in folders.items()}
    records = {name: {r["case_id"]: r for r in report["cases"]} for name, report in reports.items()}
    if records["f16"].keys() != records["q4"].keys() or len(records["f16"]) != 200:
        raise ValueError("Evaluation inventories differ")
    result = {"status": "COMPLETE", "completed_at": now(), "cases_per_format": 200,
              "f16_model_sha256": reports["f16"]["model_sha256"], "q4_model_sha256": reports["q4"]["model_sha256"],
              "same_prompts_and_decoding": True, "physical_device_inference": "NOT_RUN"}
    for name, report in reports.items():
        result[name] = {key: value for key, value in report.items() if key not in ("cases", "failed_ids")}
    result["new_failures_after_quantization"] = [key for key, record in records["f16"].items()
        if record["passed"] and not records["q4"][key]["passed"]]
    result["new_passes_after_quantization"] = [key for key, record in records["f16"].items()
        if not record["passed"] and records["q4"][key]["passed"]]
    write(RUN / "quantization_comparison.json", result)
    print("Identical F16/Q4_K_M comparison complete", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("f16", "q4"))
    parser.add_argument("--compare", action="store_true")
    arguments = parser.parse_args()
    if arguments.compare:
        compare()
    elif arguments.format:
        evaluate(arguments.format)
    else:
        parser.error("--format or --compare is required")
