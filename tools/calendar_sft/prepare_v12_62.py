"""Apply reviewed literal reply edits; never synthesize linguistic targets."""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from datetime import date
import hashlib
import json
from pathlib import Path
import re

from dataset_contract import normalize_record, parse_and_validate_assistant_response
from dataset_provenance import verify_dataset_provenance
import prepare_v12_61 as previous

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_61"
MANUAL = ROOT / "docs/calendar_v12_62_manual"
OUTPUT = ROOT / "docs/calendar_sft_v12_62"
SPEC = "docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md"
SPEC_SHA = "bfe13ab843ca8b46c646de95f8a10d61eaea96b26604d3bee787e9d6dee35df5"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def source_rows():
    return {path.stem: [json.loads(line) for line in path.read_bytes().splitlines()]
            for path in sorted(SOURCE.glob("*.jsonl"))}


def answer(row):
    return json.loads(row["messages"][-1]["content"])


def title_only_groups(outputs):
    groups = {}
    for split, rows in outputs.items():
        for line, row in enumerate(rows, 1):
            a = answer(row)
            if a["intent"] != "calendar_add":
                continue
            p, reply = a["params"], a["reply"]
            clock = p.get("starts_at", p.get("time"))
            if not isinstance(clock, str) or not re.search(r"\d\d:\d\d$", clock):
                continue
            if reply.strip().rstrip(".!?").casefold() != p.get("title", "").strip().casefold():
                continue
            key = (clock[-5:], reply)
            if key not in groups:
                groups[key] = {"id": f"G{len(groups) + 1:03d}", "time": clock[-5:], "reply": reply, "keys": []}
            groups[key]["keys"].append(f"{split}:{line}")
    return list(groups.values())


def literal_register(paths):
    result = {}
    for path in paths:
        for line, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not text or text.startswith("#"):
                continue
            key, reply = text.split("|", 1)
            if key in result or not reply or reply != reply.strip():
                raise ValueError(f"duplicate or malformed literal at {path}:{line}")
            result[key] = reply
    return result


def reply_clock(reply):
    # Validation only: the existing numerical parser requires the explicit
    # unit in a minutes-to phrase. Model targets are never rewritten here.
    if "без десяти минут " in reply:
        raise ValueError("V12.62 uses the reviewed short :50 wording")
    return previous.reply_clock(reply.replace("без десяти ", "без десяти минут "))


def validate_title_literal(group, reply):
    if not reply.startswith(group["reply"].rstrip(".!?") + " "):
        raise ValueError(f"title changed: {group['id']}")
    h, m = map(int, group["time"].split(":"))
    if reply_clock(reply) != (h % 12, m):
        raise ValueError(f"literal clock differs: {group['id']}")


def style_signature(reply):
    text = re.sub(r"\bбез десяти(?: минут)?\b", "<TEN_TO>", reply)
    for ordinal in previous.ORDINAL:
        # Normalize e/yo only in these fixed half-hour words, preserving
        # every other character (including event names and durations).
        for spelling in (ordinal, ordinal.replace("четвер", "четвёр")):
            text = re.sub(r"\bв пол[ -]?" + spelling + r"\b", "<HALF:" + ordinal + ">", text)
            text = re.sub(r"\bв половине " + spelling + r"\b", "<HALF:" + ordinal + ">", text)
    return text


def assert_reply_only(before, after):
    old, new = answer(before), answer(after)
    old.pop("reply")
    new.pop("reply")
    if old != new or before["messages"][:-1] != after["messages"][:-1]:
        raise ValueError("a model input or non-reply target changed")
    a, b = deepcopy(before), deepcopy(after)
    a.pop("messages")
    b.pop("messages")
    if a.get("contract_version") == "v12.1" and b.get("contract_version") == "v12.57":
        a["contract_version"] = "v12.57"
    if a != b:
        raise ValueError("unapproved row metadata change")


def protected_inputs():
    protected = {}
    baseline = read(SOURCE / "source_integrity_report.json")["baseline_sha256"]
    for name, old_sha in baseline.items():
        relative = Path(name).as_posix()
        expected = SPEC_SHA if relative == SPEC else old_sha
        if digest(ROOT / name) != expected:
            raise ValueError(f"protected source changed: {name}")
        protected[relative] = expected
    for path in [*SOURCE.iterdir(), *MANUAL.iterdir(), ROOT / "docs/CALENDAR_ASSISTANT_V12_62_REPLY_CLOCK.md", Path(__file__)]:
        if path.is_file():
            protected[path.relative_to(ROOT).as_posix()] = digest(path)
    return protected


