"""Validate and copy handwritten V12.54 reply edits into the frozen V12.53 rows."""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from datetime import date, datetime
import json
from pathlib import Path
import re

from dataset_contract import file_sha256, load_jsonl, message_signature, normalize_record
from dataset_provenance import verify_dataset_provenance
from prepare_v12_51 import write_json
from prepare_v12_53 import has_clock_daypart

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_53"
OUTPUT = ROOT / "docs/calendar_sft_v12_54"
REPLY_EDITS = ROOT / "docs/calendar_assistant_v12_54_reply_edits_manual.json"
SOURCE_HASHES = {
    "train": "a83bbc409dddbdcc74e5d1c43a78b1045e48778eafcf2103911b034ddba0e528",
    "validation": "88dd361060ac58a43cad1baae69c40cd40a7f66c00e28a507095bb800a3e52e5",
    "holdout": "a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19",
    "calendar_holdout_v12_2": "504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade",
    "regression_holdout": "b2a7bdbc93a6f59769f0ab0a9108dc707e1bc7a494d00cbd5c8bb2fbfa7d1e34",
}
TOTAL_ROWS = {"train": 2401, "validation": 720, "holdout": 36,
              "calendar_holdout_v12_2": 8, "regression_holdout": 63}
EDIT_COUNTS = {"train": 144, "validation": 36, "holdout": 0,
               "calendar_holdout_v12_2": 0, "regression_holdout": 0}
OLD_MINUTES = {"тридцать пять": 35, "сорок": 40, "пятьдесят": 50, "пятьдесят пять": 55}
REMAINING_MINUTES = {"двадцати пяти": 25, "двадцати": 20, "десяти": 10, "пяти": 5}
# Index is the actual start hour modulo twelve, not the upcoming named hour.
ORDINAL_HOURS = (
    "первого", "второго", "третьего", "четвёртого", "пятого", "шестого",
    "седьмого", "восьмого", "девятого", "десятого", "одиннадцатого", "двенадцатого",
)
CARDINAL_HOURS = (
    "час", "два", "три", "четыре", "пять", "шесть",
    "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать",
)
OLD_CLOCK_RE = re.compile(
    r"\bв (?P<minute>" + "|".join(OLD_MINUTES) + r") минут (?P<hour>"
    + "|".join(ORDINAL_HOURS) + r")\b"
)
TO_CLOCK_RE = re.compile(
    r"\bбез (?P<remaining>" + "|".join(REMAINING_MINUTES) + r") минут (?P<hour>"
    + "|".join(CARDINAL_HOURS) + r")\b"
)
TO_DAYPART_RE = re.compile(TO_CLOCK_RE.pattern + r" (?:утра|дня|вечера|ночи)\b")


def read_manual() -> dict:
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate manual key: {key}")
            result[key] = value
        return result

    return json.loads(REPLY_EDITS.read_text(encoding="utf-8"), object_pairs_hook=unique_keys)


def validate_reply_edit(response: dict, replacement: str) -> int:
    """Check the literal edit's scope and exact clock value; never generate a reply."""
    if not isinstance(replacement, str):
        raise ValueError("manual reply must be a string")
    original = response["reply"]
    old_matches = list(OLD_CLOCK_RE.finditer(original))
    new_matches = list(TO_CLOCK_RE.finditer(replacement))
    if len(old_matches) != 1 or len(new_matches) != 1:
        raise ValueError("reply must contain exactly one selected clock expression")
    old, new = old_matches[0], new_matches[0]
    if original[:old.start()] != replacement[:new.start()] or original[old.end():] != replacement[new.end():]:
        raise ValueError("manual edit changes text outside the clock expression")
    start = datetime.fromisoformat(response["params"]["starts_at"])
    expected = (start.hour % 12, start.minute)
    old_clock = (ORDINAL_HOURS.index(old["hour"]), OLD_MINUTES[old["minute"]])
    new_clock = (CARDINAL_HOURS.index(new["hour"]), 60 - REMAINING_MINUTES[new["remaining"]])
    if old_clock != expected or new_clock != expected:
        raise ValueError("reply clock differs from the exact starts_at value")
    return start.minute


def apply_reply_edits(sources: dict[str, list[dict]], manual: dict) -> tuple[dict, dict]:
    if manual.get("version") != "v12.54" or manual.get("base_version") != "v12.53":
        raise ValueError("wrong manual reply-edit version")
    edits = manual["by_case_id"]
    outputs = deepcopy(sources)
    seen = set()
    changed = {split: 0 for split in sources}
    for split, rows in outputs.items():
        for index, row in enumerate(rows):
            response = json.loads(row["messages"][-1]["content"])
            case_id = row.get("case_id")
            selected = OLD_CLOCK_RE.search(response["reply"]) is not None
            if selected != (case_id in edits):
                raise ValueError(f"{split}:{index + 1}: missing or unrelated manual edit")
            if selected:
                if case_id in seen:
                    raise ValueError(f"duplicate edited case ID: {case_id}")
                validate_reply_edit(response, edits[case_id])
                response["reply"] = edits[case_id]
                row["messages"][-1]["content"] = json.dumps(response, ensure_ascii=False, separators=(",", ":"))
                normalize_record(row, f"{split}:{index + 1}")
                seen.add(case_id)
                changed[split] += 1
            if has_clock_daypart(response) or TO_DAYPART_RE.search(response["reply"]):
                raise ValueError(f"{split}:{index + 1}: clock daypart is present")
    if seen != set(edits):
        raise ValueError("unused manual reply edit")
    return outputs, changed


