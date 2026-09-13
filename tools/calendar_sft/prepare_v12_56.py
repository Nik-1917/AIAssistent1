"""Audit handwritten V12.56 conversations and serialize a separate dataset.

The bounded phrase reader checks this manual register, not Android input.
No language generation, word permutations or automatic target repair.
"""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from datetime import date, datetime, time, timedelta
import json
from pathlib import Path
import re

from dataset_contract import (RUNTIME_SYSTEM_RE, file_sha256, load_jsonl,
                              message_signature, normalize_record, normalized_user_prompt)
from dataset_provenance import verify_dataset_provenance
from prepare_v12_5 import ORDINALS
from prepare_v12_51 import HOUR_WORDS, WEEKDAYS, hour_unit, read_json, write_json
from prepare_v12_52 import normalized_words
from prepare_v12_53 import read_spoken_clock
from prepare_v12_54 import TO_CLOCK_RE, CARDINAL_HOURS, REMAINING_MINUTES
from prepare_v12_55 import PART_HOURS, CONTEXT_RE, DURATION_RE, LOCAL_DURATIONS, read_half_clock

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_55"
OUTPUT = ROOT / "docs/calendar_sft_v12_56"
MANUAL = ROOT / "docs/calendar_assistant_v12_56_intervals_manual.json"
SOURCE_HASHES = {
    "train": "1cb9bfcb69dbef2b0f174db31adc9b89b60169dbeee1a18aaba7c20b344dc46c",
    "validation": "09c639b71310a25009f9734a43ae0aee379abf44406718020b485587c41fe99d",
    "holdout": "a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19",
    "calendar_holdout_v12_2": "504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade",
    "regression_holdout": "b2a7bdbc93a6f59769f0ab0a9108dc707e1bc7a494d00cbd5c8bb2fbfa7d1e34",
    "half_hour_holdout": "fa9d19e273acad133b3c8a996221bfdd2cea7cc2902b03b08e0f7b6a766c5a9f",
}
REPLACEMENT_IDS = {f"V1255HC{i:02d}" for i in range(1, 7)}
NEW_COUNTS = {"train": 72, "validation": 12, "interval_holdout": 12}
TOTAL_ROWS = {"train": 2617, "validation": 756, "holdout": 36,
              "calendar_holdout_v12_2": 8, "regression_holdout": 63,
              "half_hour_holdout": 36, "interval_holdout": 12}
ID_RE = re.compile(r"V1256(?P<split>[TEH])(?P<kind>[DWPI])(?P<hour>\d{2})(?P<form>0[1-6])?")
SPLITS = {"T": "train", "E": "validation", "H": "interval_holdout"}
GENITIVE = {
    "нуля": 0, "ноля": 0, "часу": 1, "одного": 1, "двух": 2,
    "трех": 3, "четырех": 4, "пяти": 5, "шести": 6, "семи": 7,
    "восьми": 8, "девяти": 9, "десяти": 10, "одиннадцати": 11, "двенадцати": 12,
}
HOUR_PATTERN = "|".join(sorted(map(re.escape, GENITIVE), key=len, reverse=True))
PHRASE = (
    r"(?:(?P<numeric>\d{2}:\d{2})|(?P<half>половины |пол[- ]?)"
    r"(?P<ordinal>" + "|".join(ORDINALS) + r")|(?P<hour>" + HOUR_PATTERN
    + r")(?: часов)?)\b(?: (?P<part>утра|дня|вечера|ночи)\b)?"
)
START_RE = re.compile(r"\b(?:с|со|от) " + PHRASE)
END_RE = re.compile(r"\bдо " + PHRASE)


def default_half(user: str) -> tuple[int, int]:
    form, hour, match = read_half_clock(user)
    return form, hour if hour is not None else ORDINALS.index(match["ordinal"]) + 12


def endpoint(match: re.Match, shared: str | None) -> time:
    if match["numeric"]:
        return time.fromisoformat(match["numeric"])
    base = ORDINALS.index(match["ordinal"]) if match["half"] else GENITIVE[match["hour"]]
    minute = 30 if match["half"] else 0
    part = match["part"] or shared
    if part:
        candidates = {base % 12, base % 12 + 12} & PART_HOURS[part]
        if len(candidates) != 1:
            raise ValueError("explicit endpoint daypart cannot resolve the named hour")
        return time(candidates.pop(), minute)
    return time(base + 12 if match["half"] else base, minute)


