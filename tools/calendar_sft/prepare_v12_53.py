"""Validate and append literal V12.53 clock conversations without generating text."""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from datetime import date, timedelta
import json
from pathlib import Path
import re

from dataset_contract import (
    RUNTIME_SYSTEM_RE, file_sha256, load_jsonl, message_signature,
    normalize_record, normalized_user_prompt,
)
from dataset_provenance import verify_dataset_provenance
from prepare_v12_51 import DURATIONS, HOUR_WORDS, WEEKDAYS, hour_unit, read_json, write_json
from prepare_v12_5 import ORDINALS
from prepare_v12_52 import CARDINALS, MINUTES, normalized_words

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_52"
OUTPUT = ROOT / "docs/calendar_sft_v12_53"
MANUAL = ROOT / "docs/calendar_assistant_v12_53_compact_clock_manual.json"
REPLY_EDITS = ROOT / "docs/calendar_assistant_v12_53_reply_edits_manual.json"
SOURCE_HASHES = {
    "train": "beacf682dec8b08330c03bb9a205b617b5f109349830cd9cc46ba772ad7033d2",
    "validation": "74e1d422e08d7868279106ef435d7980550f91c8e0215ab77f1b40c3f517fe01",
    "holdout": "a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19",
    "calendar_holdout_v12_2": "504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade",
    "regression_holdout": "86f072490ca838989bad172ecd269b0f6d76fe048a9a15cd2ce807245b1c7ef2",
}
ID_RE = re.compile(r"V1253(?P<split>T|E)(?P<hour>\d{2})(?P<minute>\d{2})")
HOUR_PATTERN = "|".join(sorted((*HOUR_WORDS, "час"), key=len, reverse=True))
MINUTE_PATTERN = "|".join(sorted(MINUTES, key=len, reverse=True))
COMPACT_PATTERN = (
    r"(?P<hour>" + HOUR_PATTERN + r")(?: (?P<unit>часов|часа|час))? "
    r"(?P<minute>" + MINUTE_PATTERN + r")(?P<minutes> минут)?"
)
COMPACT_RE = re.compile(COMPACT_PATTERN)
USER_CLOCK_RE = re.compile(
    r"\bв (?P<phrase>" + COMPACT_PATTERN + r"|ноль часов)"
    r"(?= (?:на|завтра|добавь|запиши|поставь)\b|[,.!?]|$)"
)
DURATION_RE = re.compile(
    r"\bна (" + "|".join(re.escape(phrase) for phrase in sorted(DURATIONS, key=len, reverse=True)) + r")\.$"
)
ORDINAL_PATTERN = "|".join(ORDINALS)
CARDINAL_PATTERN = "|".join(sorted(CARDINALS, key=len, reverse=True))
DAYPART_RE = re.compile(r"\b(?:утра|дня|вечера|ночи)\b")
CLOCK_DAYPART_RE = re.compile(
    r"\b(?:час(?:а|ов)?|минут(?:у|ы)?|"
    r"(?:четверть |половин[аеу] |пол[- ]?|минут )(?:" + ORDINAL_PATTERN + r")|"
    r"без четверти (?:" + CARDINAL_PATTERN + r")) (?:утра|дня|вечера|ночи)\b"
)


def has_clock_daypart(response: dict) -> bool:
    """Audit clock speech, excluding exact title copies and date/day intervals."""
    reply = response["reply"]
    params = response["params"]
    for title in (params.get("title"), params.get("changes", {}).get("title")):
        if isinstance(title, str) and title:
            reply = reply.replace(title, "")
    return CLOCK_DAYPART_RE.search(normalized_words(reply)) is not None


def read_spoken_clock(phrase: str, minute: int) -> tuple[int, int]:
    """Validate the reply's 12-hour clock; the input/params carry its daypart."""
    phrase = normalized_words(phrase)
    if minute == 0 and phrase == "в ноль часов":
        return 0, 0
    if minute in (15, 30):
        prefix = "в четверть " if minute == 15 else "в половине "
        if phrase.startswith(prefix) and phrase[len(prefix):] in ORDINALS:
            return ORDINALS.index(phrase[len(prefix):]) % 12, minute
    elif minute == 45:
        prefix = "без четверти "
        if phrase.startswith(prefix) and phrase[len(prefix):] in CARDINALS:
            return CARDINALS.index(phrase[len(prefix):]) % 12, minute
    else:
        match = re.fullmatch(r"в (" + MINUTE_PATTERN + r") минут (" + ORDINAL_PATTERN + r")", phrase)
        if match and MINUTES[match[1]] == minute:
            return ORDINALS.index(match[2]) % 12, minute
    raise ValueError(f"invalid reply clock or daypart: {phrase}")


