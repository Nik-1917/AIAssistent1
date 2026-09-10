"""Validate and append twelve literal, manually authored clock examples.

No sentence generation, template expansion, target repair, training or inference.
V14 supplies a count only. V12.4 bytes and its three holdouts are preserved.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import date, timedelta
import json
from pathlib import Path
import re

from dataset_contract import (
    file_sha256, load_jsonl, message_signature, normalize_record,
    normalized_user_prompt, RUNTIME_SYSTEM_RE,
)
from dataset_provenance import verify_dataset_provenance

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_4"
OUTPUT = ROOT / "docs/calendar_sft_v12_5"
MANUAL = ROOT / "docs/calendar_assistant_v12_5_relative_clock_manual.json"
FORMS = ("quarter", "pol", "polovina")
SOURCE_HASHES = {
    "train": "b21987b00d9e4045e47ee7795b8dc1d9ec023121d6c2ac03bed89c64d4d52e8b",
    "validation": "6a8e357e3d0c60de20dc99ebee6b325b70c6ac989b60487b9caeefdcdf843f39",
    "holdout": "a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19",
    "calendar_holdout_v12_2": "504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade",
    "regression_holdout": "86f072490ca838989bad172ecd269b0f6d76fe048a9a15cd2ce807245b1c7ef2",
}
COUNT_SOURCES = {
    "train": ROOT / "docs/calendar_assistant_manual_train_v14.jsonl",
    "validation": ROOT / "docs/calendar_assistant_manual_eval_v14.jsonl",
}
WEEKDAYS = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
ORDINALS = (
    "первого", "второго", "третьего", "четвертого", "пятого", "шестого",
    "седьмого", "восьмого", "девятого", "десятого", "одиннадцатого", "двенадцатого",
)
CLOCK_PHRASE = re.compile(
    r"(?:в )?(?P<form>четверт[ьи]|половин[аеы]|пол[ -]?)(?: )?"
    r"(?P<hour>" + "|".join(ORDINALS) + r") (?P<period>утра|дня|вечера|ночи)"
)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def count_reference(path: Path) -> int:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    return sum(any(re.search(r"\bбез\s+четверти\b", m["content"], re.IGNORECASE)
                   for m in row["messages"] if m["role"] == "user") for row in rows)


def read_clock_phrase(phrase: str) -> tuple[str, int, str]:
    """Check an explicit assertion; never synthesize or change a target."""
    match = CLOCK_PHRASE.fullmatch(phrase.casefold().replace("ё", "е"))
    if not match:
        raise ValueError(f"unrecognized manual clock phrase: {phrase}")
    word = match["form"]
    form = "quarter" if word.startswith("четверт") else "polovina" if word.startswith("половин") else "pol"
    next_hour = (ORDINALS.index(match["hour"]) + 1) % 12
    if match["period"] in {"дня", "вечера"}:
        next_hour += 12
    minute = 15 if form == "quarter" else 30
    return form, next_hour, f"{(next_hour - 1) % 24:02d}:{minute:02d}"


def validate_manual(manual: dict) -> dict[str, list[dict]]:
    if manual["version"] != "v12.5":
        raise ValueError("expected manual version v12.5")
    output = {"train": [], "validation": []}
    counts = Counter()
    for example in manual["examples"]:
        split, form = example["split"], example["form"]
        if split not in output or form not in FORMS:
            raise ValueError("invalid manual split or form")
        row = example["row"]
        label = row["case_id"]
        if row["contract_version"] != "v12.5" or row["category"] != f"v12_5_clock_{form}":
            raise ValueError(f"{label}: wrong row contract or category")
        normalized = normalize_record(row, label)
        response = json.loads(normalized["messages"][-1]["content"])
        user = normalized["messages"][1]["content"].casefold()
        if example["time_phrase"] not in user or example["reply_time_phrase"] not in response["reply"].casefold():
            raise ValueError(f"{label}: missing literal time phrase")
        expected_clock = (form, example["next_hour_24"], example["clock"])
        for key in ("time_phrase", "reply_time_phrase"):
            if read_clock_phrase(example[key]) != expected_clock:
                raise ValueError(f"{label}: clock phrase contradicts the asserted time")
        system = RUNTIME_SYSTEM_RE.fullmatch(normalized["messages"][0]["content"])
        today = date.fromisoformat(system["date"])
        if WEEKDAYS[today.weekday()] != system["weekday"]:
            raise ValueError(f"{label}: wrong system weekday")
        if type(example["day_offset"]) is not int or example["day_offset"] not in (0, 1, 2):
            raise ValueError(f"{label}: unexpected day offset")
        day_word = ("сегодня", "завтра", "послезавтра")[example["day_offset"]]
        if not re.search(r"\b" + day_word + r"\b", user):
            raise ValueError(f"{label}: day word disagrees with day offset")
        start = f"{today + timedelta(days=example['day_offset'])}T{example['clock']}"
        params = response["params"]
        if response["intent"] != "calendar_add" or set(params) != {"title", "starts_at", "duration_min"}:
            raise ValueError(f"{label}: unexpected command fields")
        if params["starts_at"] != start or params["duration_min"] != example["duration_min"]:
            raise ValueError(f"{label}: wrong date, clock time or duration")
        output[split].append(normalized)
        counts[split, form] += 1
    for split in output:
        reference_count = count_reference(COUNT_SOURCES[split])
        if reference_count != 2 or any(counts[split, form] != reference_count for form in FORMS):
            raise ValueError(f"{split}: expected two examples per form matching the audited reference")
    return output


def validate_inputs() -> tuple[dict[str, list[dict]], dict[str, list[dict]]]:
    for split, digest in SOURCE_HASHES.items():
        if file_sha256(SOURCE / f"{split}.jsonl") != digest:
            raise ValueError(f"{split}: original V12.4 changed")
    verify_dataset_provenance(SOURCE / "provenance.json", SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    added = validate_manual(read_json(MANUAL))
    outputs = {split: load_jsonl(SOURCE / f"{split}.jsonl") + added.get(split, []) for split in SOURCE_HASHES}
    signatures, ids = set(), set()
    for split, rows in outputs.items():
        for row in rows:
            signature = message_signature(row)
            if signature in signatures or (row.get("case_id") is not None and row["case_id"] in ids):
                raise ValueError(f"{split}: duplicate context or case ID")
            signatures.add(signature)
            ids.add(row.get("case_id"))
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
        raise ValueError("V12.5 already exists; refusing to overwrite")
    OUTPUT.mkdir()
    for split in outputs:
        original = (SOURCE / f"{split}.jsonl").read_bytes()
        if not original.endswith(b"\n"):
            raise ValueError(f"{split}: missing final newline in source")
        suffix = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in added.get(split, [])).encode("utf-8")
        (OUTPUT / f"{split}.jsonl").write_bytes(original + suffix)
    artifacts = {split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")} for split in outputs}
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.5", "base_version": "v12.4", "inherited_contract": "v12.1", "new_contract": "v12.5",
        "source_sha256": SOURCE_HASHES, "manual_sha256": file_sha256(MANUAL),
        "count_reference": {split: {"path": str(path.relative_to(ROOT)), "sha256": file_sha256(path), "user_rows_without_quarter": count_reference(path)} for split, path in COUNT_SOURCES.items()},
        "reference_usage": "Counts only; zero V14 rows imported.",
        "new_counts": {split: dict(Counter(row["category"] for row in rows)) for split, rows in added.items()},
        "total_rows": counts, "inherited_bytes": "UNCHANGED", "holdouts": "UNCHANGED",
        "training": "NOT_RUN", "gguf_conversion": "NOT_RUN", "model_evaluation": "NOT_RUN",
    })
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-assistant-sft-v12.5",
        "review": {
            "reviewed_by": "Codex, preparation audit under project owner instructions",
            "reviewed_on": date.today().isoformat(),
            "decision_evidence": "The user requested manual quarter/pol/polovina examples and reply-only rules, then instructed continued execution. This register covers preparation, not a training run.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_v12_4/provenance.json; workspace:docs/calendar_assistant_v12_5_relative_clock_manual.json",
            "rights_evidence": "The verified V12.4 data are copied unchanged. All twelve additions describe fictional events and were authored explicitly for this project. No external material, model-generated predictions or real personal data was added.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True, "contains_personal_data": False,
        }],
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