def interval_clocks(user: str) -> tuple[time, time]:
    user = normalized_words(user)
    starts, ends = list(START_RE.finditer(user)), list(END_RE.finditer(user))
    if len(starts) != 1 or len(ends) != 1 or starts[0].start() >= ends[0].start():
        raise ValueError("expected exactly one ordered from/to interval")
    contexts = set(CONTEXT_RE.findall(user))
    if len(contexts) > 1:
        raise ValueError("conflicting whole-interval dayparts")
    # A trailing shared qualifier resolves an omitted qualifier on the start;
    # explicit qualifiers on each endpoint override the shared context.
    common = next(iter(contexts), None)
    return endpoint(starts[0], common or ends[0]["part"]), endpoint(ends[0], common)


def start_date(user: str, now: datetime, clock: time) -> date:
    if re.search(r"\bзавтра\b", normalized_words(user)):
        return now.date() + timedelta(days=1)
    if re.search(r"\bсегодня\b", normalized_words(user)) or clock > now.time():
        return now.date()
    return now.date() + timedelta(days=1)


def spoken_clock(phrase: str, minute: int) -> tuple[int, int]:
    phrase = normalized_words(phrase)
    if minute == 0:
        match = re.fullmatch(r"в (.+) (час|часа|часов)", phrase)
        if match and match[1] in HOUR_WORDS:
            hour = HOUR_WORDS.index(match[1])
            if match[2] == hour_unit(hour):
                return hour % 12, 0
        raise ValueError("invalid spoken whole hour")
    if minute in (35, 40, 50, 55):
        match = TO_CLOCK_RE.fullmatch(phrase)
        if match and 60 - REMAINING_MINUTES[match["remaining"]] == minute:
            return CARDINAL_HOURS.index(match["hour"]), minute
        raise ValueError("invalid minutes-to reply")
    return read_spoken_clock(phrase, minute)


def validate_semantics(example: dict, is_default: bool) -> None:
    label, user, response = example["id"], example["user"], example["assistant"]
    system = RUNTIME_SYSTEM_RE.fullmatch(example["system"])
    if system is None or system["zone"] != "Europe/Samara":
        raise ValueError(f"{label}: unexpected system context")
    now = datetime.fromisoformat(system["date"] + "T" + system["time"])
    if WEEKDAYS[now.weekday()] != system["weekday"]:
        raise ValueError(f"{label}: wrong weekday")
    params = response["params"]
    if is_default:
        _, hour = default_half(user)
        start_clock = time(hour, 30)
    else:
        start_clock, end_clock = interval_clocks(user)
    start = datetime.combine(start_date(user, now, start_clock), start_clock)
    day_word = "сегодня" if start.date() == now.date() else "завтра"
    if response["intent"] == "calendar_search":
        if is_default or not normalized_words(user).startswith("покажи "):
            raise ValueError(f"{label}: unexpected search")
        end = datetime.combine(start.date(), end_clock)
        if end <= start:
            raise ValueError("search control must have an ordered same-day period")
        expected = {"query": "", "range_start": start.isoformat(timespec="minutes"), "range_end": end.isoformat(timespec="minutes")}
        if params != expected:
            raise ValueError(f"{label}: wrong search period")
        return
    if response["intent"] != "calendar_add":
        raise ValueError(f"{label}: expected event creation")
    expected_fields = {"title", "starts_at", "duration_min" if is_default else "ends_at"}
    if set(params) != expected_fields or params["starts_at"] != start.isoformat(timespec="minutes"):
        raise ValueError(f"{label}: wrong start or fields")
    prefix = re.escape(params["title"] + " " + day_word + ", ")
    if is_default:
        duration = DURATION_RE.search(normalized_words(user))
        if duration is None or LOCAL_DURATIONS[duration[1]] != params["duration_min"]:
            raise ValueError(f"{label}: wrong duration")
        reply = re.fullmatch(prefix + r"([^,]+), на ([^.]+)\.", response["reply"])
        if reply is None or LOCAL_DURATIONS.get(normalized_words(reply[2])) != params["duration_min"]:
            raise ValueError(f"{label}: reply title, date or duration differs")
        if spoken_clock(reply[1], 30) != (start.hour % 12, 30):
            raise ValueError(f"{label}: wrong reply clock")
        return
    if end_clock == start_clock:
        raise ValueError(f"{label}: equal clocks do not imply a full day")
    end = datetime.combine(start.date() + timedelta(days=int(end_clock < start_clock)), end_clock)
    if params["ends_at"] != end.isoformat(timespec="minutes"):
        raise ValueError(f"{label}: wrong end date or clock")
    reply = re.fullmatch(prefix + r"начало ([^,]+), окончание (.+)\.", response["reply"])
    if reply is None:
        raise ValueError(f"{label}: wrong reply title, date or endpoints")
    ending = reply[2]
    next_day = ending.endswith(" следующего дня")
    if next_day != (end.date() > start.date()):
        raise ValueError(f"{label}: reply end date differs")
    ending = ending.removesuffix(" следующего дня")
    if spoken_clock(reply[1], start.minute) != (start.hour % 12, start.minute) or spoken_clock(ending, end.minute) != (end.hour % 12, end.minute):
        raise ValueError(f"{label}: reply endpoints differ from parameters")