def apply_reply_edits(sources: dict[str, list[dict]], edits: dict) -> dict[str, list[dict]]:
    """Copy complete literal replies from the manual register; never generate text."""
    if edits["version"] != "v12.53":
        raise ValueError("wrong reply-edit version")
    by_id, by_reply = edits["by_case_id"], edits["by_reply"]
    seen_ids, seen_replies = set(), set()
    outputs = deepcopy(sources)
    for split, rows in outputs.items():
        for index, row in enumerate(rows):
            response = json.loads(row["messages"][-1]["content"])
            original = response["reply"]
            case_id = row.get("case_id")
            if case_id in by_id and original in by_reply:
                raise ValueError("overlapping reply-edit selectors")
            if case_id in by_id:
                response["reply"] = by_id[case_id]
                seen_ids.add(case_id)
            elif original in by_reply:
                response["reply"] = by_reply[original]
                seen_replies.add(original)
            if response["reply"] != original:
                # Check the handwritten edit is only one clock qualifier deletion.
                # This expression verifies the literal; it never supplies the reply.
                matches = list(DAYPART_RE.finditer(original))
                allowed = {
                    original[:m.start() - 1] + original[m.end():]
                    for m in matches if m.start() and original[m.start() - 1] == " "
                }
                if response["reply"] not in allowed:
                    raise ValueError(f"{split}:{index + 1}: edit changes more than a daypart")
                row["messages"][-1]["content"] = json.dumps(response, ensure_ascii=False, separators=(",", ":"))
                normalize_record(row, f"{split}:{index + 1}")
            if has_clock_daypart(response):
                raise ValueError(f"{split}:{index + 1}: clock daypart still present in reply")
    if seen_ids != set(by_id) or seen_replies != set(by_reply):
        raise ValueError("unused manual reply edit")
    return outputs


def declared_clock(case_id: str) -> tuple[str, int, int]:
    match = ID_RE.fullmatch(case_id)
    if match is None:
        raise ValueError(f"invalid V12.53 case ID: {case_id}")
    hour, minute = int(match["hour"]), int(match["minute"])
    if hour > 23 or (minute not in MINUTES.values() and (hour, minute) != (0, 0)):
        raise ValueError(f"invalid declared clock: {case_id}")
    return ("train" if match["split"] == "T" else "validation"), hour, minute


def read_compact_clock(phrase: str) -> tuple[int, int]:
    """Read a cardinal hour followed by minutes, without ordinal-hour subtraction."""
    phrase = normalized_words(phrase)
    if phrase == "ноль часов":
        return 0, 0
    match = COMPACT_RE.fullmatch(phrase)
    if match is None:
        raise ValueError(f"unrecognized compact clock: {phrase}")
    alias = match["hour"] == "час"
    hour = 1 if alias else HOUR_WORDS.index(match["hour"])
    if match["unit"] is not None and (alias or match["unit"] != hour_unit(hour)):
        raise ValueError(f"wrong or repeated hour unit: {phrase}")
    if match["minutes"] and not (alias or match["unit"]):
        raise ValueError(f"minute noun without an explicit hour unit is ambiguous: {phrase}")
    return hour, MINUTES[match["minute"]]


def literal_row(example: dict) -> dict:
    _, _, minute = declared_clock(example["id"])
    return {
        "case_id": example["id"],
        "category": "v12_53_midnight" if minute == 0 else "v12_53_compact_clock",
        "contract_version": "v12.5",
        "messages": [
            {"role": "system", "content": example["system"]},
            {"role": "user", "content": example["user"]},
            {"role": "assistant", "content": json.dumps(example["assistant"], ensure_ascii=False, separators=(",", ":"))},
        ],
    }


