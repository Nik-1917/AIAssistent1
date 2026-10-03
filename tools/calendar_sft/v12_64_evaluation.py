"""Versioned semantic clock grading; never silently rescore frozen releases.

Numerical meaning and the V12.62 speaking style are separate metrics. All add
commands with a known clock are checked, including inherited regression cases.
This module is offline evaluation code, not an Android natural-language parser.
"""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta
import json
import re

from audit_v12_62_reply_speech import scan, CARD_NUM
from evaluate_v12_62_gguf import grade as previous_grade
from prepare_v12_61 import norm, number

VERSION = "v12.64-semantic-clock-1"
FULL = re.compile(
    r"\b(?:в|с|до) (?P<h>" + CARD_NUM + r") (?:часов|часа|час) "
    r"(?P<m>" + CARD_NUM + r") (?:минуту|минуты|минут)(?!\w)"
)
DIGITAL = re.compile(r"(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)")
WHOLE = re.compile(r"(?:в|с|до) (.+?) (?:часов|часа|час)$")


def expected_clocks(expected):
    if expected.get("intent") == "calendar_add":
        params = expected.get("params", {})
        # starts_at and time are alternative representations of the same start.
        start = params.get("starts_at", params.get("time"))
        return ([start[-5:]] if start else []) + (
            [params["ends_at"][-5:]] if params.get("ends_at") else []
        )
    if expected.get("intent") == "calendar_update":
        time = expected.get("params", {}).get("changes", {}).get("time")
        return [time] if time else []
    return []


def clock_mentions(text):
    """Read full clock phrases before shorter overlapping whole-hour phrases."""
    text = norm(text)
    found = []
    for match in FULL.finditer(text):
        h, m = number(match["h"]), number(match["m"])
        if 0 <= h <= 23 and 0 <= m <= 59:
            found.append(dict(start=match.start(), end=match.end(), phrase=match[0],
                              hour=h, minute=m, ambiguous=h in range(1, 12), canonical=False))
    for match in DIGITAL.finditer(text):
        found.append(dict(start=match.start(), end=match.end(), phrase=match[0],
                          hour=int(match[1]), minute=int(match[2]), ambiguous=False, canonical=False))
    for clock in scan(text):
        if any(clock["start"] < x["end"] and x["start"] < clock["end"] for x in found):
            continue
        h = clock["hour_mod_12"]
        whole = WHOLE.fullmatch(clock["phrase"])
        if whole:
            h = number(whole[1])
        # Named 00/12/13..23 hours are explicit; unqualified 1..11 and relative
        # idioms use the twelve-hour circle allowed in the existing reply corpus.
        found.append(dict(start=clock["start"], end=clock["end"], phrase=clock["phrase"],
                          hour=h, minute=clock["minute"],
                          ambiguous=not whole or 1 <= h <= 11, canonical=clock["canonical"]))
    for clock in found:
        part = re.match(r"\s+(утра|дня|вечера|ночи)\b", text[clock["end"]:])
        if part:
            h = clock["hour"] % 12
            if part[1] in ("дня", "вечера"):
                h += 12
            elif part[1] == "ночи" and h >= 6:
                h += 12
            clock.update(hour=h, ambiguous=False, canonical=False)
    return sorted(found, key=lambda x: x["start"])


def without_title(reply, expected):
    params = expected.get("params", {})
    titles = [params.get("title"), params.get("changes", {}).get("title")]
    for title in titles:
        if title:
            reply = re.sub(re.escape(title), "EVENT", reply, count=1, flags=re.I)
    return reply


def compare_clock_reply(reply, expected, clocks):
    mentions = clock_mentions(without_title(reply, expected))
    if not clocks:
        if expected.get("intent") == "calendar_add":
            return (False, False, mentions) if mentions else (None, None, mentions)
        return None, None, mentions
    params = expected.get("params", {})
    if (len(clocks) == 1 and len(mentions) == 2 and params.get("starts_at")
            and isinstance(params.get("duration_min"), int)):
        end = datetime.fromisoformat(params["starts_at"]) + timedelta(minutes=params["duration_min"])
        clocks = [*clocks, end.strftime("%H:%M")]
    meaning = len(mentions) == len(clocks)
    for actual, target in zip(mentions, clocks):
        h, m = map(int, target.split(":"))
        hour_ok = actual["hour"] % 12 == h % 12 if actual["ambiguous"] else actual["hour"] == h
        meaning = meaning and hour_ok and actual["minute"] == m
    style = bool(mentions) and len(mentions) == len(clocks) and all(x["canonical"] for x in mentions)
    return bool(meaning), bool(style), mentions


def case_from_row(row, key=None, suite="v12_64"):
    expected = json.loads(row["messages"][-1]["content"])
    result = dict(id=key or row["case_id"], suite=suite,
                  system=row["messages"][0]["content"], user=row["messages"][1]["content"],
                  expected=expected, clocks=expected_clocks(expected), clock_phrases=[],
                  prompt_format="standard_chatml")
    result.update(row.get("audit", {}))
    return result


def grade(case, raw):
    case = deepcopy(case)
    # A frozen case with clocks=[] must not disable checking its known start.
    derived = expected_clocks(case["expected"])
    if derived and case.get("clocks") and case["clocks"] != derived:
        raise ValueError("clock expectations disagree with expected params")
    case["clocks"] = derived or case.get("clocks", [])
    case.setdefault("clock_phrases", [])
    result = previous_grade(case, raw)
    reply = result["actual"].get("reply", "")
    reply = reply if isinstance(reply, str) else ""
    meaning, style, mentions = compare_clock_reply(reply, case["expected"], case["clocks"])
    style = style and result["reply_clock_phrases_present"] if style is not None else None
    arithmetic = None
    if "offset_minutes" in case:
        computed = (datetime.fromisoformat(case["anchor_time"]) +
                    timedelta(minutes=case["offset_minutes"])).isoformat(timespec="minutes")
        params = result["actual"].get("params")
        arithmetic = isinstance(params, dict) and params.get("starts_at") == computed
    result.update(grader_version=VERSION, reply_clock_correct=meaning,
                  reply_style_correct=style, detected_clocks=mentions,
                  offset_arithmetic_correct=arithmetic)
    result["passed"] = (result["json_valid"] and result["contract_valid"] and
                        result["params_case_insensitive"] and meaning is not False and
                        style is not False and arithmetic is not False)
    return result


def summary(results):
    metrics = ("passed", "json_valid", "contract_valid", "params_exact",
               "params_case_insensitive", "temporal_params_match", "reply_clock_correct",
               "reply_style_correct", "offset_arithmetic_correct")
    groups = {}
    for name in sorted({r["suite"] for r in results}):
        rows = [r for r in results if r["suite"] == name]
        groups[name] = {"total": len(rows), **{
            key: {"passed": sum(r.get(key) is True for r in rows),
                  "evaluated": sum(r.get(key) is not None for r in rows)} for key in metrics}}
    return {"grader_version": VERSION, "total": len(results), "groups": groups,
            "failed_ids": [r["case_id"] for r in results if not r["passed"]]}
