"""Prepare held-out prompts or grade saved raw responses; never start inference.

Old release reports remain immutable. Every cached response is bound to its
prompt hash and all responses in one report must share a model hash.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re

from prepare_v12_66 import OUTPUT, digest, read
from v12_66_evaluation import case_from_row, grade, summary, VERSION


def save_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise ValueError(f"refusing to overwrite an evaluation artifact: {path}")
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def prepared_inputs(dataset=OUTPUT, split="intent_holdout"):
    source = dataset / (split + ".jsonl")
    index = read(dataset / "audit_index.json")
    cases = []
    for line in source.read_bytes().splitlines():
        row = json.loads(line)
        row["audit"] = index.get(row.get("case_id"), {})
        base = case_from_row(row, suite=split)
        for android in (False, True):
            case = deepcopy(base)
            case["prompt_format"] = "android_chatml" if android else "standard_chatml"
            if android:
                matched = re.fullmatch(r"Сегодня дата и время:(\S+) \((.+)\) (\S+) Europe/Samara ответ JSON", case["system"])
                if not matched:
                    raise ValueError("unknown temporal context")
                case.update(id="ANDROID_" + case["id"], suite=split + "_android",
                            system=f"cегодня {matched[1]} {matched[3]} день недели {matched[2]} ответ JSON")
            separator = "\n" if android else ""
            prompt = ("<|im_start|>system\n" + case["system"] + separator + "<|im_end|>\n" +
                      "<|im_start|>user\n" + case["user"] + separator + "<|im_end|>\n<|im_start|>assistant\n")
            case.update(prompt=prompt, prompt_sha256=hashlib.sha256(prompt.encode("utf-8")).hexdigest())
            cases.append(case)
    return dict(grader_version=VERSION, dataset_sha256=digest(source), audit_index_sha256=digest(dataset / "audit_index.json"),
                cases=cases, inference="NOT_RUN", purpose="Future independent evaluation; not training examples")


def score_cached(inputs_path, responses, previous_report=None):
    inputs = read(inputs_path)
    cases = inputs["cases"]
    if len({c["id"] for c in cases}) != len(cases):
        raise ValueError("duplicate input ID")
    records, model_hashes = [], set()
    for case in cases:
        response_path = responses / (case["id"] + ".json")
        saved = read(response_path)
        if saved["id"] != case["id"] or saved["prompt_sha256"] != case["prompt_sha256"]:
            raise ValueError(f"cached prompt binding differs: {case['id']}")
        model_hashes.add(saved["model_sha256"])
        result = grade(case, saved["output"])
        result["response_sha256"] = digest(response_path)
        records.append(result)
    if len(model_hashes) != 1:
        raise ValueError("responses do not belong to one model")
    report = {**summary(records), "cases": records, "inputs_sha256": digest(inputs_path),
              "model_sha256": next(iter(model_hashes)), "new_inference": "NOT_RUN",
              "physical_device_inference": "NOT_RUN", "source": "cached raw model responses"}
    if previous_report:
        previous = read(previous_report)
        if previous["model_sha256"] != report["model_sha256"]:
            raise ValueError("previous report model differs")
        prior = {r["case_id"]: r for r in previous["cases"]}
        if set(prior) != {r["case_id"] for r in records}:
            raise ValueError("previous report case IDs differ")
        if any(r["raw"] != prior[r["case_id"]]["raw"] for r in records):
            raise ValueError("previous report raw output differs")
        report["regrading"] = dict(previous_report_sha256=digest(previous_report),
            previously_passed=sum(r["passed"] for r in prior.values()),
            now_passed=sum(r["passed"] for r in records),
            newly_rejected=[r["case_id"] for r in records if prior[r["case_id"]]["passed"] and not r["passed"]],
            newly_accepted=[r["case_id"] for r in records if not prior[r["case_id"]]["passed"] and r["passed"]])
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--dataset", type=Path, default=OUTPUT)
    parser.add_argument("--split", default="intent_holdout")
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--responses", type=Path)
    parser.add_argument("--previous-report", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.prepare_only:
        report = prepared_inputs(args.dataset, args.split)
        save_new(args.output, report)
        print(json.dumps({"prepared_cases": len(report["cases"]), "inference": "NOT_RUN"}))
    else:
        if not args.inputs or not args.responses:
            parser.error("--inputs and --responses are required when scoring")
        report = score_cached(args.inputs, args.responses, args.previous_report)
        save_new(args.output, report)
        print(json.dumps({k: v for k, v in report.items() if k not in ("cases", "failed_ids", "groups")}, ensure_ascii=False))
