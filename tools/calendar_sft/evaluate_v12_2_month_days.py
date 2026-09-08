"""Compare frozen V12.2 inference with and without a current-month day count.

Uses only the existing holdouts. Does not train, synthesize questions, change
references, or write to the source datasets, model directories, or Android app.
"""

from __future__ import annotations

import argparse
import calendar
from copy import deepcopy
from datetime import date, datetime, timezone
import json
from pathlib import Path
from statistics import mean, median
from time import perf_counter

from dataset_contract import RUNTIME_SYSTEM_RE, file_sha256, load_jsonl
from evaluate_predictions import load_predictions
from evaluate_v12_2_adapter import DATA, score_rows
from generate_holdout_predictions import _generate_outputs, _load_model

ROOT = Path(__file__).resolve().parents[2]
LABEL = "\u0414\u043d\u0435\u0439 \u0432 \u0442\u0435\u043a\u0443\u0449\u0435\u043c \u043c\u0435\u0441\u044f\u0446\u0435"
ARMS = ("baseline", "month_days")
SUITES = ("holdout", "regression_holdout")
BATCH_SIZE = 4
MAX_NEW_TOKENS = 128


def with_month_days(row: dict) -> dict:
    messages = row["messages"]
    if messages[0]["role"] != "system" or messages[-1]["role"] != "assistant":
        raise ValueError("expected one leading system context and a final reference")
    if any(message["role"] == "system" for message in messages[1:]):
        raise ValueError("unexpected extra system context")
    matched = RUNTIME_SYSTEM_RE.fullmatch(messages[0]["content"])
    if matched is None:
        raise ValueError("unrecognized or already extended system context")
    today = date.fromisoformat(matched["date"])
    days = calendar.monthrange(today.year, today.month)[1]
    result = deepcopy(row)
    result["messages"][0]["content"] += f"\n{LABEL}: {days}"
    return result


def compare_reports(baseline: dict, extended: dict) -> dict:
    before = {case["case_id"]: case for case in baseline["cases"]}
    after = {case["case_id"]: case for case in extended["cases"]}
    if len(before) != len(baseline["cases"]) or len(after) != len(extended["cases"]) or before.keys() != after.keys():
        raise ValueError("report IDs must be unique and identical")
    improved, regressed, changed, details = [], [], [], []
    for case_id, old in before.items():
        new = after[case_id]
        if old["expected"] != new["expected"] or old["users"] != new["users"]:
            raise ValueError("questions and references must be identical")
        if not old["passed"] and new["passed"]:
            improved.append(case_id)
        if old["passed"] and not new["passed"]:
            regressed.append(case_id)
        if old["raw"] != new["raw"]:
            changed.append(case_id)
            details.append({
                "case_id": case_id, "users": old["users"], "expected": old["expected"],
                "baseline": old["actual"], "month_days": new["actual"],
                "baseline_passed": old["passed"], "month_days_passed": new["passed"],
                "baseline_system": old["system"], "month_days_system": new["system"],
                "baseline_contract_error": old["contract_error"],
                "month_days_contract_error": new["contract_error"],
            })
    return {
        "total": len(before), "baseline_metrics": baseline["metrics"],
        "month_days_metrics": extended["metrics"], "newly_passing": improved,
        "newly_failing": regressed, "changed_output_ids": changed, "changed_cases": details,
    }


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def append_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def fingerprint(paths: list[Path]) -> dict:
    return {str(path.resolve()): file_sha256(path) for path in paths}


