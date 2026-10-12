"""Prepare four-action evaluation prompts or grade hash-bound cached answers, without inference."""
from copy import deepcopy
from hashlib import sha256
import argparse
import json
from pathlib import Path

from v12_67_contract import load_jsonl
from v12_67_evaluation import VERSION, case_from_row, grade, summary
from v12_67_training import (CONFIG, CONFIG_PATH, DATA, PROVIDER, ROOT, WORK, digest,
                            prompt, read, system_text, verify_launch_inputs, write)


def case_digest(case):
    return sha256(json.dumps(case, ensure_ascii=False, sort_keys=True,
                             separators=(",", ":")).encode("utf-8")).hexdigest()


def prepared_inputs(suites=None, variant="android_runtime"):
    verify_launch_inputs()
    manifest = read(DATA / "manifest.json")
    available = set(manifest["artifacts"]) - {"train"}
    if suites is None:
        suites = sorted(available - {"validation"})
    if not suites or len(set(suites)) != len(suites) or not set(suites) <= available:
        raise ValueError("Choose retained validation or independent evaluation suites")
    index = read(DATA / "audit_index.json")
    cases = []
    for name in suites:
        for line, original in enumerate(load_jsonl(DATA / (name + ".jsonl")), 1):
            row = deepcopy(original)
            row["audit"] = index.get(row.get("case_id"), {})
            case = case_from_row(row, f"{variant}_{name}_{line:04d}", name)
            case.update(source_system=case["system"], system=system_text(case["system"], variant),
                        prompt_format="android_chatml" if variant == "android_runtime" else "standard_chatml")
            cases.append(case)
    binding = dict(version=CONFIG["version"], grader_version=VERSION,
                   manifest_sha256=digest(DATA / "manifest.json"),
                   training_config_sha256=digest(CONFIG_PATH), provider_sha256=digest(PROVIDER),
                   evaluator_sha256=digest(Path(__file__)),
                   grader_sha256=digest(ROOT / "tools/calendar_sft/v12_67_evaluation.py"),
                   clock_sha256=digest(ROOT / "tools/calendar_sft/v12_67_clock.py"),
                   contract_sha256=digest(ROOT / "tools/calendar_sft/v12_67_contract.py"),
                   suites={name: manifest["artifacts"][name]["sha256"] for name in suites},
                   transport=variant, decoding=dict(do_sample=False, max_new_tokens=CONFIG["max_new_tokens"]),
                   holdout_used_for_selection=False,
                   cases_sha256=case_digest(cases), rows=len(cases))
    return dict(binding=binding, cases=cases,
                prompts=[dict(case_id=case["id"], case_sha256=case_digest(case),
                              prompt=prompt([dict(role="system", content=case["system"]),
                                             dict(role="user", content=case["user"])],
                                            android=case["prompt_format"] == "android_chatml")) for case in cases])


def score_cached(pack, cached):
    if not isinstance(cached, dict) or set(cached) != {"binding", "model_sha256", "answers"}:
        raise ValueError("Expected bound answers and model hash")
    if cached["binding"] != pack["binding"]:
        raise ValueError("Cached answers use different inputs, transport or grader")
    model_hash = cached["model_sha256"]
    if not isinstance(model_hash, str) or len(model_hash) != 64 or any(c not in "0123456789abcdef" for c in model_hash):
        raise ValueError("Expected a verified SHA-256 model identity")
    answers = cached["answers"]
    if not isinstance(answers, list) or len(answers) != len(pack["cases"]):
        raise ValueError("Cached answers are incomplete")
    results = []
    for case, answer in zip(pack["cases"], answers):
        if (not isinstance(answer, dict) or set(answer) != {"case_id", "case_sha256", "raw_output"}
                or answer["case_id"] != case["id"] or answer["case_sha256"] != case_digest(case)
                or not isinstance(answer["raw_output"], str)):
            raise ValueError("Cached case changed or was reordered")
        results.append(grade(case, answer["raw_output"]))
    return dict(binding=pack["binding"], model_sha256=model_hash, report=summary(results), cases=results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", action="append")
    parser.add_argument("--transport", choices=CONFIG["training_transports"], default="android_runtime")
    parser.add_argument("--cached", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    pack = prepared_inputs(args.suite, args.transport)
    result = score_cached(pack, read(args.cached)) if args.cached else pack
    output = args.output or WORK / ("evaluation_report.json" if args.cached else "evaluation_inputs.json")
    if output.exists():
        raise ValueError("Refusing to overwrite a previous evaluation artifact")
    write(output, result)
    print(json.dumps(dict(status="SCORED" if args.cached else "PREPARED", rows=len(pack["cases"]),
                          output=str(output), inference_run=False), ensure_ascii=False))


if __name__ == "__main__":
    main()
