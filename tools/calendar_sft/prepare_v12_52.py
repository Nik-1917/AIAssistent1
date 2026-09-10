"""Check and serialize the individually handwritten V12.52 minute expressions.

The input contains complete literal conversations. This script never generates
sentences, permutations or target answers and never changes inherited bytes.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import date, timedelta
import json
from pathlib import Path
import re

from dataset_contract import (
    RUNTIME_SYSTEM_RE, file_sha256, load_jsonl, message_signature,
    normalize_record, normalized_user_prompt,
)
from dataset_provenance import verify_dataset_provenance
from prepare_v12_5 import ORDINALS, read_clock_phrase
from prepare_v12_51 import DURATIONS, WEEKDAYS, read_json, write_json

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_51"
OUTPUT = ROOT / "docs/calendar_sft_v12_52"
MANUAL = ROOT / "docs/calendar_assistant_v12_52_minutes_of_hour_manual.json"
SOURCE_HASHES = {
    "train": "61bc4089ccd989c754f5a6c8b66b018882f2092cfe3e2b60233e6f39a96ff261",
    "validation": "5cc25372d2660df0d98ceb9f2560039cad9deeac8f76ef9d151c495f95438fa9",
    "holdout": "a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19",
    "calendar_holdout_v12_2": "504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade",
    "regression_holdout": "86f072490ca838989bad172ecd269b0f6d76fe048a9a15cd2ce807245b1c7ef2",
}
MINUTES = {
    "пять": 5, "десять": 10, "пятнадцать": 15, "двадцать": 20,
    "двадцать пять": 25, "тридцать": 30, "тридцать пять": 35,
    "сорок": 40, "сорок пять": 45, "пятьдесят": 50, "пятьдесят пять": 55,
}
CARDINALS = ("час", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать")
MINUTE_PATTERN = "|".join(sorted(MINUTES, key=len, reverse=True))
MINUTES_OF_HOUR_RE = re.compile(
    r"\b(?P<minutes>" + MINUTE_PATTERN + r") минут (?P<hour>"
    + "|".join(ORDINALS) + r") (?P<period>утра|дня|вечера|ночи)\b"
)
BEFORE_QUARTER_RE = re.compile(
    r"без четверти (?P<hour>" + "|".join(CARDINALS) + r") (?P<period>утра|дня|вечера|ночи)"
)


def normalized_words(text: str) -> str:
    return text.casefold().replace("ё", "е")


def next_hour_24(named_hour: int, period: str) -> int:
    return named_hour % 12 + (12 if period in {"дня", "вечера"} else 0)


def read_minutes_of_hour(phrase: str) -> tuple[int, int, str, str]:
    """Interpret an explicit phrase; return named hour, minutes, period, HH:MM."""
    match = MINUTES_OF_HOUR_RE.fullmatch(normalized_words(phrase))
    if match is None:
        raise ValueError(f"unrecognized minutes-of-hour phrase: {phrase}")
    named_hour = ORDINALS.index(match["hour"]) + 1
    minutes = MINUTES[match["minutes"]]
    hour = (next_hour_24(named_hour, match["period"]) - 1) % 24
    return named_hour, minutes, match["period"], f"{hour:02d}:{minutes:02d}"


def read_reply_clock(phrase: str, minute: int) -> str:
    """Check that the requested reply idiom and its numerical value agree."""
    phrase = normalized_words(phrase)
    if minute in (15, 30):
        form, _, clock = read_clock_phrase(phrase)
        if form != ("quarter" if minute == 15 else "polovina"):
            raise ValueError("wrong quarter or half reply form")
        return clock
    if minute == 45:
        match = BEFORE_QUARTER_RE.fullmatch(phrase)
        if match is None:
            raise ValueError("expected a before-quarter reply")
        named_hour = CARDINALS.index(match["hour"]) + 1
        hour = (next_hour_24(named_hour, match["period"]) - 1) % 24
        return f"{hour:02d}:45"
    if not phrase.startswith("в "):
        raise ValueError("expected an exact spoken minute reply")
    return read_minutes_of_hour(phrase[2:])[3]


def literal_row(example: dict) -> dict:
    return {
        "case_id": example["id"], "category": "v12_52_minutes_of_hour",
        "contract_version": "v12.5",
        "messages": [
            {"role": "system", "content": example["system"]},
            {"role": "user", "content": example["user"]},
            {"role": "assistant", "content": json.dumps(example["assistant"], ensure_ascii=False, separators=(",", ":"))},
        ],
    }


def validate_example(example: dict) -> dict:
    label, split = example["id"], example["split"]
    named_hour, minute, period = example["next_hour"], example["minute"], example["period"]
    if (split not in {"train", "validation"} or type(named_hour) is not int
            or not 1 <= named_hour <= 12 or type(minute) is not int
            or minute not in MINUTES.values() or period not in {"утра", "дня", "вечера", "ночи"}):
        raise ValueError(f"{label}: invalid split, hour, minute or period")
    prefix = "V1252T" if split == "train" else "V1252E"
    if label != f"{prefix}{named_hour:02d}{minute:02d}":
        raise ValueError(f"{label}: incorrect ID")
    row = literal_row(example)
    normalized = normalize_record(row, label)
    if normalized["messages"] != row["messages"]:
        raise ValueError(f"{label}: normalization would change a literal message")
    user, response = normalized_words(example["user"]), example["assistant"]
    phrases = list(MINUTES_OF_HOUR_RE.finditer(user))
    if len(phrases) != 1 or user[max(0, phrases[0].start() - 2):phrases[0].start()] != "в ":
        raise ValueError(f"{label}: expected one explicit clock construction")
    parsed = read_minutes_of_hour(phrases[0][0])
    if parsed[:3] != (named_hour, minute, period):
        raise ValueError(f"{label}: phrase contradicts its metadata")
    clock = parsed[3]
    system = RUNTIME_SYSTEM_RE.fullmatch(example["system"])
    today = date.fromisoformat(system["date"])
    if WEEKDAYS[today.weekday()] != system["weekday"]:
        raise ValueError(f"{label}: wrong system weekday")
    if type(example["days"]) is not int or example["days"] != 1 or not re.search(r"\bзавтра\b", user):
        raise ValueError(f"{label}: expected an explicit tomorrow date")
    params = response["params"]
    if response["intent"] != "calendar_add" or set(params) != {"title", "starts_at", "duration_min"}:
        raise ValueError(f"{label}: unexpected command fields")
    if params["starts_at"] != f"{today + timedelta(days=1)}T{clock}":
        raise ValueError(f"{label}: wrong date or clock time")
    duration = re.search(r"\bна ([^.]+)\.$", user)
    if (duration is None or type(example["duration_min"]) is not int
            or DURATIONS.get(duration[1]) != example["duration_min"]
            or params["duration_min"] != example["duration_min"]):
        raise ValueError(f"{label}: wrong explicit duration")
    reply = response["reply"]
    if not reply.startswith(params["title"] + " завтра, "):
        raise ValueError(f"{label}: reply title or date disagrees with parameters")
    spoken = re.search(r", ([^,]+), на ([^.]+)\.$", reply)
    if (spoken is None or read_reply_clock(spoken[1], minute) != clock
            or DURATIONS.get(normalized_words(spoken[2])) != example["duration_min"]):
        raise ValueError(f"{label}: reply time or duration disagrees with parameters")
    return normalized


def validate_manual(manual: dict) -> dict[str, list[dict]]:
    if manual["version"] != "v12.52" or manual["contract_version"] != "v12.5":
        raise ValueError("expected dataset v12.52 with contract v12.5")
    output = {"train": [], "validation": []}
    counts, halves, ids, train_phrases, validation_phrases = Counter(), Counter(), set(), set(), set()
    for example in manual["examples"]:
        row = validate_example(example)
        split = example["split"]
        if example["id"] in ids:
            raise ValueError("duplicate manual ID")
        ids.add(example["id"])
        output[split].append(row)
        counts[split, example["next_hour"], example["minute"]] += 1
        clock = example["assistant"]["params"]["starts_at"].split("T")[1]
        halves[split, int(clock[:2]) < 12] += 1
        key = example["next_hour"], example["minute"], example["period"]
        (train_phrases if split == "train" else validation_phrases).add(key)
    expected = Counter({
        (split, hour, minute): 1
        for split, minutes in (("train", range(5, 60, 5)), ("validation", (5, 30, 55)))
        for hour in range(1, 13) for minute in minutes
    })
    if counts != expected:
        raise ValueError("expected all 132 train combinations and 36 validation combinations")
    if halves != Counter({("train", True): 66, ("train", False): 66,
                          ("validation", True): 18, ("validation", False): 18}):
        raise ValueError("unbalanced halves of the day")
    if train_phrases & validation_phrases:
        raise ValueError("validation must use the other half of the day for each checked phrase")
    return output


def validate_inputs() -> tuple[dict[str, list[dict]], dict[str, list[dict]]]:
    for split, digest in SOURCE_HASHES.items():
        path = SOURCE / f"{split}.jsonl"
        if file_sha256(path) != digest or not path.read_bytes().endswith(b"\n"):
            raise ValueError(f"{split}: original V12.51 changed or lacks final newline")
    verify_dataset_provenance(SOURCE / "provenance.json", SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    added = validate_manual(read_json(MANUAL))
    outputs = {split: load_jsonl(SOURCE / f"{split}.jsonl") + added.get(split, []) for split in SOURCE_HASHES}
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
        raise ValueError("V12.52 already exists; refusing to overwrite")
    OUTPUT.mkdir()
    for split in outputs:
        original = (SOURCE / f"{split}.jsonl").read_bytes()
        suffix = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n"
                         for row in added.get(split, [])).encode("utf-8")
        (OUTPUT / f"{split}.jsonl").write_bytes(original + suffix)
    artifacts = {split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")} for split in outputs}
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.52", "base_version": "v12.51", "new_contract": "v12.5",
        "source_sha256": SOURCE_HASHES, "manual_sha256": file_sha256(MANUAL),
        "new_counts": {split: len(rows) for split, rows in added.items()},
        "new_quota_per_named_hour": {"train": 11, "validation": 3},
        "total_rows": counts, "artifacts": artifacts,
        "inherited_bytes": "UNCHANGED", "holdouts": "UNCHANGED",
        "training": "NOT_RUN", "gguf_conversion": "NOT_RUN", "model_evaluation": "NOT_RUN",
    })
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-assistant-sft-v12.52",
        "review": {
            "reviewed_by": "Codex, preparation audit under project owner instructions",
            "reviewed_on": date.today().isoformat(),
            "decision_evidence": "The user requested minute-of-hour expressions from five minutes of the first hour through fifty-five minutes of the twelfth hour, in five-minute increments, and named the addition V12.52. This register covers preparation, not a training run.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_v12_51/provenance.json; workspace:docs/calendar_assistant_v12_52_minutes_of_hour_manual.json",
            "rights_evidence": "Verified V12.51 rows are copied unchanged. The 168 new fictional examples were individually authored as complete literal requests and responses for this project. No external material, inference predictions or real personal data was imported.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True,
            "contains_personal_data": False,
        }],
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
