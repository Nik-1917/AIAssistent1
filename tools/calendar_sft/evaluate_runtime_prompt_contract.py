"""Opt-in desktop A/B smoke; preserves raw outputs for the Android parser's JVM audit.

Does not execute calendar commands or modify phone settings. Timings are desktop-only.
"""
import argparse
import hashlib
import json
import statistics
import subprocess
import textwrap
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def request(port, route, payload=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{port}{route}", data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=600) as reply:
        return json.load(reply)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--model", required=True, type=Path)
    cli.add_argument("--output", required=True, type=Path)
    cli.add_argument("--port", type=int, default=8787)
    args = cli.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    provider = ROOT / "app/src/main/java/com/example/aiassistent1/domain/provider/SystemPromptProvider.kt"
    source = provider.read_text(encoding="utf-8")
    contract = textwrap.dedent(source.split('CALENDAR_CONTRACT = """', 1)[1].split('""".trimIndent()', 1)[0]).strip()
    clock = "cегодня 2026-10-07 10:00 день недели среда ответ JSON"
    systems = {"old": clock, "contract": clock + "\n" + contract}
    cases = json.loads((ROOT / "app/src/test/resources/calendar_prompt_contract_cases.json").read_text(encoding="utf-8"))
    model = args.model.resolve()
    with model.open("rb") as stream:
        model_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    binary = ROOT / "build/llama-b10621-bin-win-cpu-x64/llama-server.exe"
    # Identical deterministic sampling for both variants; not a replay of saved phone sampling.
    decoding = dict(n_predict=384, temperature=0, top_k=1, top_p=1, min_p=0,
                    repeat_penalty=1, seed=42, cache_prompt=False, stream=False)
    write(out / "inputs.json", dict(model=str(model), model_sha256=model_hash,
          provider_sha256=hashlib.sha256(source.encode()).hexdigest(), systems=systems,
          cases=cases, decoding=decoding, context=2048, threads=12, gpu_layers=0,
          template="LlamatikEngine.buildPrompt ChatML", limitation="Desktop smoke, 12 newly written cases, no phone inference or history replay."))
    command = [str(binary), "--offline", "-m", str(model), "-c", "2048", "-np", "1", "-t", "12",
               "-b", "512", "-ub", "512", "-ngl", "0", "--host", "127.0.0.1", "--port", str(args.port),
               "--no-webui", "--no-context-shift", "--cache-reuse", "0"]
    results = []
    with (out / "server.log").open("wb") as log:
        server = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                  creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            for _ in range(240):
                if server.poll() is not None:
                    raise RuntimeError("Server exited; see server.log")
                try:
                    if request(args.port, "/health").get("status") == "ok":
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(0.5)
            else:
                raise TimeoutError("Server startup")
            # Warmup excluded from timing; no constraints or output postprocessing added.
            request(args.port, "/completion", dict(decoding, prompt="Привет", n_predict=1))
            for index, case in enumerate(cases):
                for variant in (("old", "contract") if index % 2 == 0 else ("contract", "old")):
                    prompt = (f"<|im_start|>system\n{systems[variant]}\n<|im_end|>\n"
                              f"<|im_start|>user\n{case['user']}\n<|im_end|>\n<|im_start|>assistant\n")
                    started = time.monotonic()
                    response = request(args.port, "/completion", dict(decoding, prompt=prompt))
                    elapsed = time.monotonic() - started
                    row = dict(id=case["id"], variant=variant, output=response["content"],
                               runtime_seconds=elapsed, raw_response=response, prompt=prompt)
                    results.append(row)
                    write(out / "results.json", results)
                    print(f"{len(results)}/{len(cases)*2} {case['id']} {variant} {elapsed:.2f}s", flush=True)
        finally:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=15)
    write(out / "timing-summary.json", {variant: {
        "cases": sum(r["variant"] == variant for r in results),
        "median_seconds": statistics.median(r["runtime_seconds"] for r in results if r["variant"] == variant),
        "prompt_tokens": [r["raw_response"]["timings"]["prompt_n"] for r in results if r["variant"] == variant],
    } for variant in systems})


if __name__ == "__main__":
    main()
