"""Validate and serialize the fixed handwritten V12.3 word-order examples.

Every sentence and expected response is a literal in the manual source. This
module never constructs permutations, generates examples, repairs targets,
imports a data generator, changes the shared contract, or starts training.
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import date, datetime, timedelta
import json
from pathlib import Path
import re

from dataset_contract import (
    file_sha256, load_jsonl, message_signature, normalize_record, normalized_user_prompt,
)
from dataset_provenance import verify_dataset_provenance

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_2"
OUTPUT = ROOT / "docs/calendar_sft_v12_3"
MANUAL = ROOT / "docs/calendar_assistant_v12_3_word_order_manual.json"
SOURCE_HASHES = {
    "train": "fad13f167f25118537b0ec535b65b6975ceb7cd02ebcf0c244d77a6f3b2fe2dc",
    "validation": "acd55ba4be417715e08dcaa905d4dcff28432e9170e9b820c2926ae7749ca908",
    "holdout": "4751b71f4bd492c92b7f26aed522feadfe92d2a77e35468fedb8cff2bf69da8e",
    "regression_holdout": "e65beab07d78d525986fb4d3416fdde2bd4b330aa70b6b130875d16737b494ad",
}
ORDERS = (
    "title_day_time", "title_time_day", "day_title_time",
    "day_time_title", "time_title_day", "time_day_title",
)
FAMILY_COUNTS = {"train": 10, "validation": 6, "holdout": 6}
WEEKDAYS = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")


def observed_order(user: str, spans: dict[str, str]) -> str:
    """Check the annotated spans in an already written sentence; do not edit it."""
    if set(spans) != {"title", "day", "time"}:
        raise ValueError("expected exactly three annotated spans")
    positions = {}
    intervals = []
    for field, text in spans.items():
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"{field}: empty span")
        matches = list(re.finditer(r"(?<!\w)" + re.escape(text) + r"(?!\w)", user, re.IGNORECASE))
        if len(matches) != 1:
            raise ValueError(f"{field}: annotated span must occur exactly once")
        positions[field] = matches[0].start()
        intervals.append(matches[0].span())
    intervals.sort()
    if any(left[1] > right[0] for left, right in zip(intervals, intervals[1:])):
        raise ValueError("annotated spans overlap")
    return "_".join(sorted(positions, key=positions.get))


def validate_family(family: dict) -> list[dict]:
    label = family["id"]
    now = datetime.strptime(family["now"], "%Y-%m-%dT%H:%M")
    check = family["date_check"]
    if check["kind"] == "relative_days":
        if set(check) != {"kind", "days"} or type(check["days"]) is not int:
            raise ValueError(f"{label}: invalid day offset assertion")
        target_date = now.date() + timedelta(days=check["days"])
    elif check["kind"] == "weekday":
        if set(check) != {"kind", "weekday"} or type(check["weekday"]) is not int or not 0 <= check["weekday"] <= 6:
            raise ValueError(f"{label}: invalid weekday assertion")
        offset = (check["weekday"] - now.weekday()) % 7
        if offset == 0:
            raise ValueError(f"{label}: same-day weekday semantics are outside this focused layer")
        target_date = now.date() + timedelta(days=offset)
    elif check["kind"] == "explicit_date":
        if set(check) != {"kind", "year", "month", "day"}:
            raise ValueError(f"{label}: invalid explicit-date assertion")
        target_date = date(check["year"], check["month"], check["day"])
    else:
        raise ValueError(f"{label}: unknown date assertion")
    clock = datetime.strptime(family["clock"], "%H:%M").time()
    calculated = datetime.combine(target_date, clock).isoformat(timespec="minutes")
    response = family["response"]
    if response["intent"] != "calendar_add" or set(response["params"]) != {"title", "starts_at"}:
        raise ValueError(f"{label}: unexpected intent or fields in the three-fact layer")
    if response["params"]["starts_at"] != calculated:
        raise ValueError(f"{label}: handwritten response disagrees with calendar: {calculated}")
    if response["reply"] != response["params"]["title"] + ".":
        raise ValueError(f"{label}: reply must preserve the handwritten title only")
    if family["time_format"] != "words":
        raise ValueError(f"{label}: the existing contract requires clock times in words")
    variants = family["variants"]
    if Counter(v["order"] for v in variants) != Counter(ORDERS):
        raise ValueError(f"{label}: each of the six orders must occur exactly once")
    system = f"Сегодня дата и время:{now:%Y-%m-%d} ({WEEKDAYS[now.weekday()]}) {now:%H:%M} Europe/Samara ответ JSON"
    rows = []
    for variant in variants:
        if set(variant) != {"id", "order", "user"}:
            raise ValueError(f"{label}: unexpected variant metadata")
        if not 1 <= len(variant["user"].split()) <= 14:
            raise ValueError(f"{variant['id']}: request must contain at most fourteen words")
        if observed_order(variant["user"], family["spans"]) != variant["order"]:
            raise ValueError(f"{variant['id']}: annotated word order disagrees with the sentence")
        rows.append(normalize_record({
            "category": "v12_3_word_order_" + variant["order"],
            "case_id": variant["id"], "contract_version": "v12.1",
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": variant["user"]},
                {"role": "assistant", "content": json.dumps(response, ensure_ascii=False)},
            ],
        }, variant["id"]))
    return rows


def validate_manual(manual: dict) -> dict[str, list[dict]]:
    if manual["version"] != "v12.3" or manual["contract_version"] != "v12.1":
        raise ValueError("V12.3 must retain the V12.1 response contract")
    families = manual["families"]
    if Counter(f["split"] for f in families) != Counter(FAMILY_COUNTS):
        raise ValueError("expected 10 train, 6 validation, and 6 holdout families")
    ids = [f["id"] for f in families]
    ids.extend(v["id"] for f in families for v in f["variants"])
    if len(set(ids)) != len(ids):
        raise ValueError("family and case IDs must be unique")
    titles = [f["response"]["params"]["title"].casefold().strip() for f in families]
    if len(set(titles)) != len(titles):
        raise ValueError("one event must remain in one family and one split")
    added = {split: [] for split in FAMILY_COUNTS}
    for family in families:
        added[family["split"]].extend(validate_family(family))
    return added


def validate_inputs() -> tuple[dict, dict, dict]:
    for split, expected in SOURCE_HASHES.items():
        if file_sha256(SOURCE / f"{split}.jsonl") != expected:
            raise ValueError(f"{split}: original V12.2 changed")
    verify_dataset_provenance(SOURCE / "provenance.json", SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    manual = json.loads(MANUAL.read_text(encoding="utf-8"))
    added = validate_manual(manual)
    sources = {split: load_jsonl(SOURCE / f"{split}.jsonl") for split in SOURCE_HASHES}
    outputs = {
        "train": sources["train"] + added["train"],
        "validation": sources["validation"] + added["validation"],
        "holdout": added["holdout"],
        "calendar_holdout_v12_2": sources["holdout"],
        "regression_holdout": sources["regression_holdout"],
    }
    seen = set()
    seen_ids = set()
    for split, rows in outputs.items():
        for row in rows:
            signature = message_signature(row)
            if signature in seen:
                raise ValueError(f"duplicate input context in {split}: {row.get('case_id')}")
            seen.add(signature)
            if "case_id" in row:
                if row["case_id"] in seen_ids:
                    raise ValueError(f"duplicate case ID: {row['case_id']}")
                seen_ids.add(row["case_id"])
    trained = {normalized_user_prompt(row) for split in ("train", "validation") for row in outputs[split]}
    for split in ("holdout", "calendar_holdout_v12_2", "regression_holdout"):
        if trained & {normalized_user_prompt(row) for row in outputs[split]}:
            raise ValueError(f"{split}: holdout wording leaked into train/validation")
    for split, rows in added.items():
        others = {normalized_user_prompt(row) for name, values in outputs.items() if name != split for row in values}
        if others & {normalized_user_prompt(row) for row in rows}:
            raise ValueError(f"{split}: new wording overlaps another split")
    return manual, added, outputs


def encode_rows(rows: list[dict]) -> bytes:
    return "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows).encode("utf-8")


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    manual, added, outputs = validate_inputs()
    counts = {split: len(rows) for split, rows in outputs.items()}
    if args.check_only:
        print(json.dumps(counts))
        return
    if OUTPUT.exists():
        raise ValueError("V12.3 output already exists; refusing to overwrite prepared data")
    OUTPUT.mkdir(parents=True)
    for split in ("train", "validation"):
        (OUTPUT / f"{split}.jsonl").write_bytes((SOURCE / f"{split}.jsonl").read_bytes() + encode_rows(added[split]))
    (OUTPUT / "holdout.jsonl").write_bytes(encode_rows(added["holdout"]))
    (OUTPUT / "calendar_holdout_v12_2.jsonl").write_bytes((SOURCE / "holdout.jsonl").read_bytes())
    (OUTPUT / "regression_holdout.jsonl").write_bytes((SOURCE / "regression_holdout.jsonl").read_bytes())
    artifacts = {split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")} for split in outputs}
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED",
        "dataset_id": "aiassistent1-assistant-sft-v12.3-20260909",
        "review": {
            "reviewed_by": "Codex, preparation audit under the project owner's approval",
            "reviewed_on": "2026-09-09",
            "decision_evidence": "The user approved preparation of V12.3, reducing the proposed train quota from 30 to 10 examples per order. The approved plan retains 6 validation and 6 holdout examples per order. The user requires manual authorship, unchanged rules, and a final report before training. All new examples describe fictional project-authored events. This register documents data provenance, not permission to start training in this step.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_v12_2/provenance.json; workspace:docs/calendar_assistant_v12_3_word_order_manual.json",
            "rights_evidence": "Inherited V12.2 data remain byte-for-byte unchanged under their VERIFIED provenance. New sentences and targets were authored explicitly for this project's training dataset under the owner's approval, without external source content or real personal data. Model execution and release have not been performed.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True,
            "contains_personal_data": False,
        }],
    })
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.3", "contract_version": "v12.1", "base_version": "v12.2",
        "source_sha256": SOURCE_HASHES, "manual_sha256": file_sha256(MANUAL),
        "parent_provenance_sha256": file_sha256(SOURCE / "provenance.json"),
        "manual_families": len(manual["families"]), "manual_rows": sum(map(len, added.values())),
        "manual_split_counts": {split: len(rows) for split, rows in added.items()},
        "manual_order_counts": {split: {order: sum(r["category"] == "v12_3_word_order_" + order for r in rows) for order in ORDERS} for split, rows in added.items()},
        "family_index": [{"id": f["id"], "split": f["split"], "case_ids": [v["id"] for v in f["variants"]]} for f in manual["families"]],
        "artifacts": artifacts, "total_rows": counts,
        "calendar_assertions": "PASS", "word_order_assertions": "PASS", "split_checks": "PASS",
        "balance_scope": "new handwritten layer only; inherited rows unchanged",
        "data_generator": "NOT_RUN", "training": "NOT_RUN", "model_evaluation": "NOT_RUN",
        "gguf_conversion": "NOT_RUN", "android_validation": "NOT_RUN",
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
