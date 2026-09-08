"""Validate 48 handwritten cases and append them to immutable V12.1 files.

This is a one-to-one assembler. It never creates linguistic examples, samples
dates, imports the dataset generator, or repairs a failed calendar assertion.
"""

from __future__ import annotations

import calendar
from collections import Counter
from datetime import datetime, timedelta
import json
from pathlib import Path

from dataset_contract import (
    file_sha256, load_jsonl, message_signature, normalize_record, normalized_user_prompt,
)
from dataset_provenance import verify_dataset_provenance

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_1"
OUTPUT = ROOT / "docs/calendar_sft_v12_2"
MANUAL = ROOT / "docs/calendar_assistant_v12_2_calendar_manual.json"
PARENT_PROVENANCE = ROOT / "docs/calendar_sft_data_provenance_v12_1.json"
SOURCE_HASHES = {
    "train": "8da311c163240f1474566cb7bc3d787ca3358dbc268637d1411a382bbfb7a5d9",
    "validation": "55837272548051313e4e4e5239a548f4cb3c6ba6ec9c104454b29a44e500ff37",
    "holdout": "e65beab07d78d525986fb4d3416fdde2bd4b330aa70b6b130875d16737b494ad",
}
WEEKDAYS = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")


def validate_manual_case(case: dict) -> dict:
    now = datetime.fromisoformat(case["now"])
    if not 2027 <= now.year <= 2032:
        raise ValueError(f"{case['id']}: year outside approved scope")
    check = case["check"]
    if ("days" in check) == ("minutes" in check):
        raise ValueError(f"{case['id']}: specify exactly one calendar offset")
    if "days" in check:
        target = now.date() + timedelta(days=check["days"])
        if "at" in check:
            target = datetime.combine(target, datetime.strptime(check["at"], "%H:%M").time())
    else:
        if "at" in check:
            raise ValueError(f"{case['id']}: minute offset cannot have a clock override")
        target = now + timedelta(minutes=check["minutes"])
    calculated = target.isoformat(timespec="minutes") if isinstance(target, datetime) else target.isoformat()
    if calculated != check["expected"]:
        raise ValueError(f"{case['id']}: handwritten date disagrees with calendar: {calculated}")
    response = case["response"]
    if response["intent"] == "calendar_add":
        key = "starts_at" if isinstance(target, datetime) else "date"
        if response["params"].get(key) != calculated:
            raise ValueError(f"{case['id']}: response disagrees with checked date")
        if set(response["params"]) != {"title", key}:
            raise ValueError(f"{case['id']}: unexpected field in focused date-only case")
        if response["reply"] != response["params"]["title"] + ".":
            raise ValueError(f"{case['id']}: scheduling metadata in reply")
    elif response["intent"] == "chat":
        if target.month != 2 or f"{target.day} февраля {target.year} года." not in response["reply"]:
            raise ValueError(f"{case['id']}: backwards-date answer disagrees with calendar")
    else:
        raise ValueError(f"{case['id']}: unexpected intent in focused layer")
    if len(case["user"].split()) > 14:
        raise ValueError(f"{case['id']}: request is too long")
    kind = "leap" if calendar.isleap(now.year) else "common"
    system = f"Сегодня дата и время:{now:%Y-%m-%d} ({WEEKDAYS[now.weekday()]}) {now:%H:%M} Europe/Samara ответ JSON"
    return normalize_record({
        "category": f"v12_2_{kind}_{response['intent']}",
        "case_id": case["id"], "contract_version": "v12.1",
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": case["user"]},
            {"role": "assistant", "content": json.dumps(response, ensure_ascii=False)},
        ],
    }, case["id"])