def word_order(example: dict) -> str:
    """Locate event, day and clock, independent of the command verb position."""
    user = normalized_words(example["user"])
    clock = USER_CLOCK_RE.search(user)
    day = re.search(r"\bзавтра\b", user)
    duration = DURATION_RE.search(user)
    verb = re.search(r"\b(?:запиши|поставь|добавь)\b", user)
    if any(match is None for match in (clock, day, duration, verb)):
        raise ValueError(f"{example['id']}: cannot audit word order")
    remaining = list(user)
    for match in (clock, day, duration, verb):
        remaining[match.start():match.end()] = " " * (match.end() - match.start())
    event = re.search(r"[а-яё]", "".join(remaining))
    if event is None:
        raise ValueError(f"{example['id']}: missing event wording")
    return "".join(label for _, label in sorted(
        ((event.start(), "E"), (day.start(), "D"), (clock.start(), "T"))
    ))


def validate_example(example: dict) -> dict:
    label = example["id"]
    split, hour, minute = declared_clock(label)
    row = literal_row(example)
    normalized = normalize_record(row, label)
    if normalized["messages"] != row["messages"]:
        raise ValueError(f"{label}: normalization would change a literal message")
    user, response = normalized_words(example["user"]), example["assistant"]
    phrases = list(USER_CLOCK_RE.finditer(user))
    if len(phrases) != 1:
        raise ValueError(f"{label}: expected one explicit compact clock")
    phrase = phrases[0]["phrase"]
    if read_compact_clock(phrase) != (hour, minute):
        raise ValueError(f"{label}: phrase contradicts the clock declared in its ID")
    if minute:
        match = COMPACT_RE.fullmatch(phrase)
        if split == "train" and (match["unit"] or match["minutes"]):
            raise ValueError(f"{label}: train must use the compact form")
        if split == "validation" and not (match["unit"] and match["minutes"]):
            raise ValueError(f"{label}: validation must use explicit hour and minute units")
    system = RUNTIME_SYSTEM_RE.fullmatch(example["system"])
    today = date.fromisoformat(system["date"])
    if WEEKDAYS[today.weekday()] != system["weekday"]:
        raise ValueError(f"{label}: wrong system weekday")
    if not re.search(r"\bзавтра\b", user):
        raise ValueError(f"{label}: expected an explicit tomorrow date")
    params = response["params"]
    if response["intent"] != "calendar_add" or set(params) != {"title", "starts_at", "duration_min"}:
        raise ValueError(f"{label}: unexpected command fields")
    clock = f"{hour:02d}:{minute:02d}"
    if params["starts_at"] != f"{today + timedelta(days=1)}T{clock}":
        raise ValueError(f"{label}: wrong date or clock time")
    duration = DURATION_RE.search(user)
    if duration is None or DURATIONS.get(duration[1]) != params["duration_min"]:
        raise ValueError(f"{label}: wrong explicit duration")
    reply = response["reply"]
    if not reply.startswith(params["title"] + " завтра, "):
        raise ValueError(f"{label}: reply title or date disagrees with parameters")
    spoken = re.search(r", ([^,]+), на ([^.]+)\.$", reply)
    if spoken is None or DURATIONS.get(normalized_words(spoken[2])) != params["duration_min"]:
        raise ValueError(f"{label}: reply duration disagrees with parameters")
    if read_spoken_clock(spoken[1], minute) != (hour % 12, minute):
        raise ValueError(f"{label}: reply time disagrees with parameters")
    return normalized


def validate_manual(manual: dict) -> dict[str, list[dict]]:
    if manual["version"] != "v12.53" or manual["contract_version"] != "v12.5":
        raise ValueError("expected dataset v12.53 with contract v12.5")
    output, counts, ids = {"train": [], "validation": []}, Counter(), set()
    orders = {"train": Counter(), "validation": Counter()}
    for example in manual["examples"]:
        row = validate_example(example)
        split, hour, minute = declared_clock(example["id"])
        if example["id"] in ids:
            raise ValueError("duplicate manual ID")
        ids.add(example["id"])
        output[split].append(row)
        counts[split, hour, minute] += 1
        if minute:
            orders[split][word_order(example)] += 1
    expected = Counter({
        (split, hour, minute): 1
        for split, minutes in (("train", range(5, 60, 5)), ("validation", (5, 30, 55)))
        for hour in range(24) for minute in minutes
    })
    expected.update({("train", 0, 0): 1, ("validation", 0, 0): 1})
    if counts != expected:
        raise ValueError("expected 264 train clocks, 72 validation clocks and one midnight per split")
    for split, quota in (("train", 44), ("validation", 12)):
        if orders[split] != Counter({order: quota for order in ("EDT", "ETD", "DET", "DTE", "TED", "TDE")}):
            raise ValueError(f"{split}: six word orders are not balanced")
    return output


