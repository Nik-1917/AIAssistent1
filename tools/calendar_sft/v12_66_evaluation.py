"""V12.66 intent confusion metrics with all V12.65 spoken-clock checks."""
from collections import defaultdict
import json

from v12_65_evaluation import (case_from_row as previous_case, grade as previous_grade,
                               summary as previous_summary)

VERSION = "v12.66-intent-routing-1"
INTENTS = ("calendar_add", "calendar_search", "calendar_update", "calendar_delete", "calendar_sum", "chat")


def case_from_row(row, key=None, suite="v12_66"):
    return previous_case(row, key, suite)


def grade(case, raw):
    try:
        decoded = json.loads(raw)
    except ValueError:
        decoded = None
    if isinstance(decoded, dict) and not isinstance(decoded.get("intent"), str):
        # Frozen legacy validation assumes a hashable intent. Treat malformed
        # model output as a failed prediction without changing that validator.
        result = previous_grade(case, "{}")
        result.update(actual=decoded, raw=raw, json_valid=True,
                      contract_error="intent must be a string")
    else:
        result = previous_grade(case, raw)
    result.update(grader_version=VERSION, routing_family=case.get("routing_family", "inherited"))
    result["passed"] = bool(result["passed"] and result["intent_match"])
    return result


def intent_metrics(records):
    matrix = {expected: dict.fromkeys((*INTENTS, "invalid"), 0) for expected in INTENTS}
    for result in records:
        expected = result["expected"]["intent"]
        actual = result["actual"].get("intent")
        column = actual if isinstance(actual, str) and actual in INTENTS else "invalid"
        matrix[expected][column] += 1
    support = {intent: sum(row.values()) for intent, row in matrix.items()}
    rates = [matrix[intent][intent] / count for intent, count in support.items() if count]
    return dict(matrix=matrix, support=support, macro_accuracy=sum(rates) / len(rates) if rates else 0,
                passed=sum(r["intent_match"] is True for r in records), evaluated=len(records))


def summary(records):
    result = previous_summary(records)
    result.update(grader_version=VERSION, intent_confusion=intent_metrics(records))
    for suite, group in result["groups"].items():
        group["intent_confusion"] = intent_metrics([r for r in records if r["suite"] == suite])
    result["routing_families"] = {
        family: intent_metrics([r for r in records if r["routing_family"] == family])
        for family in sorted({r["routing_family"] for r in records})
    }
    return result


def selection_score(cases, records):
    """Rank development data by complete success, intent, clocks, then params."""
    if len(cases) != len(records) or not records:
        raise ValueError("development cases and predictions differ")
    groups = defaultdict(list)
    for case, result in zip(cases, records):
        if result["case_id"] != case["id"] or result["grader_version"] != VERSION:
            raise ValueError("wrong case order or evaluator version")
        if result["expected"] != case["expected"]:
            raise ValueError("wrong development target")
        if case.get("split") == "holdout":
            raise ValueError("holdout cannot be used for checkpoint selection")
        groups[case["expected"]["intent"]].append(result)
    def macro(metric):
        return sum(sum(r[metric] is True for r in rows) / len(rows) for rows in groups.values()) / len(groups)
    clocks = [r for r in records if r["reply_clock_correct"] is not None]
    agreement = sum(r["reply_clock_correct"] is True and r["reply_params_consistent"] is True for r in clocks)
    return [macro("passed"), macro("intent_match"), agreement / len(clocks) if clocks else 0,
            macro("params_exact")]