def literal_row(example: dict, kind: str) -> dict:
    return normalize_record({
        "case_id": example["id"], "category": "v12_56_default_half" if kind == "D" else "v12_56_event_interval",
        "contract_version": "v12.56", "messages": [
            {"role": "system", "content": example["system"]},
            {"role": "user", "content": example["user"]},
            {"role": "assistant", "content": json.dumps(example["assistant"], ensure_ascii=False, separators=(",", ":"))},
        ],
    }, example["id"])


def validate_manual(manual: dict) -> dict[str, list[dict]]:
    if manual["version"] != "v12.56" or set(manual["replacements"]) != REPLACEMENT_IDS:
        raise ValueError("wrong manual version or replacement scope")
    added = {s: [] for s in NEW_COUNTS}
    ids, defaults, forms, intervals = set(), Counter(), Counter(), Counter()
    for e in manual["examples"]:
        match = ID_RE.fullmatch(e["id"])
        if match is None or set(e) != {"id", "system", "user", "assistant"} or e["id"] in ids:
            raise ValueError("invalid or duplicate manual case")
        ids.add(e["id"])
        kind, split = match["kind"], SPLITS[match["split"]]
        row = literal_row(e, kind)
        if [m["content"] for m in row["messages"][:2]] != [e["system"], e["user"]] or json.loads(row["messages"][-1]["content"]) != e["assistant"]:
            raise ValueError("normalization changed literal content")
        validate_semantics(e, kind == "D")
        if kind == "D":
            form, hour = default_half(e["user"])
            if hour != int(match["hour"]) or form != int(match["form"] or 0):
                raise ValueError("default case ID differs from phrase")
            if read_half_clock(e["user"])[1] is not None:
                raise ValueError("default grid requires an unspecified daypart")
            defaults[split, hour] += 1
            forms[split, form] += 1
        if split == "train" and kind in ("W", "P"):
            start, end = interval_clocks(e["user"])
            if start.hour != int(match["hour"]) or start.minute != (0 if kind == "W" else 30):
                raise ValueError("interval ID differs from phrase")
            intervals[kind, "start", start.hour] += 1
            intervals[kind, "end", end.hour] += 1
        added[split].append(row)
    if {s: len(r) for s, r in added.items()} != NEW_COUNTS:
        raise ValueError("unexpected manual counts")
    if {h: defaults["train", h] for h in range(24)} != {h: 2 if h >= 12 else 0 for h in range(24)}:
        raise ValueError("default grid must have two cases for each PM hour")
    if forms != Counter({(s, f): 4 if s == "train" else 1 for s in NEW_COUNTS for f in range(1, 7)}):
        raise ValueError("six default forms must be balanced in each split")
    if intervals != Counter({(k, end, h): 1 for k in ("W", "P") for end in ("start", "end") for h in range(24)}):
        raise ValueError("both endpoints must cover all 24 hours in both training grids")
    return added


