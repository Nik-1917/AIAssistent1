"""Frozen independent Q4 generation probes; does not train or postprocess replies."""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

from audit_v12_62_reply_speech import scan
from dataset_contract import file_sha256, parse_and_validate_assistant_response
from evaluate_predictions import params_semantically_equal
from prepare_v12_61 import norm, user_key

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_v12_62_eval"
DATA = ROOT / "docs/calendar_sft_v12_62"
BASE = ROOT / "build/calendar_sft_models/Qwen3-4B-Instruct-2507/cdbee75f17c01a7cc42f958dc650907174af0554"
BINARY = ROOT / "build/llama-b10621-bin-win-cpu-x64/llama-completion.exe"
SYSTEM = "Сегодня дата и время:2026-09-29 (вторник) 10:00 Europe/Samara ответ JSON"
ANDROID_SYSTEM = "cегодня 2026-09-29 10:00 день недели вторник ответ JSON"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, value):
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)


def cases():
    rows = []
    for line in (SOURCE / "clock_cases.txt").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, clock, title, user, phrase = line.split("|")
        rows.append({"id": key, "suite": "all_minutes" if key.startswith("M") else "other_hours", "system": SYSTEM,
                     "user": user, "expected": {"intent": "calendar_add", "params": {"title": title, "starts_at": "2026-09-30T" + clock}},
                     "clocks": [clock], "clock_phrases": [phrase], "prompt_format": "locked_tokenizer"})
    for item in read(SOURCE / "additional_cases.json"):
        rows.append({"id": item["id"], "suite": "duration_interval_speech", "system": SYSTEM, "user": item["user"],
                     "expected": {"intent": "calendar_add", "params": item["params"]}, "clocks": item["clocks"],
                     "clock_phrases": item["clock_phrases"], "prompt_format": "locked_tokenizer"})
    for key in ("M15", "M30", "M31", "M59"):
        item = deepcopy(next(x for x in rows if x["id"] == key))
        item.update(id="A_" + key, suite="android_prompt", system=ANDROID_SYSTEM, prompt_format="android_chatml")
        rows.append(item)
    seen = set()
    for line in (DATA / "regression_holdout.jsonl").read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        expected = json.loads(row["messages"][-1]["content"])
        if expected["intent"] == "calendar_add" or expected["intent"] in seen:
            continue
        seen.add(expected["intent"])
        rows.append({"id": "R_" + row["case_id"], "suite": "unchanged_intents", "system": row["messages"][0]["content"],
                     "user": row["messages"][1]["content"], "expected": expected,
                     "clocks": [], "clock_phrases": [], "prompt_format": "locked_tokenizer"})
    if len({r["id"] for r in rows}) != len(rows):
        raise ValueError("duplicate evaluation ID")
    if {r["clocks"][0][-2:] for r in rows if r["suite"] == "all_minutes"} != {f"{m:02d}" for m in range(60)}:
        raise ValueError("minute probe coverage is incomplete")
    train_keys = {user_key(json.loads(line)) for line in (DATA / "train.jsonl").read_text(encoding="utf-8").splitlines()}
    for row in rows:
        if norm(row["user"]).strip(" .!?") in train_keys:
            raise ValueError(f"training wording leaked into evaluation: {row['id']}")
    return rows


