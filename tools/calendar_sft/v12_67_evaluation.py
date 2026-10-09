"""Four-action scoring with independent clock meaning, style and arithmetic checks."""
from collections import defaultdict
from copy import deepcopy
from datetime import datetime, timedelta
import json

from v12_67_contract import INTENTS, parse_and_validate_assistant_response
from v12_67_clock import compare_clock_reply, expected_clocks

VERSION = "v12.67-four-actions-1"
METRICS = ("passed", "json_valid", "contract_valid", "intent_match", "params_exact",
           "params_case_insensitive", "temporal_params_match", "reply_clock_correct",
           "reply_style_correct", "offset_arithmetic_correct", "reply_params_consistent")


def check_reply(payload):
    return compare_clock_reply(payload.get("reply", ""), payload, expected_clocks(payload))


def case_from_row(row, key=None, suite="v12_67"):
    expected = parse_and_validate_assistant_response(row["messages"][-1]["content"])
    result = dict(id=key or row.get("case_id"), suite=suite,
                  system=row["messages"][0]["content"], user=row["messages"][1]["content"],
                  expected=expected, clocks=expected_clocks(expected), clock_phrases=[],
                  prompt_format="standard_chatml")
    if not result["id"]:
        raise ValueError("Evaluation needs a stable case ID")
    for key, value in row.get("audit", {}).items():
        if key not in result or key in ("clock_phrases",):
            result[key] = deepcopy(value)
    return result


def semantic_params(params):
    # Exact note text stays exact. Only the established title/query case tolerance applies.
    return {key: value.casefold() if key in ("title", "query") and isinstance(value, str) else value
            for key, value in params.items()}


def grade(case, raw):
    try:
        actual = json.loads(raw)
    except (ValueError, TypeError):
        actual = None
    valid_json = isinstance(actual, dict)
    actual = actual if valid_json else {}
    try:
        parse_and_validate_assistant_response(raw)
        error = None
    except ValueError as exception:
        error = str(exception)
    expected = case["expected"]
    intent = actual.get("intent") == expected["intent"]
    params = actual.get("params")
    exact = intent and params == expected["params"]
    folded = intent and isinstance(params, dict) and semantic_params(params) == semantic_params(expected["params"])
    temporal_keys = ("starts_at", "ends_at", "date", "time", "duration_min", "range_start", "range_end")
    temporal = intent and isinstance(params, dict) and (
        {key: params[key] for key in temporal_keys if key in params}
        == {key: expected["params"][key] for key in temporal_keys if key in expected["params"]}
    )
    reply = actual.get("reply", "")
    meaning, style, mentions = compare_clock_reply(
        reply if isinstance(reply, str) else "", expected, expected_clocks(expected),
    )
    consistent = check_reply(actual)[0] if error is None else False
    arithmetic = None
    if "offset_minutes" in case:
        computed = (datetime.fromisoformat(case["anchor_time"])
                    + timedelta(minutes=case["offset_minutes"])).isoformat(timespec="minutes")
        arithmetic = isinstance(params, dict) and params.get("starts_at") == computed
    passed = bool(valid_json and error is None and intent and folded
                  and meaning is not False and style is not False
                  and consistent is not False and arithmetic is not False)
    return dict(case_id=case["id"], suite=case["suite"], grader_version=VERSION,
                passed=passed, json_valid=valid_json, contract_valid=error is None,
                contract_error=error, intent_match=intent, params_exact=exact,
                params_case_insensitive=folded, temporal_params_match=temporal,
                reply_clock_correct=meaning, reply_style_correct=style,
                reply_params_consistent=consistent, offset_arithmetic_correct=arithmetic,
                detected_clocks=mentions, expected=expected, actual=actual, raw=raw,
                user=case["user"], system=case["system"],
                routing_family=case.get("routing_family", "inherited"))


def intent_metrics(records):
    matrix = {intent: dict.fromkeys((*sorted(INTENTS), "invalid"), 0) for intent in sorted(INTENTS)}
    for result in records:
        expected = result["expected"]["intent"]
        actual = result["actual"].get("intent")
        column = actual if isinstance(actual, str) and actual in INTENTS else "invalid"
        matrix[expected][column] += 1
    support = {intent: sum(row.values()) for intent, row in matrix.items()}
    rates = [matrix[intent][intent] / count for intent, count in support.items() if count]
    return dict(matrix=matrix, support=support, macro_accuracy=sum(rates) / len(rates) if rates else 0,
                passed=sum(record["intent_match"] is True for record in records), evaluated=len(records))


def summary(records):
    groups = {}
    for suite in sorted({record["suite"] for record in records}):
        rows = [record for record in records if record["suite"] == suite]
        groups[suite] = dict(total=len(rows), intent_confusion=intent_metrics(rows), **{
            key: dict(passed=sum(row[key] is True for row in rows),
                      evaluated=sum(row[key] is not None for row in rows)) for key in METRICS
        })
    return dict(grader_version=VERSION, total=len(records), groups=groups,
                intent_confusion=intent_metrics(records),
                failed_ids=[record["case_id"] for record in records if not record["passed"]])


def selection_score(cases, records):
    if len(cases) != len(records) or not records:
        raise ValueError("Development cases and predictions differ")
    groups = defaultdict(list)
    for case, result in zip(cases, records):
        if (result["case_id"] != case["id"] or result["grader_version"] != VERSION
                or result["expected"] != case["expected"] or case.get("split") == "holdout"):
            raise ValueError("Wrong development target, order, grader or partition")
        groups[case["expected"]["intent"]].append(result)
    def macro(metric):
        return sum(sum(row[metric] is True for row in rows) / len(rows) for rows in groups.values()) / len(groups)
    clocks = [row for row in records if row["reply_clock_correct"] is not None]
    agreement = sum(row["reply_clock_correct"] is True and row["reply_params_consistent"] is True for row in clocks)
    return [macro("passed"), macro("intent_match"), agreement / len(clocks) if clocks else 0, macro("params_exact")]