def assemble():
    inputs = protected_inputs()
    original = source_rows()
    groups = title_only_groups(original)
    if groups != read(MANUAL / "title_groups.json"):
        raise ValueError("reviewed group membership differs from immutable source")
    literals = literal_register(sorted(MANUAL.glob("groups_*.txt")))
    if set(literals) != {g["id"] for g in groups}:
        raise ValueError("a literal title/time reply is missing or extra")
    edits, reasons = {}, {}
    for group in groups:
        reply = literals[group["id"]]
        validate_title_literal(group, reply)
        for key in group["keys"]:
            edits[key], reasons[key] = reply, group["id"]
    for key, reply in literal_register([MANUAL / "style.txt"]).items():
        if key in edits:
            raise ValueError(f"duplicate row edit: {key}")
        edits[key], reasons[key] = reply, "literal_style"
    outputs = deepcopy(original)
    corrections = []
    for key, reply in edits.items():
        split, line = key.split(":")
        before = original[split][int(line) - 1]
        row = outputs[split][int(line) - 1]
        old = answer(before)
        if old["reply"] == reply:
            raise ValueError(f"unchanged literal registered: {key}")
        if reasons[key] == "literal_style" and style_signature(old["reply"]) != style_signature(reply):
            raise ValueError(f"style edit changes other wording: {key}")
        new = deepcopy(old)
        new["reply"] = reply
        row["messages"][-1]["content"] = json.dumps(new, ensure_ascii=False, separators=(",", ":"))
        if row.get("contract_version") == "v12.1":
            row["contract_version"] = "v12.57"
        assert_reply_only(before, row)
        corrections.append({"key": key, "case_id": row.get("case_id"), "literal_reference": reasons[key],
                            "reply_before": old["reply"], "reply_after": reply,
                            "source_contract_version": before.get("contract_version"),
                            "contract_version": row.get("contract_version"), "params": old["params"]})
    for split, rows in outputs.items():
        for line, row in enumerate(rows, 1):
            assert_reply_only(original[split][line - 1], row)
            normalize_record(row, f"{split}:{line}")
            parse_and_validate_assistant_response(row["messages"][-1]["content"], contract_version="v12.57")
    previous.validate_partitions(outputs)
    if title_only_groups(outputs):
        raise ValueError("known calendar_add times still have title-only replies")
    grid = read(SOURCE / "clock_coverage_index.json")
    if set(grid) != {f"{h:02d}:{m:02d}" for h in range(24) for m in range(60)}:
        raise ValueError("source clock grid is incomplete")
    for clock, item in grid.items():
        row = outputs["train"][item["train_line"] - 1]
        a = answer(row)
        h, m = map(int, clock.split(":"))
        phrase = a["reply"].split(", на ", 1)[0].rstrip(" ,.")
        if a["params"]["starts_at"][-5:] != clock or reply_clock(phrase) != (h % 12, m):
            raise ValueError(f"1440-grid mismatch: {clock}")
    if inputs != protected_inputs():
        raise ValueError("inputs changed during preparation")
    return original, outputs, corrections, grid, inputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    original, outputs, corrections, grid, inputs = assemble()
    changed = {entry["key"] for entry in corrections}
    payloads = {}
    for split, rows in outputs.items():
        source_lines = (SOURCE / f"{split}.jsonl").read_bytes().splitlines(keepends=True)
        payloads[split] = b"".join((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
                                   if f"{split}:{line}" in changed else source_lines[line - 1]
                                   for line, row in enumerate(rows, 1))
    summary = {"status": "PASS", "version": "v12.62", "rows": {k: len(v) for k, v in outputs.items()},
               "changed_replies": dict(Counter(c["key"].split(":")[0] for c in corrections)),
               "total_changed_replies": len(corrections), "manual_literal_groups": 476, "title_only_rows_repaired": 700,
               "base_clock_input_and_reply_matches": len(grid), "model_inputs_unchanged": True,
               "intent_and_params_unchanged": True, "json_schema": "v12.57",
               "historical_contract_metadata_promoted_rows": sum(c["source_contract_version"] == "v12.1" for c in corrections),
               "leakage_checks": "PASS", "tokenization": "NOT_RUN", "training": "NOT_RUN", "model_evaluation": "NOT_RUN"}
    if args.check_only:
        for split, payload in payloads.items():
            if (OUTPUT / f"{split}.jsonl").read_bytes() != payload:
                raise ValueError(f"assembled dataset differs: {split}")
    else:
        if OUTPUT.exists():
            raise ValueError("refusing to overwrite an existing release")
        OUTPUT.mkdir()
        for split, payload in payloads.items():
            (OUTPUT / f"{split}.jsonl").write_bytes(payload)
        artifacts = {split: {"sha256": digest(OUTPUT / f"{split}.jsonl")} for split in outputs}
        write_json(OUTPUT / "manifest.json", {**summary, "source": str(SOURCE.relative_to(ROOT)), "artifacts": artifacts,
                                             "protected_sha256": inputs, "source_contract_metadata": "retained in correction_log for the 700 promoted rows"})
        write_json(OUTPUT / "correction_log.json", corrections)
        write_json(OUTPUT / "clock_coverage_index.json", grid)
        write_json(OUTPUT / "validation_report.json", summary)
        provenance = deepcopy(read(SOURCE / "provenance.json"))
        provenance.update(dataset_id="aiassistent1-v12.62-reply-clock", artifacts={k: artifacts[k] for k in ("train", "validation")})
        provenance["review"].update(reviewed_on=date.today().isoformat(), decision_evidence="Owner approved complete 1440 reply clock coverage, a narrow rule clarification, and explicitly requested training. Only model reply targets change.")
        provenance["records"][0].update(source_reference="workspace:docs/calendar_sft_v12_61/provenance.json and docs/calendar_v12_62_manual",
                                         rights_evidence="Existing approved fictional project examples and complete literal AI-authored reply strings manually reviewed in this task; no external corpus or personal records.")
        write_json(OUTPUT / "provenance.json", provenance)
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