def validate_inputs() -> tuple[dict, dict]:
    manual = json.loads(MANUAL.read_text(encoding="utf-8"))
    if manual["version"] != "v12.2" or manual["contract_version"] != "v12.1":
        raise ValueError("V12.2 must retain the V12.1 response contract")
    cases = manual["cases"]
    if len(cases) != 48 or len({case["id"] for case in cases}) != 48:
        raise ValueError("expected exactly 48 distinct manually authored cases")
    expected_counts = Counter({
        ("train", 2028): 8, ("train", 2032): 8,
        ("validation", 2028): 2, ("validation", 2032): 2,
        ("holdout", 2028): 2, ("holdout", 2032): 2,
        **{(split, year): count for year in (2027, 2029, 2030, 2031)
           for split, count in (("train", 4), ("validation", 1), ("holdout", 1))},
    })
    if Counter((case["split"], datetime.fromisoformat(case["now"]).year) for case in cases) != expected_counts:
        raise ValueError("year/split balance differs from approved 32/8/8 layout")
    added = {split: [] for split in SOURCE_HASHES}
    for case in cases:
        added[case["split"]].append(validate_manual_case(case))

    for split, expected in SOURCE_HASHES.items():
        if file_sha256(SOURCE / f"{split}.jsonl") != expected:
            raise ValueError(f"{split}: original V12.1 changed")
    verify_dataset_provenance(PARENT_PROVENANCE, SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    sources = {split: load_jsonl(SOURCE / f"{split}.jsonl") for split in SOURCE_HASHES}
    outputs = {
        "train": sources["train"] + added["train"],
        "validation": sources["validation"] + added["validation"],
        "holdout": added["holdout"],
        "regression_holdout": sources["holdout"],
    }
    seen = set()
    for split, rows in outputs.items():
        for row in rows:
            signature = message_signature(row)
            if signature in seen:
                raise ValueError(f"duplicate context in {split}: {row.get('case_id')}")
            seen.add(signature)
    for split in ("holdout", "regression_holdout"):
        tested = {normalized_user_prompt(row) for row in outputs[split]}
        trained = {normalized_user_prompt(row) for name in ("train", "validation") for row in outputs[name]}
        if tested & trained:
            raise ValueError(f"{split}: holdout wording leaked into train/validation")
    for split in added:
        others = {normalized_user_prompt(row) for name, rows in outputs.items() if name != split for row in rows}
        if others & {normalized_user_prompt(row) for row in added[split]}:
            raise ValueError(f"{split}: new wording collides with another split")
    return sources, added


def encode_rows(rows: list[dict]) -> bytes:
    return "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows).encode("utf-8")


def main() -> None:
    sources, added = validate_inputs()
    if OUTPUT.exists():
        raise ValueError("V12.2 output already exists; do not overwrite an approved release")
    OUTPUT.mkdir(parents=True)
    for split in ("train", "validation"):
        (OUTPUT / f"{split}.jsonl").write_bytes((SOURCE / f"{split}.jsonl").read_bytes() + encode_rows(added[split]))
    (OUTPUT / "holdout.jsonl").write_bytes(encode_rows(added["holdout"]))
    (OUTPUT / "regression_holdout.jsonl").write_bytes((SOURCE / "holdout.jsonl").read_bytes())
    artifacts = {
        split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")}
        for split in ("train", "validation", "holdout", "regression_holdout")
    }
    provenance = {
        "format_version": 1, "status": "VERIFIED",
        "dataset_id": "aiassistent1-assistant-sft-v12.2-20260907",
        "review": {
            "reviewed_by": "AIAssistent1 project rights holder", "reviewed_on": "2026-09-07",
            "decision_evidence": "The holder approved 48 manually authored project examples for 2027-2032, the balanced 32 train / 8 validation / 8 holdout split, preparation and training, then named this version V12.2. Parent V12.1/V12 authorization covers training and derivative weights. The added cases are fictional project-authored examples with no real personal data.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_data_provenance_v12_1.json; workspace:docs/calendar_assistant_v12_2_calendar_manual.json",
            "rights_evidence": "Inherited VERIFIED V12.1 authorization and the explicit approval to author and train the 48-case V12.2 calendar layer in this task.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True,
            "contains_personal_data": False,
        }],
    }
    (OUTPUT / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "version": "v12.2", "contract_version": "v12.1", "base_version": "v12.1",
        "source_sha256": SOURCE_HASHES, "manual_sha256": file_sha256(MANUAL),
        "manual_rows": 48, "manual_split_counts": {name: len(rows) for name, rows in added.items()},
        "manual_year_counts": {"2027": 6, "2028": 12, "2029": 6, "2030": 6, "2031": 6, "2032": 12},
        "artifacts": artifacts,
        "total_rows": {"train": len(sources["train"]) + 32, "validation": len(sources["validation"]) + 8, "holdout": 8, "regression_holdout": len(sources["holdout"])},
        "calendar_assertions": "PASS", "split_checks": "PASS", "data_generator": "NOT_RUN",
    }
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(manifest["total_rows"]))


if __name__ == "__main__":
    main()