def validate_inputs() -> tuple[dict, dict]:
    for split, digest in SOURCE_HASHES.items():
        path = SOURCE / f"{split}.jsonl"
        if file_sha256(path) != digest or not path.read_bytes().endswith(b"\n"):
            raise ValueError(f"{split}: frozen V12.53 source changed")
    verify_dataset_provenance(SOURCE / "provenance.json", SOURCE / "train.jsonl", SOURCE / "validation.jsonl")
    sources = {split: load_jsonl(SOURCE / f"{split}.jsonl") for split in SOURCE_HASHES}
    outputs, changed = apply_reply_edits(sources, read_manual())
    if changed != EDIT_COUNTS or {s: len(rows) for s, rows in outputs.items()} != TOTAL_ROWS:
        raise ValueError("unexpected row or reply-edit counts")
    signatures, ids = set(), set()
    for split, rows in outputs.items():
        for old, new in zip(sources[split], rows, strict=True):
            old_response = json.loads(old["messages"][-1]["content"])
            new_response = json.loads(new["messages"][-1]["content"])
            old_response["reply"] = new_response["reply"]
            if old_response != new_response or old["messages"][:-1] != new["messages"][:-1]:
                raise ValueError("fields other than reply changed")
            signature, case_id = message_signature(new), new.get("case_id")
            if signature in signatures or (case_id is not None and case_id in ids):
                raise ValueError("duplicate prompt context or case ID")
            signatures.add(signature)
            if case_id is not None:
                ids.add(case_id)
            if new_response["intent"] == "note_add":
                raise ValueError("removed intent is present")
    return outputs, changed


def serialize_preserving_sources(outputs: dict) -> dict[str, bytes]:
    artifacts = {}
    for split, rows in outputs.items():
        old_lines = (SOURCE / f"{split}.jsonl").read_bytes().splitlines(keepends=True)
        lines = []
        for old_line, row in zip(old_lines, rows, strict=True):
            old = json.loads(old_line)
            if old == row:
                lines.append(old_line)
                continue
            old_response = json.loads(old["messages"][-1]["content"])
            new_response = json.loads(row["messages"][-1]["content"])
            old_response["reply"] = new_response["reply"]
            if old_response != new_response:
                raise ValueError("assistant fields other than reply changed")
            old["messages"][-1]["content"] = row["messages"][-1]["content"]
            if old != row:
                raise ValueError("row fields other than reply changed")
            lines.append((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        artifacts[split] = b"".join(lines)
    return artifacts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    outputs, changed = validate_inputs()
    serialized = serialize_preserving_sources(outputs)
    summary = {"total_rows": TOTAL_ROWS, "reply_edits": changed}
    if args.check_only:
        print(json.dumps(summary))
        return
    if OUTPUT.exists():
        raise ValueError("V12.54 already exists; refusing to overwrite")
    OUTPUT.mkdir()
    for split, data in serialized.items():
        (OUTPUT / f"{split}.jsonl").write_bytes(data)
    artifacts = {split: {"sha256": file_sha256(OUTPUT / f"{split}.jsonl")} for split in outputs}
    by_minute = {}
    for split, rows in outputs.items():
        counts = Counter()
        for row in rows:
            response = json.loads(row["messages"][-1]["content"])
            match = TO_CLOCK_RE.search(response["reply"])
            if match:
                counts[60 - REMAINING_MINUTES[match["remaining"]]] += 1
        by_minute[split] = dict(sorted(counts.items()))
    write_json(OUTPUT / "manifest.json", {
        "version": "v12.54", "base_version": "v12.53", "new_contract": "v12.5",
        "source_sha256": SOURCE_HASHES, "reply_edits_manual_sha256": file_sha256(REPLY_EDITS),
        "inherited_reply_edits": changed, "reply_edits_by_minute": by_minute,
        "total_rows": TOTAL_ROWS, "artifacts": artifacts,
        "archived_sources": "UNCHANGED", "inherited_fields_except_reply": "UNCHANGED",
        "holdouts": "BYTE_IDENTICAL", "row_order_and_split_membership": "UNCHANGED",
        "reply_clock_dayparts": "OMITTED",
        "training": "NOT_RUN", "gguf_conversion": "NOT_RUN", "model_evaluation": "NOT_RUN",
    })
    write_json(OUTPUT / "provenance.json", {
        "format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-assistant-sft-v12.54",
        "review": {
            "reviewed_by": "Codex, preparation audit under project owner instructions",
            "reviewed_on": date.today().isoformat(),
            "decision_evidence": "The user approved the plan for 180 handwritten reply-only replacements and named the addition V12.54. Preparation and formatter checks are authorized; this register does not report a training run.",
        },
        "artifacts": artifacts,
        "records": [{
            "source_type": "internal_authored",
            "source_reference": "workspace:docs/calendar_sft_v12_53/provenance.json; workspace:docs/calendar_assistant_v12_54_reply_edits_manual.json",
            "rights_evidence": "The verified V12.53 rows retain all inputs, intents, parameters, metadata and split membership. The 180 complete replacement replies were individually authored for these fictional project examples. No external material, inference predictions or real personal data was imported.",
            "permits_model_training": True, "permits_derivative_weight_distribution": True,
            "contains_personal_data": False,
        }],
    })
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