def validate_inputs() -> tuple[dict, dict]:
    for split, digest in SOURCE_HASHES.items():
        path = SOURCE / f"{split}.jsonl"
        if file_sha256(path) != digest or not path.read_bytes().endswith(b"\n"):
            raise ValueError(f"changed source: {split}")
    verify_dataset_provenance(SOURCE / "provenance.json", SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    manual = read_json(MANUAL)
    added = validate_manual(manual)
    outputs = {s: load_jsonl(SOURCE / f"{s}.jsonl") for s in SOURCE_HASHES}
    seen = set()
    for row in outputs["half_hour_holdout"]:
        case_id = row.get("case_id")
        if case_id not in REPLACEMENT_IDS:
            continue
        old = json.loads(row["messages"][-1]["content"])
        replacement = manual["replacements"][case_id]
        before, after = deepcopy(old["params"]), deepcopy(replacement["params"])
        old_date, new_start = before.pop("date"), after.pop("starts_at")
        if before != after or not new_start.startswith(old_date + "T") or old["intent"] != replacement["intent"]:
            raise ValueError("replacement changes unrelated parameters")
        e = {"id": case_id, "system": row["messages"][0]["content"], "user": row["messages"][-2]["content"], "assistant": replacement}
        validate_semantics(e, True)
        row.update(literal_row(e, "D"))
        seen.add(case_id)
    if seen != REPLACEMENT_IDS:
        raise ValueError("unused replacement")
    for s, rows in added.items():
        outputs.setdefault(s, []).extend(rows)
    ids, signatures = set(), set()
    for s, rows in outputs.items():
        for row in rows:
            case_id, signature = row.get("case_id"), message_signature(row)
            if (case_id and case_id in ids) or signature in signatures:
                raise ValueError(f"duplicate case or context: {s}")
            if case_id:
                ids.add(case_id)
            signatures.add(signature)
            if json.loads(row["messages"][-1]["content"])["intent"] == "note_add":
                raise ValueError("removed intent reintroduced")
    prompts = {s: {normalized_user_prompt(r) for r in rows} for s, rows in outputs.items()}
    for s, rows in added.items():
        others = set().union(*(v for key, v in prompts.items() if key != s))
        if others & {normalized_user_prompt(r) for r in rows}:
            raise ValueError("new prompt overlaps another split")
    fitting = prompts["train"] | prompts["validation"]
    if any(fitting & p for s, p in prompts.items() if s not in {"train", "validation"}):
        raise ValueError("holdout leakage")
    if {s: len(rows) for s, rows in outputs.items()} != TOTAL_ROWS:
        raise ValueError("unexpected final counts")
    return added, outputs


def serialized(outputs: dict) -> dict[str, bytes]:
    result = {}
    for split, rows in outputs.items():
        source = SOURCE / f"{split}.jsonl"
        old_lines = source.read_bytes().splitlines(keepends=True) if source.exists() else []
        lines = []
        for index, row in enumerate(rows):
            if index < len(old_lines) and row.get("case_id") not in REPLACEMENT_IDS:
                if normalize_record(json.loads(old_lines[index]), "original") != row:
                    raise ValueError("unexpected inherited change")
                lines.append(old_lines[index])
            else:
                lines.append((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        result[split] = b"".join(lines)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    added, outputs = validate_inputs()
    if args.check_only:
        print(json.dumps(TOTAL_ROWS))
        return
    if OUTPUT.exists():
        raise ValueError("refusing to overwrite V12.56")
    payloads = serialized(outputs)
    OUTPUT.mkdir()
    for s, payload in payloads.items():
        (OUTPUT / f"{s}.jsonl").write_bytes(payload)
    artifacts = {s: {"sha256": file_sha256(OUTPUT / f"{s}.jsonl")} for s in outputs}
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.56", "base_version": "v12.55", "new_contract": "v12.56",
        "manual_sha256": file_sha256(MANUAL), "source_sha256": SOURCE_HASHES,
        "new_counts": NEW_COUNTS, "total_rows": TOTAL_ROWS, "artifacts": artifacts,
        "relabelled_holdout_cases": sorted(REPLACEMENT_IDS), "other_inherited_bytes": "UNCHANGED",
        "training": "NOT_RUN", "model_evaluation": "NOT_RUN", "gguf_conversion": "NOT_RUN",
    })
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-assistant-sft-v12.56",
        "review": {"reviewed_by": "Codex, manual preparation under project owner instructions",
                   "reviewed_on": date.today().isoformat(),
                   "decision_evidence": "The user requested half-hour defaults in the second half of the day, explicit mappings for every hour, and event start/end examples, continuing the proposed preparation."},
        "artifacts": artifacts,
        "records": [{"source_type": "internal_authored",
                     "source_reference": "workspace:docs/calendar_sft_v12_55/provenance.json; workspace:docs/calendar_assistant_v12_56_intervals_manual.json",
                     "rights_evidence": "V12.55 sources remain intact. New fictional conversations and six complete replacement expected answers were individually authored as literals for the project; no external material, model predictions or personal data was imported.",
                     "permits_model_training": True, "permits_derivative_weight_distribution": True, "contains_personal_data": False}],
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(TOTAL_ROWS))


if __name__ == "__main__":
    main()
