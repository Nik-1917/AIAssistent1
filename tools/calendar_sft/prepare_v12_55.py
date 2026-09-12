"""Validate and append handwritten half-hour conversations to frozen V12.54.

The clock reader below audits this finite register; it is not an app parser.
Messages and answers are copied as literals, never generated or repaired.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import date, datetime, timedelta
import json
from pathlib import Path
import re

from dataset_contract import (
    RUNTIME_SYSTEM_RE, file_sha256, load_jsonl, message_signature,
    normalize_record, normalized_user_prompt,
)
from dataset_provenance import verify_dataset_provenance
from prepare_v12_5 import ORDINALS
from prepare_v12_51 import DURATIONS, HOUR_WORDS, WEEKDAYS, hour_unit, read_json, write_json
from prepare_v12_52 import normalized_words
from prepare_v12_53 import read_spoken_clock

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_54"
OUTPUT = ROOT / "docs/calendar_sft_v12_55"
MANUAL = ROOT / "docs/calendar_assistant_v12_55_half_hour_manual.json"
SOURCE_HASHES = {
    "train": "51c0b4d336aabc99b5d6f0063d78aace804958b480984b47c29c73e4ab873414",
    "validation": "832e2b83e21c28175d90c6e63301ebfed1efc6e80761b35af8a4e827ac7a2f4a",
    "holdout": "a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19",
    "calendar_holdout_v12_2": "504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade",
    "regression_holdout": "b2a7bdbc93a6f59769f0ab0a9108dc707e1bc7a494d00cbd5c8bb2fbfa7d1e34",
}
SPLITS = {"T": "train", "E": "validation", "H": "half_hour_holdout"}
NEW_COUNTS = {"train": 144, "validation": 24, "half_hour_holdout": 36}
TOTAL_ROWS = {"train": 2545, "validation": 744, "holdout": 36,
              "calendar_holdout_v12_2": 8, "regression_holdout": 63, "half_hour_holdout": 36}
ID_RE = re.compile(r"V1255(?P<split>[TEH])(?P<hour>\d{2})(?P<form>0[1-6])")
CONTROL_RE = re.compile(r"V1255HC(?P<number>0[1-9]|1[0-2])")
CLOCK_RE = re.compile(
    r"\b(?P<in>в )?(?P<surface>половина |половине |пол[- ]?)"
    r"(?P<ordinal>" + "|".join(ORDINALS) + r")\b"
    r"(?: (?P<part>утра|дня|вечера|ночи)\b)?"
)
CONTEXT_RE = re.compile(r"\b(?:утром|днем|вечером|ночью)\b")
# Only explicit dayparts occurring in the finite register are resolved. These
# overlapping intervals give at most one candidate for each ordinal hour.
PART_HOURS = {
    "утра": frozenset(range(12)), "утром": frozenset(range(12)),
    "дня": frozenset(range(11, 18)), "днем": frozenset(range(11, 18)),
    "вечера": frozenset(range(17, 24)), "вечером": frozenset(range(17, 24)),
    "ночи": frozenset((*range(6), *range(20, 24))),
    "ночью": frozenset((*range(6), *range(20, 24))),
}
LOCAL_DURATIONS = {**DURATIONS, "пол часа": 30}
DURATION_RE = re.compile(
    r"\bна (" + "|".join(re.escape(p) for p in sorted(LOCAL_DURATIONS, key=len, reverse=True)) + r")\.$"
)
OFFSET_RE = re.compile(r"\bчерез (полчаса|пол часа|половину часа)\b")
NUMERIC_RE = re.compile(r"\bв ([0-2]\d:[0-5]\d)\b")
ORDERS = ("EDT", "ETD", "DET", "DTE", "TED", "TDE")


def declaration(case_id: str) -> tuple[str, int | None, int]:
    match = ID_RE.fullmatch(case_id)
    if match and int(match["hour"]) < 24:
        return SPLITS[match["split"]], int(match["hour"]), int(match["form"])
    control = CONTROL_RE.fullmatch(case_id)
    if control:
        return "half_hour_holdout", None, int(control["number"])
    raise ValueError(f"invalid V12.55 case ID: {case_id}")


def read_half_clock(user: str) -> tuple[int, int | None, re.Match]:
    """Check one half-hour assertion; absent daypart remains unresolved."""
    user = normalized_words(user)
    matches = list(CLOCK_RE.finditer(user))
    if len(matches) != 1:
        raise ValueError("expected exactly one half-hour clock")
    match = matches[0]
    surface, has_in = match["surface"], bool(match["in"])
    if surface == "половина " and not has_in:
        form = 5
    elif surface == "половине " and has_in:
        form = 6
    elif surface in ("пол", "пол-", "пол "):
        form = (3 if surface == "пол " else 1) + int(has_in)
        if surface == "пол-" and match["ordinal"] != "одиннадцатого":
            raise ValueError("unexpected hyphen spelling")
    else:
        raise ValueError("unexpected half-hour preposition or case")
    parts = ([match["part"]] if match["part"] else []) + CONTEXT_RE.findall(user)
    base_hour = ORDINALS.index(match["ordinal"])
    if not parts:
        return form, None, match
    candidates = {base_hour, base_hour + 12}
    for part in parts:
        candidates &= PART_HOURS[part]
    if len(candidates) != 1:
        raise ValueError("conflicting or unsupported explicit dayparts")
    return form, candidates.pop(), match


def word_order(example: dict) -> str:
    user = normalized_words(example["user"])
    _, _, clock = read_half_clock(user)
    day = re.search(r"\bзавтра\b", user)
    duration = DURATION_RE.search(user)
    verb = re.search(r"\b(?:запиши|поставь|добавь)\b", user)
    if any(m is None for m in (day, duration, verb)):
        raise ValueError(f"{example['id']}: cannot audit event/day/time order")
    remaining = list(user)
    for match in (clock, day, duration, verb, *CONTEXT_RE.finditer(user)):
        remaining[match.start():match.end()] = " " * (match.end() - match.start())
    event = re.search(r"[а-яё]", "".join(remaining))
    if event is None:
        raise ValueError("missing event wording")
    return "".join(label for _, label in sorted(
        ((event.start(), "E"), (day.start(), "D"), (clock.start(), "T"))
    ))


def literal_row(example: dict) -> dict:
    _, hour, number = declaration(example["id"])
    kind = "clock" if hour is not None else (
        "ambiguous" if number <= 6 else "duration" if number <= 9 else "offset"
    )
    return {
        "case_id": example["id"], "category": "v12_55_half_hour_" + kind,
        "contract_version": "v12.5",
        "messages": [
            {"role": "system", "content": example["system"]},
            {"role": "user", "content": example["user"]},
            {"role": "assistant", "content": json.dumps(example["assistant"], ensure_ascii=False, separators=(",", ":"))},
        ],
    }


def reply_clock(phrase: str, minute: int) -> tuple[int, int]:
    if minute:
        return read_spoken_clock(phrase, minute)
    match = re.fullmatch(r"в (.+) (час|часа|часов)", normalized_words(phrase))
    if match and match[1] in HOUR_WORDS:
        hour = HOUR_WORDS.index(match[1])
        if hour <= 12 and match[2] == hour_unit(hour):
            return hour % 12, 0
    raise ValueError("invalid whole-hour reply clock")


def validate_example(example: dict) -> dict:
    label = example["id"]
    if set(example) != {"id", "system", "user", "assistant"}:
        raise ValueError(f"{label}: unexpected manual fields")
    _, hour, form_or_control = declaration(label)
    row = literal_row(example)
    normalized = normalize_record(row, label)
    if normalized != row:
        raise ValueError(f"{label}: normalization would change a handwritten record")
    system = RUNTIME_SYSTEM_RE.fullmatch(example["system"])
    today = date.fromisoformat(system["date"])
    if WEEKDAYS[today.weekday()] != system["weekday"] or system["zone"] != "Europe/Samara":
        raise ValueError(f"{label}: wrong system weekday or zone")
    now = datetime.fromisoformat(system["date"] + "T" + system["time"])
    tomorrow = today + timedelta(days=1)
    user, response = normalized_words(example["user"]), example["assistant"]
    params = response["params"]
    ambiguous = hour is None and form_or_control <= 6
    expected_fields = {"title", "duration_min", "date" if ambiguous else "starts_at"}
    if response["intent"] != "calendar_add" or set(params) != expected_fields:
        raise ValueError(f"{label}: unexpected command fields")
    duration = DURATION_RE.search(user)
    if duration is None or LOCAL_DURATIONS[duration[1]] != params["duration_min"]:
        raise ValueError(f"{label}: wrong explicit duration")
    if hour is not None or ambiguous:
        form, resolved_hour, _ = read_half_clock(user)
        if form != form_or_control or resolved_hour != hour:
            raise ValueError(f"{label}: half-hour assertion differs from ID")
    elif form_or_control <= 9:
        if CLOCK_RE.search(user) or params["duration_min"] != 30:
            raise ValueError(f"{label}: duration was confused with clock time")
        matches = list(NUMERIC_RE.finditer(user))
        if len(matches) != 1:
            raise ValueError(f"{label}: expected a numeric clock for duration control")
        expected_start = datetime.fromisoformat(f"{tomorrow}T{matches[0][1]}")
    else:
        if CLOCK_RE.search(user) or len(OFFSET_RE.findall(user)) != 1:
            raise ValueError(f"{label}: expected a half-hour offset")
        expected_start = now + timedelta(minutes=30)
    if ambiguous:
        if params["date"] != tomorrow.isoformat():
            raise ValueError(f"{label}: wrong date for ambiguous clock")
    else:
        if hour is not None:
            expected_start = datetime.fromisoformat(f"{tomorrow}T{hour:02d}:30")
        if params["starts_at"] != expected_start.isoformat(timespec="minutes"):
            raise ValueError(f"{label}: wrong date or clock time")
    if hour is not None or form_or_control <= 9:
        if not re.search(r"\bзавтра\b", user):
            raise ValueError(f"{label}: missing explicit tomorrow date")
    # Match the complete literal reply, preserving title and duration meaning.
    spoken = re.fullmatch(
        re.escape(params["title"] + " завтра, ") + r"(?:(?P<clock>[^,]+), )?на (?P<duration>[^.]+)\.",
        response["reply"],
    )
    if spoken is None or LOCAL_DURATIONS.get(normalized_words(spoken["duration"])) != params["duration_min"]:
        raise ValueError(f"{label}: reply title, date or duration differs from parameters")
    if ambiguous:
        if spoken["clock"] is not None:
            raise ValueError(f"{label}: ambiguous clock must not invent a reply time")
    elif spoken["clock"] is None or reply_clock(spoken["clock"], expected_start.minute) != (
        expected_start.hour % 12, expected_start.minute
    ):
        raise ValueError(f"{label}: reply clock differs from parameters")
    return normalized


def validate_manual(manual: dict) -> dict[str, list[dict]]:
    if manual["version"] != "v12.55" or manual["contract_version"] != "v12.5":
        raise ValueError("expected dataset v12.55 with contract v12.5")
    added = {split: [] for split in NEW_COUNTS}
    ids, grid, controls = set(), Counter(), set()
    orders, form_orders = Counter(), Counter()
    for example in manual["examples"]:
        row = validate_example(example)
        split, hour, form = declaration(example["id"])
        if example["id"] in ids:
            raise ValueError("duplicate manual case ID")
        ids.add(example["id"])
        added[split].append(row)
        if hour is None:
            controls.add(form)
        else:
            grid[split, hour, form] += 1
        if split == "train":
            order = word_order(example)
            orders[hour, order] += 1
            form_orders[form, order] += 1
    expected = Counter({("train", hour, form): 1 for hour in range(24) for form in range(1, 7)})
    expected.update({("validation", hour, hour % 6 + 1): 1 for hour in range(24)})
    expected.update({("half_hour_holdout", hour, (hour + 2) % 6 + 1): 1 for hour in range(24)})
    if grid != expected or controls != set(range(1, 13)):
        raise ValueError("incomplete or unbalanced half-hour grid or controls")
    if orders != Counter({(hour, order): 1 for hour in range(24) for order in ORDERS}):
        raise ValueError("train must cover all six word orders at each hour")
    if form_orders != Counter({(form, order): 4 for form in range(1, 7) for order in ORDERS}):
        raise ValueError("surface form and word order must vary independently")
    if {split: len(rows) for split, rows in added.items()} != NEW_COUNTS:
        raise ValueError("unexpected number of handwritten examples")
    return added


def validate_inputs() -> tuple[dict[str, list[dict]], dict[str, list[dict]]]:
    for split, digest in SOURCE_HASHES.items():
        path = SOURCE / f"{split}.jsonl"
        if file_sha256(path) != digest or not path.read_bytes().endswith(b"\n"):
            raise ValueError(f"{split}: original V12.54 changed or lacks final newline")
    verify_dataset_provenance(SOURCE / "provenance.json", SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    added = validate_manual(read_json(MANUAL))
    outputs = {split: load_jsonl(SOURCE / f"{split}.jsonl") + added.get(split, []) for split in SOURCE_HASHES}
    outputs["half_hour_holdout"] = added["half_hour_holdout"]
    ids, signatures = set(), set()
    prompts = {split: {normalized_user_prompt(row) for row in rows} for split, rows in outputs.items()}
    for split, rows in outputs.items():
        for row in rows:
            signature, case_id = message_signature(row), row.get("case_id")
            if signature in signatures or (case_id is not None and case_id in ids):
                raise ValueError(f"{split}: duplicate context or case ID")
            signatures.add(signature)
            if case_id is not None:
                ids.add(case_id)
            if json.loads(row["messages"][-1]["content"])["intent"] == "note_add":
                raise ValueError(f"{split}: removed intent is present")
    for split, rows in added.items():
        others = set().union(*(values for name, values in prompts.items() if name != split))
        if others & {normalized_user_prompt(row) for row in rows}:
            raise ValueError(f"{split}: new wording overlaps another split")
    fitting = prompts["train"] | prompts["validation"]
    for split in outputs.keys() - {"train", "validation"}:
        if fitting & prompts[split]:
            raise ValueError(f"{split}: holdout wording leaked into fitting data")
    if {split: len(rows) for split, rows in outputs.items()} != TOTAL_ROWS:
        raise ValueError("unexpected final row counts")
    return added, outputs


def serialize_preserving_sources(added: dict[str, list[dict]]) -> dict[str, bytes]:
    result = {split: (SOURCE / f"{split}.jsonl").read_bytes() for split in SOURCE_HASHES}
    for split, rows in added.items():
        suffix = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows)
        result[split] = result.get(split, b"") + suffix.encode("utf-8")
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
        raise ValueError("V12.55 already exists; refusing to overwrite")
    payloads = serialize_preserving_sources(added)
    OUTPUT.mkdir()
    for split, payload in payloads.items():
        (OUTPUT / f"{split}.jsonl").write_bytes(payload)
    artifacts = {split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")} for split in outputs}
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.55", "base_version": "v12.54", "new_contract": "v12.5",
        "source_sha256": SOURCE_HASHES, "manual_sha256": file_sha256(MANUAL),
        "new_counts": NEW_COUNTS, "total_rows": TOTAL_ROWS, "artifacts": artifacts,
        "train_each_surface_form": 24, "train_each_hour": 6,
        "train_each_word_order": 24, "train_each_surface_form_and_word_order": 4,
        "validation_grid": "24 hours, one example per hour, four per surface form",
        "half_hour_holdout_grid": "24 hours plus six ambiguity, three duration and three offset controls",
        "archived_sources": "UNCHANGED", "all_inherited_bytes": "UNCHANGED",
        "runtime_prompt_and_reply_rules": "UNCHANGED",
        "training": "NOT_RUN", "gguf_conversion": "NOT_RUN", "model_evaluation": "NOT_RUN",
    })
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-assistant-sft-v12.55",
        "review": {
            "reviewed_by": "Codex, preparation audit under project owner instructions",
            "reviewed_on": date.today().isoformat(),
            "decision_evidence": "The user requested all half-hour clock variants after reviewing the manual preparation plan and named this addition V12.55. This register records preparation only, not a training run.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_v12_54/provenance.json; workspace:docs/calendar_assistant_v12_55_half_hour_manual.json",
            "rights_evidence": "All verified V12.54 records are retained byte for byte. The 204 new fictional conversations were individually authored as complete literal system, user and assistant messages for this project. No external material, inference predictions or real personal data was imported.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True,
            "contains_personal_data": False,
        }],
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    write_json(OUTPUT / "validation_report.json", {
        "dataset": "v12.55", "status": "PASS", "new_examples": 204,
        "total_rows": sum(TOTAL_ROWS.values()), "artifacts": artifacts,
        "json_contract": "PASS", "declared_clock_and_numeric_parameters": "PASS",
        "dates_and_weekdays": "PASS", "explicit_duration": "PASS", "reply_clock_and_duration": "PASS",
        "grid_and_word_order_balance": "PASS", "new_cross_split_prompt_overlap": 0,
        "inherited_bytes": "UNCHANGED", "new_holdout_excluded_from_fitting": True,
        "model_evaluation": "NOT_RUN",
    })
    print(json.dumps(TOTAL_ROWS))


if __name__ == "__main__":
    main()
