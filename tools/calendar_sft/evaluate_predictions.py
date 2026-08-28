"""Score model JSON outputs against the independent calendar holdout.

Predictions are JSONL objects with exactly two keys:
    {"case_id":"H001","output":"{...strict assistant JSON...}"}

The scorer intentionally does not compare the wording of `reply`; it validates
reply-format rules, reports exact executable params, and separately applies a
manually reviewed semantic-acceptance policy bound to the frozen holdout hash.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import sys
from typing import Any

from dataset_contract import (
    DatasetContractError,
    file_sha256,
    load_jsonl,
    parse_and_validate_assistant_response,
)


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_HOLDOUT = ROOT / "docs" / "calendar_assistant_manual_holdout.jsonl"
DEFAULT_SEMANTIC_ACCEPTANCE = (
    ROOT / "docs" / "calendar_assistant_holdout_semantic_acceptance.json"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--predictions", required=True, type=Path)
    parser.add_argument("--holdout", default=DEFAULT_HOLDOUT, type=Path)
    parser.add_argument(
        "--semantic-acceptance",
        default=DEFAULT_SEMANTIC_ACCEPTANCE,
        type=Path,
    )
    parser.add_argument("--report", required=True, type=Path)
    return parser.parse_args()


def load_predictions(path: Path) -> dict[str, str]:
    predictions: dict[str, str] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            raise DatasetContractError(f"{path}:{line_number}: blank rows are not allowed")
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise DatasetContractError(f"{path}:{line_number}: invalid row JSON: {error.msg}") from error
        if not isinstance(row, dict) or set(row) != {"case_id", "output"}:
            raise DatasetContractError(f"{path}:{line_number}: expected exactly case_id and output")
        case_id = row["case_id"]
        output = row["output"]
        if not isinstance(case_id, str) or not case_id:
            raise DatasetContractError(f"{path}:{line_number}: case_id must be a non-empty string")
        if not isinstance(output, str):
            raise DatasetContractError(f"{path}:{line_number}: output must be a string")
        if case_id in predictions:
            raise DatasetContractError(f"{path}:{line_number}: duplicate case_id {case_id}")
        predictions[case_id] = output
    return predictions


def percent(numerator: int, denominator: int) -> float:
    return round(100.0 * numerator / denominator, 2) if denominator else 0.0


def report_path(path: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(ROOT).as_posix()
    except ValueError:
        return str(resolved)


def normalize_semantic_text_case(value: Any) -> Any:
    """Case-fold semantic event text while keeping all other values strict."""

    if isinstance(value, dict):
        return {
            key: item.casefold()
            if key in {"title", "query"} and isinstance(item, str)
            else normalize_semantic_text_case(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [normalize_semantic_text_case(item) for item in value]
    return value


def params_semantically_equal(
    actual: dict[str, Any],
    expected: dict[str, Any],
    accepted_alternatives: tuple[dict[str, Any], ...] = (),
) -> bool:
    normalized_actual = normalize_semantic_text_case(actual)
    return any(
        normalized_actual == normalize_semantic_text_case(candidate)
        for candidate in (expected, *accepted_alternatives)
    )


def load_semantic_acceptance(
    path: Path,
    holdout_path: Path,
    holdout: list[dict[str, Any]],
) -> dict[str, tuple[dict[str, Any], ...]]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise DatasetContractError(f"{path}: invalid JSON: {error.msg}") from error
    if not isinstance(payload, dict) or set(payload) != {
        "format_version",
        "holdout_sha256",
        "cases",
    }:
        raise DatasetContractError(f"{path}: invalid semantic-acceptance field set")
    if payload["format_version"] != 1:
        raise DatasetContractError(f"{path}: unsupported semantic-acceptance format")
    if payload["holdout_sha256"] != file_sha256(holdout_path):
        raise DatasetContractError(f"{path}: holdout SHA-256 does not match")
    cases = payload["cases"]
    if not isinstance(cases, dict):
        raise DatasetContractError(f"{path}: cases must be an object")

    expected_by_id = {
        row["case_id"]: parse_and_validate_assistant_response(
            row["messages"][-1]["content"],
            f"holdout[{row['case_id']}]",
        )
        for row in holdout
    }
    accepted: dict[str, tuple[dict[str, Any], ...]] = {}
    for case_id, rule in cases.items():
        if case_id not in expected_by_id:
            raise DatasetContractError(f"{path}: unknown holdout case {case_id}")
        if not isinstance(rule, dict) or set(rule) != {"reason", "accepted_params"}:
            raise DatasetContractError(f"{path}: invalid rule for {case_id}")
        if not isinstance(rule["reason"], str) or not rule["reason"].strip():
            raise DatasetContractError(f"{path}: blank reason for {case_id}")
        alternatives = rule["accepted_params"]
        if not isinstance(alternatives, list) or not alternatives:
            raise DatasetContractError(f"{path}: accepted_params must be non-empty for {case_id}")
        validated: list[dict[str, Any]] = []
        signatures: set[str] = set()
        for index, alternative in enumerate(alternatives):
            if not isinstance(alternative, dict):
                raise DatasetContractError(
                    f"{path}: accepted_params[{index}] must be an object for {case_id}",
                )
            candidate = dict(expected_by_id[case_id])
            candidate["params"] = alternative
            parse_and_validate_assistant_response(
                json.dumps(candidate, ensure_ascii=False, separators=(",", ":")),
                f"semantic_acceptance[{case_id}][{index}]",
            )
            signature = json.dumps(alternative, ensure_ascii=False, sort_keys=True)
            if signature in signatures:
                raise DatasetContractError(f"{path}: duplicate alternative for {case_id}")
            signatures.add(signature)
            validated.append(alternative)
        accepted[case_id] = tuple(validated)
    return accepted


def main() -> int:
    args = parse_args()
    try:
        holdout = load_jsonl(args.holdout)
        predictions = load_predictions(args.predictions)
        semantic_acceptance = load_semantic_acceptance(
            args.semantic_acceptance,
            args.holdout,
            holdout,
        )
    except (DatasetContractError, OSError) as error:
        print(f"Cannot score predictions: {error}", file=sys.stderr)
        return 2

    expected_ids = {row["case_id"] for row in holdout}
    unknown_ids = sorted(set(predictions) - expected_ids)
    errors: list[dict[str, str]] = []
    accepted_semantic_variants: list[dict[str, str]] = []
    by_category: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    valid_response_count = 0
    intent_match_count = 0
    params_match_count = 0
    semantic_pass_count = 0

    for row in holdout:
        case_id = row["case_id"]
        category = row["category"]
        totals = by_category[category]
        totals["total"] += 1
        actual_text = predictions.get(case_id)
        if actual_text is None:
            errors.append({"case_id": case_id, "error": "missing prediction"})
            continue
        try:
            actual = parse_and_validate_assistant_response(actual_text, f"prediction[{case_id}]")
        except DatasetContractError as error:
            errors.append({"case_id": case_id, "error": str(error)})
            continue
        valid_response_count += 1
        totals["valid_response"] += 1
        expected = parse_and_validate_assistant_response(
            row["messages"][-1]["content"],
            f"holdout[{case_id}]",
        )
        intent_match = actual["intent"] == expected["intent"]
        params_match = actual["params"] == expected["params"]
        semantic_params_match = params_semantically_equal(
            actual["params"],
            expected["params"],
            semantic_acceptance.get(case_id, ()),
        )
        if intent_match:
            intent_match_count += 1
            totals["intent_match"] += 1
        if params_match:
            params_match_count += 1
            totals["params_match"] += 1
        if intent_match and semantic_params_match:
            semantic_pass_count += 1
            totals["semantic_pass"] += 1
        if intent_match and semantic_params_match and not params_match:
            accepted_semantic_variants.append(
                {
                    "case_id": case_id,
                    "expected_params": json.dumps(expected["params"], ensure_ascii=False, sort_keys=True),
                    "actual_params": json.dumps(actual["params"], ensure_ascii=False, sort_keys=True),
                },
            )
        elif not intent_match or not semantic_params_match:
            errors.append(
                {
                    "case_id": case_id,
                    "error": "intent or params differ from expected",
                    "expected_intent": expected["intent"],
                    "actual_intent": actual["intent"],
                    "expected_params": json.dumps(expected["params"], ensure_ascii=False, sort_keys=True),
                    "actual_params": json.dumps(actual["params"], ensure_ascii=False, sort_keys=True),
                },
            )

    for case_id in unknown_ids:
        errors.append({"case_id": case_id, "error": "prediction case_id is not in holdout"})

    total = len(holdout)
    category_report = {
        category: {
            "total": metrics["total"],
            "valid_response_percent": percent(metrics["valid_response"], metrics["total"]),
            "intent_match_percent": percent(metrics["intent_match"], metrics["total"]),
            "params_match_percent": percent(metrics["params_match"], metrics["total"]),
            "semantic_pass_percent": percent(metrics["semantic_pass"], metrics["total"]),
        }
        for category, metrics in sorted(by_category.items())
    }
    report: dict[str, Any] = {
        "format_version": 2,
        "total_cases": total,
        "predictions_received": len(predictions),
        "valid_response_percent": percent(valid_response_count, total),
        "intent_match_percent": percent(intent_match_count, total),
        "params_match_percent": percent(params_match_count, total),
        "semantic_pass_percent": percent(semantic_pass_count, total),
        "semantic_acceptance": {
            "path": report_path(args.semantic_acceptance),
            "sha256": file_sha256(args.semantic_acceptance),
            "cases": len(semantic_acceptance),
        },
        "by_category": category_report,
        "accepted_semantic_variants": accepted_semantic_variants,
        "errors": errors,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({key: report[key] for key in report if key.endswith("percent")}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