def validate_inputs() -> tuple[dict[str, list[dict]], dict[str, list[dict]]]:
    for split, digest in SOURCE_HASHES.items():
        path = SOURCE / f"{split}.jsonl"
        if file_sha256(path) != digest or not path.read_bytes().endswith(b"\n"):
            raise ValueError(f"{split}: original V12.52 changed or lacks final newline")
    verify_dataset_provenance(SOURCE / "provenance.json", SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    added = validate_manual(read_json(MANUAL))
    sources = {split: load_jsonl(SOURCE / f"{split}.jsonl") for split in SOURCE_HASHES}
    inherited = apply_reply_edits(sources, read_json(REPLY_EDITS))
    outputs = {split: inherited[split] + added.get(split, []) for split in SOURCE_HASHES}
    signatures, ids = set(), set()
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
        others = {normalized_user_prompt(row) for name, values in outputs.items() if name != split for row in values}
        if others & {normalized_user_prompt(row) for row in rows}:
            raise ValueError(f"{split}: new wording overlaps another split")
    fitting = {normalized_user_prompt(row) for split in added for row in outputs[split]}
    for split in SOURCE_HASHES.keys() - added.keys():
        if fitting & {normalized_user_prompt(row) for row in outputs[split]}:
            raise ValueError(f"{split}: holdout wording leaked into fitting data")
    return added, outputs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    added, outputs = validate_inputs()
    counts = {split: len(rows) for split, rows in outputs.items()}
    if args.check_only:
        print(json.dumps(counts))
        return
    if OUTPUT.exists():
        raise ValueError("V12.53 already exists; refusing to overwrite")
    OUTPUT.mkdir()
    changed_counts = {}
    for split, rows in outputs.items():
        original_lines = (SOURCE / f"{split}.jsonl").read_bytes().splitlines(keepends=True)
        changed_counts[split] = 0
        lines = []
        for index, row in enumerate(rows):
            if index < len(original_lines):
                original = json.loads(original_lines[index])
                if original == row:
                    lines.append(original_lines[index])
                    continue
                old_response = json.loads(original["messages"][-1]["content"])
                new_response = json.loads(row["messages"][-1]["content"])
                old_response["reply"] = new_response["reply"]
                original["messages"][-1]["content"] = json.dumps(old_response, ensure_ascii=False, separators=(",", ":"))
                if original != row:
                    raise ValueError("inherited fields other than reply changed")
                changed_counts[split] += 1
            lines.append((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        (OUTPUT / f"{split}.jsonl").write_bytes(b"".join(lines))
    artifacts = {split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")} for split in outputs}
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.53", "base_version": "v12.52", "new_contract": "v12.5",
        "source_sha256": SOURCE_HASHES, "manual_sha256": file_sha256(MANUAL),
        "reply_edits_manual_sha256": file_sha256(REPLY_EDITS),
        "inherited_reply_edits": changed_counts,
        "new_counts": {split: len(rows) for split, rows in added.items()},
        "new_grid_per_hour": {"train": 11, "validation": 3},
        "additional_midnight_per_split": 1,
        "new_grid_each_word_order": {"train": 44, "validation": 12},
        "total_rows": counts, "artifacts": artifacts,
        "archived_sources": "UNCHANGED",
        "inherited_fields_except_reply": "UNCHANGED",
        "holdout_prompts_and_params": "UNCHANGED",
        "reply_clock_dayparts": "OMITTED",
        "training": "NOT_RUN", "gguf_conversion": "NOT_RUN", "model_evaluation": "NOT_RUN",
    })
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-assistant-sft-v12.53",
        "review": {
            "reviewed_by": "Codex, preparation audit under project owner instructions",
            "reviewed_on": date.today().isoformat(),
            "decision_evidence": "The user approved the preceding manual preparation and requested short hour-minute forms from one oh-five through twenty-three fifty-five and midnight, naming this addition V12.53. This register covers preparation, not a training run.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_v12_52/provenance.json; workspace:docs/calendar_assistant_v12_53_compact_clock_manual.json; workspace:docs/calendar_assistant_v12_53_reply_edits_manual.json",
            "rights_evidence": "Verified V12.52 rows retain their prompts, intents and parameters. A manual register supplies complete replacement replies omitting clock dayparts as requested by the user. The 338 new fictional examples were individually authored as complete literal requests and responses for this project. No external material, inference predictions or real personal data was imported.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True,
            "contains_personal_data": False,
        }],
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
