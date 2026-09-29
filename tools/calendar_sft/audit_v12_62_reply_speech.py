"""Read clock phrases and check Russian inflection; never author dataset text."""
from __future__ import annotations
import json
from pathlib import Path
import re

from prepare_v12_61 import GENITIVE, NUMBERS, ORDINAL, number, norm
from prepare_v12_62 import OUTPUT, answer, read, write_json

ORD = "(?:" + "|".join(ORDINAL) + ")"
CARD = "(?:" + "|".join(sorted(NUMBERS, key=len, reverse=True)) + ")"
GEN = "(?:" + "|".join(sorted(GENITIVE, key=len, reverse=True)) + ")"
CARD_NUM = CARD + "(?: " + CARD + ")?"
GEN_NUM = GEN + "(?: " + GEN + ")?"
PATTERN = re.compile(
    r"\b(?P<before>без (?:(?P<quarter_to>четверти)|(?P<remaining>" + GEN_NUM + r")(?P<remaining_unit> минуты| минут)?) (?P<next>" + CARD_NUM + r"))\b"
    r"|\b(?P<special>(?P<quarter_half>четверть|половине) (?P<special_hour>" + ORD + r"))\b"
    r"|\b(?P<elapsed>(?P<minutes>" + CARD_NUM + r") (?P<minute_unit>минуту|минуты|минут) (?P<ordinal>" + ORD + r"))\b"
    r"|\b(?P<whole>(?:в|с|до) (?P<hour>" + CARD_NUM + r") (?P<hour_unit>часов|часа|час))\b"
    r"|\b(?P<one>(?:в|с|до) час)\b"
)


def scan(text):
    text = norm(text)
    found = []
    for m in PATTERN.finditer(text):
        canonical, problem = True, None
        kind = next(k for k in ("before", "special", "elapsed", "whole", "one") if m[k])
        if kind == "before":
            hour = (number(m["next"]) - 1) % 12
            remain = 15 if m["quarter_to"] else number(m["remaining"], GENITIVE)
            minute = 60 - remain
            prefix = text[:m.start()].rstrip()
            if re.search(r"\b(?:в|с|до)$", prefix):
                canonical, problem = False, "preposition_before_without"
            if not 1 <= remain <= 29 or not 1 <= number(m["next"]) <= 12:
                canonical, problem = False, "minutes_to_range"
            if not m["quarter_to"]:
                required_unit = None if remain == 10 else " минуты" if remain in (1, 21) else " минут"
                if m["remaining_unit"] != required_unit or remain == 15:
                    canonical, problem = False, "minutes_to_form_or_inflection"
                if m["next"] == "один":
                    canonical, problem = False, "one_hour_must_be_chas"
        elif kind == "special":
            hour = (ORDINAL[m["special_hour"]] - 1) % 12
            minute = 15 if m["quarter_half"] == "четверть" else 30
        elif kind == "elapsed":
            hour = (ORDINAL[m["ordinal"]] - 1) % 12
            minute = number(m["minutes"])
            unit = "минуту" if minute in (1, 21) else "минуты" if minute in (2, 3, 4, 22, 23, 24) else "минут"
            if not 1 <= minute <= 29 or minute == 15 or m["minute_unit"] != unit:
                canonical, problem = False, "elapsed_form_or_inflection"
            if minute in (1, 21) and not m["minutes"].endswith("одну"):
                canonical, problem = False, "elapsed_feminine_accusative"
            if minute in (2, 22) and not m["minutes"].endswith("две"):
                canonical, problem = False, "elapsed_feminine_two"
        else:
            raw_hour = 1 if kind == "one" else number(m["hour"])
            hour, minute = raw_hour % 12, 0
            if kind == "whole":
                expected_unit = "час" if raw_hour % 10 == 1 and raw_hour != 11 else "часа" if raw_hour % 10 in (2, 3, 4) and not 12 <= raw_hour <= 14 else "часов"
                if not 0 <= raw_hour <= 23 or m["hour_unit"] != expected_unit:
                    canonical, problem = False, "whole_hour_inflection"
        found.append({"phrase": m[0], "hour_mod_12": hour, "minute": minute,
                      "canonical": canonical, "problem": problem, "start": m.start(), "end": m.end()})
    return found


def check_clock(text, clock):
    h, m = map(int, clock.split(":"))
    found = scan(text)
    return len(found) == 1 and found[0]["canonical"] and (found[0]["hour_mod_12"], found[0]["minute"]) == (h % 12, m)


def main():
    rows = {p.stem: [json.loads(x) for x in p.read_text(encoding="utf-8").splitlines()] for p in OUTPUT.glob("*.jsonl")}
    checks = []
    for clock, item in read(OUTPUT / "clock_coverage_index.json").items():
        a = answer(rows["train"][item["train_line"] - 1])
        # Remove the exact title before looking for a clock; titles can quote time.
        phrase = re.sub(re.escape(a["params"]["title"]), "EVENT", a["reply"], count=1, flags=re.I)
        checks.append({"key": f"grid:{clock}", "clock": clock, "reply": a["reply"], "passed": check_clock(phrase, clock)})
    for edit in read(OUTPUT / "correction_log.json"):
        if edit["literal_reference"] == "literal_style":
            continue
        clock = edit["params"].get("starts_at", edit["params"].get("time"))[-5:]
        phrase = re.sub(re.escape(edit["params"]["title"]), "EVENT", edit["reply_after"], count=1, flags=re.I)
        checks.append({"key": edit["key"], "clock": clock, "reply": edit["reply_after"], "passed": check_clock(phrase, clock)})
    failures = [c for c in checks if not c["passed"]]
    report = {"status": "FAIL" if failures else "PASS", "total_clock_checks": len(checks),
              "grid_checks": 1440, "title_only_repair_checks": 700, "failed": failures,
              "scope": "Numerical clock and inflection for all grid cells and newly completed title-only replies; no model inference"}
    destination = Path(__file__).resolve().parents[2] / "build/v12_62_preparation/reply_speech_audit.json"
    write_json(destination, report)
    print(json.dumps(report, ensure_ascii=False))
    if failures:
        raise ValueError("Independent speech audit failed")


if __name__ == "__main__":
    main()
