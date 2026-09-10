"""Validate and serialize the 72 literal V12.51 whole-hour examples.

No sentence generation, word permutations, target repair, training or inference.
Every V12.5 source byte is retained, including the three independent holdouts.
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

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_5"
OUTPUT = ROOT / "docs/calendar_sft_v12_51"
MANUAL = ROOT / "docs/calendar_assistant_v12_51_on_hour_manual.json"
SOURCE_HASHES = {
    "train": "39e9570260c74313bbcd916e1c21a2250c49c6ed44812a15db01958433a46517",
    "validation": "6c518d9743c23e461f6ad89f0170615c270a4be11b3e560febf64f7f9dc31bfb",
    "holdout": "a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19",
    "calendar_holdout_v12_2": "504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade",
    "regression_holdout": "86f072490ca838989bad172ecd269b0f6d76fe048a9a15cd2ce807245b1c7ef2",
}
GROUPS = (("train", "compact"), ("train", "expanded"), ("validation", "mixed"))
WEEKDAYS = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
HOUR_WORDS = (
    "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь",
    "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать",
    "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать",
    "девятнадцать", "двадцать", "двадцать один", "двадцать два", "двадцать три",
)
HOUR_PATTERN = "|".join(re.escape(word) for word in sorted(HOUR_WORDS, key=len, reverse=True))
ON_HOUR_RE = re.compile(
    r"(?P<hour>" + HOUR_PATTERN + r"|час)(?: (?P<unit>часов|часа|час))?"
    r" ноль ноль(?P<minutes> минут)?"
)
REPLY_HOUR_RE = re.compile(r"(?P<hour>" + HOUR_PATTERN + r") (?P<unit>часов|часа|час)")
DURATIONS = {
    "десять минут": 10, "пятнадцать минут": 15, "четверть часа": 15,
    "двадцать минут": 20, "двадцать пять минут": 25, "тридцать минут": 30,
    "полчаса": 30, "половину часа": 30, "тридцать пять минут": 35,
    "сорок минут": 40, "сорок пять минут": 45, "час": 60,
}


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def hour_unit(hour: int) -> str:
    if hour in (1, 21):
        return "час"
    if hour in (2, 3, 4, 22, 23):
        return "часа"
    return "часов"


def read_on_hour_phrase(phrase: str) -> int:
    """Read a literal assertion, including the requested 'час ноль ноль' alias."""
    match = ON_HOUR_RE.fullmatch(phrase.casefold())
    if match is None:
        raise ValueError(f"unrecognized whole-hour phrase: {phrase}")
    if match["hour"] == "час":
        if match["unit"] is not None:
            raise ValueError(f"repeated hour unit: {phrase}")
        return 1
    hour = HOUR_WORDS.index(match["hour"])
    if match["unit"] is not None and match["unit"] != hour_unit(hour):
        raise ValueError(f"wrong hour unit: {phrase}")
    return hour


def literal_row(example: dict) -> dict:
    """Wrap existing literal strings; never construct a request or its answer."""
    return {
        "case_id": example["id"],
        "category": f"v12_51_on_hour_{example['form']}",
        "contract_version": "v12.5",
        "messages": [
            {"role": "system", "content": example["system"]},
            {"role": "user", "content": example["user"]},
            {"role": "assistant", "content": json.dumps(example["assistant"], ensure_ascii=False, separators=(",", ":"))},
        ],
    }


def validate_manual(manual: dict) -> dict[str, list[dict]]:
    if manual["version"] != "v12.51" or manual["contract_version"] != "v12.5":
        raise ValueError("expected dataset v12.51 with contract v12.5")
    output = {"train": [], "validation": []}
    counts, ids = Counter(), set()
    for example in manual["examples"]:
        label, split, form, hour = example["id"], example["split"], example["form"], example["hour"]
        if (split, form) not in GROUPS or type(hour) is not int or not 0 <= hour <= 23:
            raise ValueError(f"{label}: invalid group or hour")
        prefix, suffix = ("V1251E", "") if split == "validation" else ("V1251T", "S" if form == "compact" else "F")
        if label != f"{prefix}{hour:02d}{suffix}" or label in ids:
            raise ValueError(f"{label}: invalid or duplicate ID")
        ids.add(label)
        if read_on_hour_phrase(example["phrase"]) != hour:
            raise ValueError(f"{label}: phrase contradicts the asserted hour")
        phrase_match = ON_HOUR_RE.fullmatch(example["phrase"].casefold())
        has_unit = phrase_match["unit"] is not None or phrase_match["hour"] == "час"
        has_minutes = phrase_match["minutes"] is not None
        if (form == "compact" and (has_minutes or (has_unit and phrase_match["hour"] != "час"))
                or form == "expanded" and not (has_unit and has_minutes)
                or form == "mixed" and has_unit == has_minutes):
            raise ValueError(f"{label}: phrase does not match declared form")
        row = literal_row(example)
        normalized = normalize_record(row, label)
        if normalized["messages"] != row["messages"]:
            raise ValueError(f"{label}: normalization would change a literal message")
        user, response = example["user"].casefold(), example["assistant"]
        if not re.search(r"\bв " + re.escape(example["phrase"].casefold()) + r"\b", user):
            raise ValueError(f"{label}: missing literal clock construction")
        system = RUNTIME_SYSTEM_RE.fullmatch(example["system"])
        today = date.fromisoformat(system["date"])
        if WEEKDAYS[today.weekday()] != system["weekday"]:
            raise ValueError(f"{label}: wrong system weekday")
        if type(example["days"]) is not int or example["days"] not in (1, 2):
            raise ValueError(f"{label}: unexpected day offset")
        day_word = {1: "завтра", 2: "послезавтра"}[example["days"]]
        if not re.search(r"\b" + day_word + r"\b", user):
            raise ValueError(f"{label}: day word disagrees with day offset")
        params = response["params"]
        if response["intent"] != "calendar_add" or set(params) != {"title", "starts_at", "duration_min"}:
            raise ValueError(f"{label}: unexpected command fields")
        start = f"{today + timedelta(days=example['days'])}T{hour:02d}:00"
        if params["starts_at"] != start:
            raise ValueError(f"{label}: wrong date or clock time")
        duration = re.search(r"\bна ([^.]+)\.$", user)
        if (duration is None or type(example["duration_min"]) is not int
                or DURATIONS.get(duration[1]) != example["duration_min"]
                or params["duration_min"] != example["duration_min"]):
            raise ValueError(f"{label}: wrong explicit duration")
        reply = response["reply"]
        if not reply.startswith(params["title"] + " " + day_word + ", "):
            raise ValueError(f"{label}: reply title or date disagrees with parameters")
        spoken = re.search(r", в ([^,]+), на ([^.]+)\.$", reply.casefold())
        clock = REPLY_HOUR_RE.fullmatch(spoken[1]) if spoken else None
        if (clock is None or HOUR_WORDS.index(clock["hour"]) != hour
                or clock["unit"] != hour_unit(hour)
                or DURATIONS.get(spoken[2]) != example["duration_min"]):
            raise ValueError(f"{label}: reply time or duration disagrees with parameters")
        output[split].append(normalized)
        counts[split, form, hour] += 1
    expected = Counter({(split, form, hour): 1 for split, form in GROUPS for hour in range(24)})
    if counts != expected:
        raise ValueError("expected exactly one example per hour in each of the three groups")
    return output


def validate_inputs() -> tuple[dict[str, list[dict]], dict[str, list[dict]]]:
    for split, digest in SOURCE_HASHES.items():
        path = SOURCE / f"{split}.jsonl"
        if file_sha256(path) != digest or not path.read_bytes().endswith(b"\n"):
            raise ValueError(f"{split}: original V12.5 changed or lacks final newline")
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


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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
        raise ValueError("V12.51 already exists; refusing to overwrite")
    OUTPUT.mkdir()
    for split in outputs:
        original = (SOURCE / f"{split}.jsonl").read_bytes()
        suffix = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n"
                         for row in added.get(split, [])).encode("utf-8")
        (OUTPUT / f"{split}.jsonl").write_bytes(original + suffix)
    artifacts = {split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")} for split in outputs}
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.51", "base_version": "v12.5", "new_contract": "v12.5",
        "source_sha256": SOURCE_HASHES, "manual_sha256": file_sha256(MANUAL),
        "new_counts": {split: dict(Counter(row["category"] for row in rows)) for split, rows in added.items()},
        "new_quota_per_hour": {"train": 2, "validation": 1},
        "total_rows": counts, "artifacts": artifacts,
        "inherited_bytes": "UNCHANGED", "holdouts": "UNCHANGED",
        "training": "NOT_RUN", "gguf_conversion": "NOT_RUN", "model_evaluation": "NOT_RUN",
    })
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-assistant-sft-v12.51",
        "review": {
            "reviewed_by": "Codex, preparation audit under project owner instructions",
            "reviewed_on": date.today().isoformat(),
            "decision_evidence": "The user requested manually authored whole-hour examples from one through midnight and named the addition V12.51. This register covers preparation, not a training run.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_v12_5/provenance.json; workspace:docs/calendar_assistant_v12_51_on_hour_manual.json",
            "rights_evidence": "Verified V12.5 rows are copied unchanged. The 72 new fictional examples were individually authored as complete literal requests and responses for this project. No external material, inference predictions or real personal data was imported.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True,
            "contains_personal_data": False,
        }],
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
