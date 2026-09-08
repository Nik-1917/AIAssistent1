"""Run existing V12.2 holdouts with a saved adapter and score both separately.

Only model predictions and reports are written. No training examples or weights
are created or modified. Inference uses the existing local holdout runner.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path

from dataset_contract import file_sha256, load_jsonl, parse_and_validate_assistant_response
from evaluate_predictions import params_semantically_equal
from generate_holdout_predictions import _generate_outputs, _load_model

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "docs/calendar_sft_v12_2"


def score_rows(rows: list[dict], predictions: list[dict]) -> dict:
    outputs = {prediction["case_id"]: prediction["output"] for prediction in predictions}
    if len(outputs) != len(predictions) or set(outputs) != {row["case_id"] for row in rows}:
        raise ValueError("prediction IDs do not match the immutable holdout")
    counts = Counter()
    by_category = {}
    cases = []
    for row in rows:
        expected = json.loads(row["messages"][-1]["content"])
        raw = outputs[row["case_id"]]
        try:
            actual = json.loads(raw)
        except ValueError:
            actual = None
        json_valid = isinstance(actual, dict)
        if not json_valid:
            actual = {}
        counts["json_object_valid"] += json_valid
        violation = None
        try:
            parse_and_validate_assistant_response(raw, contract_version=row.get("contract_version"))
            counts["contract_valid"] += 1
        except ValueError as error:
            violation = str(error)
        intent_match = actual.get("intent") == expected["intent"]
        params_match = actual.get("params") == expected["params"]
        params_case_insensitive = isinstance(actual.get("params"), dict) and params_semantically_equal(actual["params"], expected["params"])
        passed = violation is None and intent_match and params_match
        counts["intent_match"] += intent_match
        counts["intent_params_exact"] += intent_match and params_match
        counts["strict_pass"] += passed
        counts["case_insensitive_pass"] += violation is None and intent_match and params_case_insensitive
        if expected["intent"] == "calendar_add":
            counts["calendar_add_total"] += 1
            actual_params = actual.get("params")
            temporal_keys = {key for key in ("starts_at", "date", "time") if key in expected["params"]}
            temporal_match = isinstance(actual_params, dict) and intent_match and all(
                actual_params.get(key) == expected["params"][key] for key in temporal_keys
            ) and {key for key in ("starts_at", "date", "time") if key in actual_params} == temporal_keys
            counts["calendar_add_temporal_match"] += temporal_match
            counts["calendar_add_reply_match"] += intent_match and actual.get("reply") == expected["reply"]
        category = by_category.setdefault(row["category"], {"total": 0, "passed": 0})
        category["total"] += 1
        category["passed"] += passed
        cases.append({
            "case_id": row["case_id"], "category": row["category"], "passed": passed,
            "system": row["messages"][0]["content"],
            "users": [message["content"] for message in row["messages"][1:-1]],
            "expected": expected, "actual": actual, "raw": raw, "contract_error": violation,
        })
    return {"total": len(rows), "metrics": dict(counts), "categories": by_category, "cases": cases}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    run = args.run_dir.resolve()
    adapter = run / "adapter"
    if not (run / "run_manifest.json").is_file() or not (adapter / "adapter_model.safetensors").is_file():
        raise ValueError("training must finish before evaluation")
    if any((run / f"{name}_predictions.jsonl").exists() for name in ("holdout", "regression_holdout")):
        raise ValueError("predictions already exist; do not silently replace a recorded evaluation")
    model, tokenizer, environment = _load_model(args.model_dir, adapter)
    for name in ("holdout", "regression_holdout"):
        path = DATA / f"{name}.jsonl"
        rows = load_jsonl(path)
        predictions = []
        for start in range(0, len(rows), 4):
            batch = rows[start:start + 4]
            outputs = _generate_outputs(model, tokenizer, [row["messages"] for row in batch], 128)
            predictions.extend({"case_id": row["case_id"], "output": output} for row, output in zip(batch, outputs))
            print(f"{name}: {len(predictions)}/{len(rows)}", flush=True)
        prediction_path = run / f"{name}_predictions.jsonl"
        prediction_path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in predictions), encoding="utf-8")
        report = score_rows(rows, predictions)
        report.update({
            "version": "v12.2", "suite": name, "created_at_utc": datetime.now(timezone.utc).isoformat(),
            "holdout_sha256": file_sha256(path), "predictions_sha256": file_sha256(prediction_path),
            "model_dir": str(args.model_dir.resolve()), "adapter_dir": str(adapter), "environment": environment,
            "decoding": {"do_sample": False, "batch_size": 4, "max_new_tokens": 128},
            "grading": "V12.1 response contract, exact intent/params; optional text-case-only score, no semantic alternatives.",
        })
        (run / f"{name}_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"suite": name, "total": report["total"], "metrics": report["metrics"]}), flush=True)


if __name__ == "__main__":
    main()