def score_saved_predictions(rows: list[dict], path: Path) -> dict:
    predictions = [{"case_id": case_id, "output": output} for case_id, output in load_predictions(path).items()]
    return score_rows(rows, predictions)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--run-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    run, model_dir, output = args.run_dir.resolve(), args.model_dir.resolve(), args.output_dir.resolve()
    adapter = run / "adapter"
    if output == run or run in output.parents or model_dir == output or model_dir in output.parents or DATA.resolve() == output or DATA.resolve() in output.parents:
        raise ValueError("experiment output must be separate from source artifacts")
    suites = {name: load_jsonl(DATA / f"{name}.jsonl") for name in SUITES}
    variants = {name: {"baseline": rows, "month_days": [with_month_days(row) for row in rows]} for name, rows in suites.items()}
    historical = {name: json.loads((run / f"{name}_report.json").read_text(encoding="utf-8")) for name in SUITES}
    protected = [
        DATA / "train.jsonl", DATA / "validation.jsonl", DATA / "manifest.json",
        DATA / "provenance.json", run / "run_manifest.json",
        adapter / "adapter_model.safetensors", adapter / "adapter_config.json",
        Path(__file__), ROOT / "tools/calendar_sft/evaluate_v12_2_adapter.py",
        ROOT / "tools/calendar_sft/generate_holdout_predictions.py",
        ROOT / "tools/calendar_sft/dataset_contract.py",
        ROOT / "tools/calendar_sft/evaluate_predictions.py",
    ]
    for name in SUITES:
        protected.extend((DATA / f"{name}.jsonl", run / f"{name}_report.json", run / f"{name}_predictions.jsonl"))
    hashes_before = fingerprint(protected)
    for name in SUITES:
        old = historical[name]
        if Path(old["model_dir"]).resolve() != model_dir or Path(old["adapter_dir"]).resolve() != adapter:
            raise ValueError("model and adapter must match the recorded V12.2 evaluation")
        if old["holdout_sha256"] != file_sha256(DATA / f"{name}.jsonl"):
            raise ValueError("source holdout changed since the recorded evaluation")
        if old["environment"]["adapter_sha256"] != file_sha256(adapter / "adapter_model.safetensors"):
            raise ValueError("adapter changed since the recorded evaluation")
        previous_score = score_saved_predictions(suites[name], run / f"{name}_predictions.jsonl")
        if previous_score["metrics"] != old["metrics"]:
            raise ValueError("scoring no longer reproduces the recorded evaluation")
    output.mkdir(parents=True, exist_ok=False)
    manifest = {
        "version": "v12.2", "experiment": "current_month_days_ab", "status": "running",
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "model_dir": str(model_dir), "adapter_dir": str(adapter),
        "hint_template": f"\n{LABEL}: {{days}}", "suites": {name: len(rows) for name, rows in suites.items()},
        "decoding": {"do_sample": False, "batch_size": BATCH_SIZE, "max_new_tokens": MAX_NEW_TOKENS},
        "method": "Same frozen questions, references, model and batch composition; arm order alternates per batch. Two four-token warmups excluded from timings.",
        "timing_scope": "Synchronized wall time for tokenization, batched generation and decoding. One pass per arm; not single-request or Android latency.",
        "immutable_inputs_before": hashes_before,
    }
    write_json(output / "manifest.json", manifest)
    print("Loading the frozen V12.2 model for paired inference.", flush=True)
    model, tokenizer, environment = _load_model(model_dir, adapter)
    manifest["environment"] = environment
    for name in SUITES:
        if environment != historical[name]["environment"]:
            raise ValueError("inference environment differs from the recorded evaluation")
    import torch

    manifest["runtime"] = {"torch": torch.__version__, "cuda": torch.version.cuda}
    torch.manual_seed(20260825)
    for arm in ARMS:
        _generate_outputs(model, tokenizer, [row["messages"] for row in variants[SUITES[0]][arm][:BATCH_SIZE]], 4)
    torch.cuda.synchronize()
    write_json(output / "manifest.json", manifest)
    batch_number = 0
    timings = []
    comparisons = {}
    for name in SUITES:
        predictions = {arm: [] for arm in ARMS}
        for start in range(0, len(suites[name]), BATCH_SIZE):
            order = ARMS if batch_number % 2 == 0 else tuple(reversed(ARMS))
            for arm in order:
                batch = variants[name][arm][start:start + BATCH_SIZE]
                messages = [row["messages"] for row in batch]
                token_counts = [len(tokenizer.apply_chat_template(row["messages"][:-1], tokenize=True, add_generation_prompt=True)) for row in batch]
                torch.cuda.synchronize()
                began = perf_counter()
                outputs = _generate_outputs(model, tokenizer, messages, MAX_NEW_TOKENS)
                torch.cuda.synchronize()
                elapsed = perf_counter() - began
                if len(outputs) != len(batch):
                    raise ValueError("model output count does not match the batch")
                current = [{"case_id": row["case_id"], "output": answer} for row, answer in zip(batch, outputs)]
                predictions[arm].extend(current)
                append_jsonl(output / f"{name}_{arm}_predictions.jsonl", current)
                measurement = {
                    "suite": name, "arm": arm, "batch_number": batch_number, "order": list(order),
                    "case_ids": [row["case_id"] for row in batch], "seconds": elapsed,
                    "prompt_tokens": token_counts,
                    "decoded_output_tokens": [len(tokenizer.encode(answer, add_special_tokens=False)) for answer in outputs],
                }
                timings.append(measurement)
                append_jsonl(output / "timings.jsonl", [measurement])
                print(f"{name} {arm}: {len(predictions[arm])}/{len(suites[name])}; batch {elapsed:.2f}s", flush=True)
            batch_number += 1
        reports = {}
        for arm in ARMS:
            reports[arm] = score_rows(variants[name][arm], predictions[arm])
            reports[arm].update({"suite": name, "arm": arm, "environment": environment})
            write_json(output / f"{name}_{arm}_report.json", reports[arm])
        comparison = compare_reports(reports["baseline"], reports["month_days"])
        old_outputs = {case["case_id"]: case["raw"] for case in historical[name]["cases"]}
        comparison["baseline_changed_since_previous_run"] = [case["case_id"] for case in reports["baseline"]["cases"] if case["raw"] != old_outputs[case["case_id"]]]
        comparisons[name] = comparison
        write_json(output / f"{name}_comparison.json", comparison)
        print(json.dumps({"suite": name, "baseline": reports["baseline"]["metrics"], "month_days": reports["month_days"]["metrics"], "newly_passing": comparison["newly_passing"], "newly_failing": comparison["newly_failing"]}), flush=True)
    timing_summary = {}
    for arm in ARMS:
        records = [record for record in timings if record["arm"] == arm]
        total_seconds = sum(record["seconds"] for record in records)
        cases = sum(len(record["case_ids"]) for record in records)
        timing_summary[arm] = {
            "total_seconds": total_seconds, "batches": len(records), "cases": cases,
            "median_batch_seconds": median(record["seconds"] for record in records),
            "batch_amortized_seconds_per_case": total_seconds / cases,
            "mean_prompt_tokens": mean(count for record in records for count in record["prompt_tokens"]),
            "total_decoded_output_tokens": sum(sum(record["decoded_output_tokens"]) for record in records),
        }
    hashes_after = fingerprint(protected)
    manifest.update({
        "status": "complete" if hashes_before == hashes_after else "integrity_failed",
        "completed_at_utc": datetime.now(timezone.utc).isoformat(),
        "immutable_inputs_after": hashes_after, "immutable_inputs_unchanged": hashes_before == hashes_after,
        "timing_summary": timing_summary,
    })
    write_json(output / "comparison.json", {"suites": comparisons, "timing_summary": timing_summary})
    manifest["output_sha256"] = fingerprint(sorted(path for path in output.iterdir() if path.is_file() and path.name != "manifest.json"))
    write_json(output / "manifest.json", manifest)
    if hashes_before != hashes_after:
        raise ValueError("source artifacts changed during the experiment")
    print(json.dumps({"status": "complete", "output_dir": str(output), "timing_summary": timing_summary}), flush=True)


if __name__ == "__main__":
    main()
