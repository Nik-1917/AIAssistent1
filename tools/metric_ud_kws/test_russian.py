"""Evaluate a consented local Russian manifest. No recording download, synthesis or upload."""
import argparse
import json
import pathlib
from verify_reference import load_reference, extract, sha256


def validate_cases(cases):
    if not cases:
        raise ValueError("No Russian evaluation cases supplied")
    enrollments = {}
    for case in cases:
        for name in ("enrollment", "candidate", "phrase_id", "owner_id", "speaker_id", "rights_evidence"):
            if not isinstance(case.get(name), str) or not case[name].strip():
                raise ValueError(f"Missing {name}")
        if case.get("language") != "ru" or case.get("source") not in ("human", "synthetic"):
            raise ValueError("Explicit Russian/source labels required")
        if type(case.get("same_keyword")) is not bool:
            raise ValueError("same_keyword must be a boolean")
        if pathlib.Path(case["enrollment"]).resolve() == pathlib.Path(case["candidate"]).resolve():
            raise ValueError("Candidate must be a separate recording, not enrollment itself")
        key = (case["phrase_id"], case["owner_id"])
        path = pathlib.Path(case["enrollment"]).resolve()
        if key in enrollments and enrollments[key] != path:
            raise ValueError("One-shot requires one fixed enrollment per phrase and owner")
        enrollments[key] = path


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("manifest", "weights", "upstream", "cases", "output"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--threshold", type=float, required=True,
                        help="Threshold fixed using a separate calibration split")
    args = parser.parse_args()
    if not -1 <= args.threshold <= 1:
        raise ValueError("Invalid threshold")
    cases = json.loads(pathlib.Path(args.cases).read_text(encoding="utf-8"))
    validate_cases(cases)
    manifest, model, features = load_reference(args.manifest, args.weights, args.upstream)
    results = []
    references = {}
    for case in cases:
        enrollment = pathlib.Path(case["enrollment"]).resolve()
        candidate = pathlib.Path(case["candidate"]).resolve()
        if sha256(enrollment) == sha256(candidate):
            raise ValueError("Identical enrollment and candidate audio is not validation")
        if enrollment not in references:
            references[enrollment] = extract(enrollment, manifest, model, features)[1]
        value = extract(candidate, manifest, model, features)[1]
        score = sum(a * b for a, b in zip(references[enrollment], value))
        results.append({"phrase_id": case["phrase_id"], "speaker_id": case["speaker_id"],
            "source": case["source"], "expected": case["same_keyword"],
            "score": score, "matched": score >= args.threshold})
    human = [r for r in results if r["source"] == "human"]
    false_accepts = sum(r["matched"] and not r["expected"] for r in human)
    false_rejects = sum(not r["matched"] and r["expected"] for r in human)
    report = {"weights_sha256": manifest["weights_sha256"], "threshold": args.threshold,
        "human_cases": len(human), "false_accepts": false_accepts, "false_rejects": false_rejects,
        "production_validated": False, "speaker_verification_evaluated": False, "cases": results}
    pathlib.Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2,
        allow_nan=False), encoding="utf-8")
    if not human:
        raise SystemExit("Synthetic-only results do not validate Russian speech")
