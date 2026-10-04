"""V12.65: compare spoken time with both gold and actual event parameters."""
from collections import defaultdict
from copy import deepcopy

from v12_64_evaluation import (
    case_from_row as previous_case, grade as previous_grade,
    compare_clock_reply, expected_clocks, summary as previous_summary,
)

VERSION = "v12.65-reply-consistency-1"


def clock_payload(payload):
    """Exact named update targets are opaque, like exact creation titles."""
    result = deepcopy(payload)
    p = result.get("params", {})
    if result.get("intent") == "calendar_update" and p.get("target", {}).get("query"):
        p.setdefault("title", p["target"]["query"])
    return result


def check_reply(payload):
    return compare_clock_reply(payload.get("reply", ""), clock_payload(payload), expected_clocks(payload))


def case_from_row(row, key=None, suite="v12_65"):
    return previous_case(row, key, suite)


def grade(case, raw):
    result = previous_grade(case, raw)
    expected = clock_payload(case["expected"])
    actual = result["actual"]
    reply = actual.get("reply", "")
    reply = reply if isinstance(reply, str) else ""
    meaning, style, mentions = compare_clock_reply(reply, expected, expected_clocks(expected))
    consistent = check_reply(actual)[0] if result["contract_valid"] else False
    result.update(grader_version=VERSION, reply_clock_correct=meaning,
                  reply_style_correct=style, detected_clocks=mentions,
                  reply_params_consistent=consistent)
    result["passed"] = bool(result["json_valid"] and result["contract_valid"]
                            and result["params_case_insensitive"]
                            and meaning is not False and style is not False
                            and consistent is not False
                            and result.get("offset_arithmetic_correct") is not False)
    return result


def summary(records):
    result = previous_summary(records)
    result["grader_version"] = VERSION
    for name, group in result["groups"].items():
        rows = [r for r in records if r["suite"] == name]
        group["reply_params_consistent"] = {
            "passed": sum(r.get("reply_params_consistent") is True for r in rows),
            "evaluated": sum(r.get("reply_params_consistent") is not None for r in rows),
        }
    return result


def selection_score(cases, records):
    """Full pass includes spoken correctness; rank solely on development data."""
    if len(cases) != len(records) or not records:
        raise ValueError("development cases and predictions differ")
    groups = defaultdict(list)
    for case, result in zip(cases, records):
        if result["case_id"] != case["id"] or result["grader_version"] != VERSION:
            raise ValueError("wrong case order or evaluator version")
        groups[case["expected"]["intent"]].append(result)
    macro = sum(sum(r["passed"] is True for r in rows) / len(rows)
                for rows in groups.values()) / len(groups)
    clocks = [r for r in records if r["reply_clock_correct"] is not None]
    return [macro, sum(r["reply_clock_correct"] is True and r["reply_params_consistent"] is True
                       for r in clocks) / len(clocks) if clocks else 0,
            sum(sum(r["params_exact"] is True for r in rows) / len(rows)
                for rows in groups.values()) / len(groups)]
