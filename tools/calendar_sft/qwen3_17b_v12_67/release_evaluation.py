"""Evaluate identical independent V12.67 prompts with F16 and Q4_K_M."""
import argparse
from contextlib import contextmanager
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import json

from run_context import (ROOT, RUN, CONFIG, read, write, digest, now,
                         verify_preservation, verify_source, verify_launch_inputs)
from evaluate_v12_67 import prepared_inputs, case_digest, score_cached
from build_v12_2_gguf import verify_tools

BINARY = ROOT / "build/llama-b10621-bin-win-cpu-x64/llama-server.exe"
CONTEXT = 1024


def request(port, path, payload=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    message = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data,
                                     headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(message, timeout=600) as response:
        return json.load(response)


@contextmanager
def owned_server(model, folder, prefix="server"):
    model = Path(model).resolve()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    command = [str(BINARY), "--offline", "-m", str(model), "-c", str(CONTEXT), "-np", "1",
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
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)


def load_smoke(model, folder):
    with owned_server(model, folder, prefix="load") as (_, process, command, properties):
        evidence = dict(status="COMPLETE", verified_at=now(), pid=process.pid, command=command,
                        model_sha256=digest(model), server_sha256=digest(BINARY),
                        properties=properties, generation_quality_test=False)
        write(folder / "load_smoke.json", evidence)
        return evidence


def independent_inputs():
    packs = {transport: prepared_inputs(variant=transport) for transport in CONFIG["training_transports"]}
    cases = [case for pack in packs.values() for case in pack["cases"]]
    if (len(cases) != 824 or len({case["id"] for case in cases}) != 824
            or any(case["suite"] in ("train", "validation", "development") for case in cases)
            or any(len(pack["binding"]["suites"]) != 13 for pack in packs.values())):
        raise ValueError("Wrong independent evaluation inventory")
    return packs


def evaluate(format_name):
    verify_launch_inputs()
    verify_preservation()
    tools = verify_tools()
    manifest = read(RUN / "release/gguf_manifest.json")
    artifact = manifest["intermediate" if format_name == "f16" else "model"]
    model = Path(artifact["file"]).resolve()
    model_sha = digest(model)
    if manifest["status"] != "COMPLETE" or artifact["sha256"] != model_sha or manifest["tools"] != tools:
        raise ValueError("GGUF or tools do not match the completed build")
    packs = independent_inputs()
    from transformers import AutoTokenizer
    _, source = verify_source(CONFIG["model"])
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True, trust_remote_code=False)
    for pack in packs.values():
        for item in pack["prompts"]:
            tokens = len(tokenizer.encode(item["prompt"], add_special_tokens=False))
            if tokens + CONFIG["max_new_tokens"] + 1 > CONTEXT:
                raise ValueError("Evaluation would exceed the full context")
    folder = RUN / ("evaluation_" + format_name)
    folder.mkdir(exist_ok=False)
    (folder / "cases").mkdir()
    decoding = dict(n_predict=CONFIG["max_new_tokens"], temperature=0, top_k=1, top_p=1, min_p=0,
                    seed=CONFIG["seed"], repeat_penalty=1, cache_prompt=False, stream=False)
    write(folder / "inputs.json", dict(model=str(model), model_sha256=model_sha, packs=packs,
          decoding=decoding, server_sha256=digest(BINARY), context=CONTEXT,
          grammar_constraint=False, holdout_used_for_selection=False, physical_device_inference="NOT_RUN"))
    answers = {transport: [] for transport in packs}
    count = 0
    with owned_server(model, folder) as (port, process, _, _):
        for transport, pack in packs.items():
            for case, item in zip(pack["cases"], pack["prompts"]):
                if process.poll() is not None:
                    raise RuntimeError("Owned GGUF server exited during evaluation")
                if case["id"] != item["case_id"] or case_digest(case) != item["case_sha256"]:
                    raise ValueError("Evaluation prompt/case binding changed")
                started = time.monotonic()
                response = request(port, "/completion", dict(decoding, prompt=item["prompt"]))
                raw = response["content"]
                if not isinstance(raw, str) or response.get("truncated"):
                    raise ValueError("Invalid or truncated completion")
                answer = dict(case_id=case["id"], case_sha256=item["case_sha256"], raw_output=raw)
                answers[transport].append(answer)
                write(folder / "cases" / (case["id"] + ".json"),
                      dict(**answer, model_sha256=model_sha, raw_response=response,
                           runtime_seconds=time.monotonic() - started))
                count += 1
                write(folder / "state.json", dict(status="RUNNING", completed=count, total=824,
                      last_case=case["id"], server_pid=process.pid))
    from v12_67_evaluation import summary
    records = []
    for transport, pack in packs.items():
        cached = dict(binding=pack["binding"], model_sha256=model_sha, answers=answers[transport])
        write(folder / ("answers_" + transport + ".json"), cached)
        scored = score_cached(pack, cached)
        write(folder / ("report_" + transport + ".json"), scored)
        records.extend(scored["cases"])
    verify_preservation()
    report = dict(summary(records), execution_status="COMPLETE", model_sha256=model_sha,
                  completed_at=now(), quality_status="PASS" if all(r["passed"] for r in records) else "FAILURES_FOUND",
                  cases=records, physical_device_inference="NOT_RUN")
    write(folder / "report.json", report)
    write(folder / "state.json", dict(status="COMPLETE", completed=824, total=824,
          quality_status=report["quality_status"]))
    print(f"{format_name}: {sum(r['passed'] for r in records)}/824 full passes", flush=True)


def compare():
    verify_launch_inputs()
    verify_preservation()
    folders = {name: RUN / ("evaluation_" + name) for name in ("f16", "q4")}
    inputs = {name: read(folder / "inputs.json") for name, folder in folders.items()}
    for key in ("packs", "decoding", "context", "server_sha256", "grammar_constraint"):
        if inputs["f16"][key] != inputs["q4"][key]:
            raise ValueError("Evaluation prompts, decoding or runtime differ")
    reports = {name: read(folder / "report.json") for name, folder in folders.items()}
    for name, report in reports.items():
        if (report["execution_status"] != "COMPLETE" or report["total"] != 824
                or report["model_sha256"] != inputs[name]["model_sha256"]
                or digest(inputs[name]["model"]) != report["model_sha256"]):
            raise ValueError("Evaluation or model binding is incomplete")
    records = {name: {r["case_id"]: r for r in report["cases"]} for name, report in reports.items()}
    if records["f16"].keys() != records["q4"].keys() or len(records["f16"]) != 824:
        raise ValueError("Evaluation inventories differ")
    result = dict(status="COMPLETE", completed_at=now(), cases_per_format=824,
                  f16_model_sha256=reports["f16"]["model_sha256"], q4_model_sha256=reports["q4"]["model_sha256"],
                  same_prompts_and_decoding=True, physical_device_inference="NOT_RUN")
    for name, report in reports.items():
        result[name] = {key: value for key, value in report.items() if key not in ("cases", "failed_ids")}
    result["new_failures_after_quantization"] = [key for key, record in records["f16"].items()
        if record["passed"] and not records["q4"][key]["passed"]]
    result["new_passes_after_quantization"] = [key for key, record in records["f16"].items()
        if not record["passed"] and records["q4"][key]["passed"]]
    write(RUN / "quantization_comparison.json", result)
    print("Identical V12.67 F16/Q4_K_M comparison complete", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument("--format", choices=("f16", "q4"))
    choice.add_argument("--compare", action="store_true")
    arguments = parser.parse_args()
    compare() if arguments.compare else evaluate(arguments.format)