def grade(row, output):
    try:
        actual = json.loads(output)
    except ValueError:
        actual = None
    valid_json = isinstance(actual, dict)
    actual = actual if valid_json else {}
    try:
        parse_and_validate_assistant_response(output, contract_version="v12.57")
        error = None
    except ValueError as exc:
        error = str(exc)
    expected = row["expected"]
    intent = actual.get("intent") == expected["intent"]
    ap = actual.get("params")
    exact = intent and ap == expected["params"]
    folded = intent and isinstance(ap, dict) and params_semantically_equal(ap, expected["params"])
    temporal_keys = ("starts_at", "ends_at", "date", "time", "duration_min")
    temporal = intent and isinstance(ap, dict) and {k: ap[k] for k in temporal_keys if k in ap} == {k: expected["params"][k] for k in temporal_keys if k in expected["params"]}
    reply = actual.get("reply") if isinstance(actual.get("reply"), str) else ""
    clock_reply = re.sub(re.escape(expected["params"].get("title", "__NO_TITLE__")), "EVENT", reply, count=1, flags=re.I)
    found = scan(clock_reply)
    desired = [(int(c[:2]) % 12, int(c[-2:])) for c in row["clocks"]]
    actual_clocks = [(c["hour_mod_12"], c["minute"]) for c in found]
    phrases = all(re.search(r"(?<!\w)" + re.escape(norm(p)) + r"(?!\w)", norm(clock_reply)) for p in row["clock_phrases"])
    clock_ok = bool(desired) and actual_clocks == desired and all(c["canonical"] for c in found) and phrases
    if not desired:
        clock_ok = None
    passed = valid_json and error is None and folded and (clock_ok is not False)
    return {"case_id": row["id"], "suite": row["suite"], "passed": passed, "json_valid": valid_json,
            "contract_valid": error is None, "contract_error": error, "intent_match": intent, "params_exact": exact,
            "params_case_insensitive": folded, "temporal_params_match": temporal, "reply_clock_correct": clock_ok,
            "reply_clock_phrases_present": phrases, "detected_clocks": found,
            "expected": expected, "actual": actual, "raw": output, "user": row["user"], "system": row["system"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--only", default="")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    rows = cases()
    if args.only:
        wanted = set(args.only.split(","))
        rows = [r for r in rows if r["id"] in wanted]
        if {r["id"] for r in rows} != wanted:
            raise ValueError("unknown selected probe")
    model = args.model.resolve()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    for child in ("cases", "prompts", "logs"):
        (out / child).mkdir(exist_ok=True)
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", TOKENIZERS_PARALLELISM="false")
    from transformers import AutoTokenizer
    tokenizer = AutoTokenizer.from_pretrained(BASE, local_files_only=True)
    template_hash = hashlib.sha256(tokenizer.chat_template.encode("utf-8")).hexdigest()
    if template_hash != "64f85b198065d0fba2a81f37e10ed68161ce2c19a754c7100e67e0ca2ee9c326":
        raise ValueError("locked tokenizer changed")
    for row in rows:
        messages = [{"role": "system", "content": row["system"]}, {"role": "user", "content": row["user"]}]
        if row["prompt_format"] == "android_chatml":
            prompt = "".join("<|im_start|>" + m["role"] + "\n" + m["content"] + "\n<|im_end|>\n" for m in messages) + "<|im_start|>assistant\n"
        else:
            prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        path = out / "prompts" / (row["id"] + ".txt")
        if path.exists() and path.read_text(encoding="utf-8") != prompt:
            raise ValueError("a frozen prompt changed")
        path.write_text(prompt, encoding="utf-8")
        row["prompt_sha256"] = file_sha256(path)
    if args.prepare_only:
        write(out / "prepared_cases.json", rows)
        print(json.dumps({"prepared": len(rows), "suites": dict(Counter(r["suite"] for r in rows))}))
        return
    if not model.is_file():
        raise ValueError("completed model is missing")
    model_sha = file_sha256(model)
    build = read(model.parent / "gguf_manifest.json")
    if build["status"] != "COMPLETE" or build["model"]["sha256"] != model_sha:
        raise ValueError("model differs from completed build")
    inputs = [SOURCE / "clock_cases.txt", SOURCE / "additional_cases.json", DATA / "regression_holdout.jsonl",
              Path(__file__), ROOT / "tools/calendar_sft/audit_v12_62_reply_speech.py", BINARY]
    frozen = {"model": str(model), "model_sha256": model_sha, "inputs": {str(p): file_sha256(p) for p in inputs},
              "cases": rows, "chat_template_sha256": template_hash,
              "decoding": {"temperature": 0, "top_k": 1, "top_p": 1, "min_p": 0, "seed": 20260825,
                           "repeat_penalty": 1, "max_new_tokens": 192, "cpu_threads": 12, "gpu_layers": 0,
                           "grammar_constraint": False, "context": 512}}
    if (out / "inputs.json").exists() and read(out / "inputs.json") != frozen:
        raise ValueError("evaluation inputs differ from the frozen run")
    write(out / "inputs.json", frozen)
    for index, row in enumerate(rows, 1):
        record_path = out / "cases" / (row["id"] + ".json")
        if record_path.exists():
            record = read(record_path)
            if record["prompt_sha256"] != row["prompt_sha256"] or record["model_sha256"] != model_sha:
                raise ValueError("cached prediction belongs to different inputs")
            continue
        command = [str(BINARY), "--offline", "-m", str(model), "-f", str(out / "prompts" / (row["id"] + ".txt")),
                   "-c", "512", "-n", "192", "-t", "12", "-b", "512", "-ub", "512", "-ngl", "0",
                   "--temp", "0", "--top-k", "1", "--top-p", "1", "--min-p", "0", "--seed", "20260825",
                   "--repeat-penalty", "1", "--no-conversation", "--no-display-prompt", "--no-escape", "--simple-io",
                   "--color", "off", "--log-colors", "off"]
        start = time.monotonic()
        process = subprocess.run(command, cwd=ROOT, capture_output=True, stdin=subprocess.DEVNULL, timeout=600,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        stdout = process.stdout.decode("utf-8", errors="strict")
        (out / "logs" / (row["id"] + ".stdout.txt")).write_text(stdout, encoding="utf-8")
        (out / "logs" / (row["id"] + ".stderr.txt")).write_bytes(process.stderr)
        if process.returncode:
            raise RuntimeError(f"llama-completion failed: {row['id']}, exit {process.returncode}")
        output = stdout.strip()
        if output.endswith("[end of text]"):
            output = output[:-len("[end of text]")].rstrip()
        record = {"id": row["id"], "model_sha256": model_sha, "prompt_sha256": row["prompt_sha256"],
                  "output": output, "runtime_seconds": time.monotonic() - start, "command": command}
        write(record_path, record)
        write(out / "state.json", {"status": "RUNNING", "completed": index, "total": len(rows), "last_case": row["id"]})
    results = [grade(row, read(out / "cases" / (row["id"] + ".json"))["output"]) for row in rows]
    suites = defaultdict(Counter)
    metrics = ("passed", "json_valid", "contract_valid", "intent_match", "params_exact", "params_case_insensitive", "temporal_params_match", "reply_clock_correct")
    for result in results:
        counts = suites[result["suite"]]
        counts["total"] += 1
        for metric in metrics:
            counts[metric] += result[metric] is True
        counts["reply_clock_eligible"] += result["reply_clock_correct"] is not None
    report = {"execution_status": "COMPLETE", "quality_status": "PASS" if all(r["passed"] for r in results) else "FAILURES_FOUND",
              "completed_at_utc": datetime.now(timezone.utc).isoformat(), "model": str(model), "model_sha256": model_sha,
              "total": len(results), "suites": {k: dict(v) for k, v in suites.items()}, "cases": results,
              "limitations": "Desktop CPU GGUF generation, deterministic decoding; not physical Android inference. Human speech forms remain ambiguous without params.",
              "grading": "Separate exact/casefold params, temporal values, literal canonical phrase and ordered clock interpretation; no inference output is repaired."}
    write(out / "report.json", report)
    write(out / "state.json", {"status": "COMPLETE", "quality_status": report["quality_status"], "completed": len(results), "total": len(rows)})
    print(json.dumps({"execution_status": "COMPLETE", "quality_status": report["quality_status"], "total": len(results), "suites": report["suites"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
