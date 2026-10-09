"""Package reviewed exclusions and unchanged literal conversations as V12.67."""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path

from v12_67_contract import CONTRACT_VERSION, INTENTS, normalize_record
from v12_67_evaluation import check_reply, case_from_row, grade

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_66"
MANUAL = ROOT / "docs/calendar_v12_67_manual"
OUTPUT = ROOT / "docs/calendar_sft_v12_67"
RULES = ("docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md", "docs/CALENDAR_ASSISTANT_V12_67_RULES.md",
         "docs/CALENDAR_SYSTEM_PROMPT_CONTRACT.md", "docs/CALENDAR_ASSISTANT_ANDROID_MECHANISMS.md",
         "docs/CALENDAR_OPTIONAL_NOTES.md", "docs/CALENDAR_ASSISTANT_CLEAN_ROOM.md",
         "docs/CALENDAR_ASSISTANT_DATASET_REVIEW.md", "tools/calendar_sft/README.md")
SCOPE_EXCLUSIONS = {
    "train": frozenset({1088, 1089, 1358, 1360, 1364, 1365, 1366, 1367, 1368,
                        1369, 1370, 1371, 1372, 1435, 1441, 1443, 1444}),
    "validation": frozenset({255, 256, 281, 282}),
}


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def digest(path):
    return sha256(Path(path).read_bytes()).hexdigest()


def canonical(row):
    return json.dumps(row, ensure_ascii=False, sort_keys=True)


def messages_digest(row):
    return sha256(canonical(row["messages"]).encode("utf-8")).hexdigest()


def verify_preserved_sources():
    lock = read(MANUAL / "source_lock.json")
    for name, expected in lock.items():
        if digest(ROOT / name) != expected:
            raise ValueError("Protected source changed: " + name)
    return len(lock)


def retained(source_rows, registry, split, *, jsonl=True):
    exclusions = {item["line"]: item for item in registry["exclude"]}
    if len(exclusions) != len(registry["exclude"]):
        raise ValueError("Duplicate exclusion position")
    result, positions = [], []
    for line, (original, original_text) in enumerate(source_rows, 1):
        if line in exclusions:
            item = exclusions[line]
            if sha256(original_text.encode("utf-8")).hexdigest() != item["row_sha256"]:
                raise ValueError(f"Excluded source row changed: {split}:{line}")
            intent = json.loads(original["messages"][-1]["content"])["intent"]
            if item["reason"] == "outside_contract" and intent in INTENTS:
                raise ValueError("Exclusion would remove a supported action")
            if item["reason"] == "scope_instruction" and line not in SCOPE_EXCLUSIONS.get(split, frozenset()):
                raise ValueError("Unexpected instructional exclusion")
            continue
        row = deepcopy(original)
        row["contract_version"] = CONTRACT_VERSION
        core = {key: value for key, value in row.items()
                if key in {"category", "messages", "case_id", "contract_version"}}
        normalize_record(core, f"{split}:{line}")
        if row["messages"] != original["messages"] or row["category"] != original["category"]:
            raise ValueError("Literal conversation changed")
        result.append(core if jsonl else row)
        positions.append(dict(source_line=line, output_line=len(result),
                              case_id=row.get("case_id"), messages_sha256=messages_digest(row)))
    if set(exclusions) - set(range(1, len(source_rows) + 1)):
        raise ValueError("Exclusion position is outside the source")
    return result, positions


def assemble(check_only=False, refresh_metadata=False, apply_reviewed_exclusions=False):
    if sum((check_only, refresh_metadata, apply_reviewed_exclusions)) > 1:
        raise ValueError("Choose one preparation mode")
    protected = verify_preserved_sources()
    registry = read(MANUAL / "exclusions.json")
    outputs, retention, artifacts, reply_checks = {}, {}, {}, {}
    for path in sorted(SOURCE.glob("*.jsonl")):
        split = path.stem
        if digest(path) != registry[split]["source_sha256"]:
            raise ValueError("Source dataset changed: " + split)
        source_rows = [(json.loads(line), line) for line in path.read_text(encoding="utf-8").splitlines()]
        rows, positions = retained(source_rows, registry[split], split)
        retention[split] = dict(source_rows=len(source_rows), excluded=len(source_rows) - len(rows), positions=positions)
        if not rows:
            continue
        content = ("\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows) + "\n").encode("utf-8")
        outputs[split + ".jsonl"] = content
        artifacts[split] = dict(rows=len(rows), sha256=sha256(content).hexdigest(),
                               intents=dict(sorted(Counter(json.loads(row["messages"][-1]["content"])["intent"] for row in rows).items())))
        checked = 0
        for row in rows:
            payload = json.loads(row["messages"][-1]["content"])
            meaning, style, _ = check_reply(payload)
            if meaning is False or style is False:
                raise ValueError("Retained literal reply fails clock checks: " + str(row.get("case_id", split)))
            checked += meaning is True
        reply_checks[split] = dict(rows=len(rows), known_clock_rows=checked, status="PASS")
    dev_path = SOURCE / "generation_dev.json"
    if digest(dev_path) != registry["generation_dev"]["source_sha256"]:
        raise ValueError("Development source changed")
    dev, dev_positions = retained([(row, canonical(row)) for row in read(dev_path)],
                                 registry["generation_dev"], "generation_dev", jsonl=False)
    retention["generation_dev"] = dict(source_rows=len(read(dev_path)), excluded=len(read(dev_path)) - len(dev), positions=dev_positions)
    retained_ids = {item["case_id"] for entry in retention.values() for item in entry["positions"] if item["case_id"]}
    audit = {key: value for key, value in read(SOURCE / "audit_index.json").items() if key in retained_ids}
    for index, row in enumerate(dev):
        candidate = deepcopy(row)
        candidate["audit"] = audit.get(row.get("case_id"), candidate.get("audit", {}))
        if candidate["audit"].get("split") == "holdout":
            raise ValueError("Holdout cannot be development data")
        case = case_from_row(candidate, row.get("case_id", f"DEV_{index}"), "development")
        if not grade(case, row["messages"][-1]["content"])["passed"]:
            raise ValueError("Development target fails the current grader")
    grid = deepcopy(read(SOURCE / "clock_coverage_index.json"))
    train_lines = {item["case_id"]: item["output_line"] for item in retention["train"]["positions"] if item["case_id"]}
    for cell in grid.values():
        cell["train_line"] = train_lines[cell["case_id"]]
    if len(grid) != 1440:
        raise ValueError("The inherited clock grid is incomplete")
    provider = ROOT / "app/src/main/java/com/example/aiassistent1/domain/provider/SystemPromptProvider.kt"
    total = sum(item["rows"] for item in artifacts.values())
    excluded = sum(value["excluded"] for key, value in retention.items() if key != "generation_dev")
    manifest = dict(version=CONTRACT_VERSION, contract_version=CONTRACT_VERSION,
                    allowed_intents=sorted(INTENTS), artifacts=artifacts, total_rows=total,
                    excluded_rows=excluded, development_rows=len(dev), inherited_clock_grid=len(grid),
                    conversations_preserved=total, changed_user_or_assistant_messages=0,
                    rules_sha256={name: digest(ROOT / name) for name in RULES},
                    runtime_provider_sha256=digest(provider), exclusions_sha256=digest(MANUAL / "exclusions.json"),
                    source_lock_sha256=digest(MANUAL / "source_lock.json"), protected_files_checked=protected,
                    archive_registry_sha256=digest(MANUAL / "archive_registry.json"),
                    source_manifest_sha256=digest(SOURCE / "manifest.json"),
                    training="NOT_RUN", inference="NOT_RUN")
    provenance = deepcopy(read(SOURCE / "provenance.json"))
    provenance.update(dataset_id="aiassistent1-v12.67-four-actions",
                      artifacts={split: {"sha256": artifacts[split]["sha256"]} for split in ("train", "validation")})
    provenance["review"].update(reviewed_on="2026-10-09",
        decision_evidence="Owner approved four actions and the Android assistant command cleanup. Existing literal conversations are retained unchanged under an exact exclusion register. No new Russian training sentences were generated.")
    metadata = {"manifest.json": manifest, "provenance.json": provenance,
                "retention_index.json": retention, "audit_index.json": audit,
                "generation_dev.json": dev, "reply_audit.json": reply_checks,
                "clock_coverage_index.json": grid}
    outputs.update({name: (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8") for name, value in metadata.items()})
    if apply_reviewed_exclusions:
        previous = read(OUTPUT / "manifest.json")
        if (previous["version"] != CONTRACT_VERSION or previous["training"] != "NOT_RUN"
                or previous["inference"] != "NOT_RUN"
                or {path.name for path in OUTPUT.iterdir()} != set(outputs)):
            raise ValueError("Reviewed exclusion applies only to the same untrained staging package")
        old_retention = read(OUTPUT / "retention_index.json")
        for split, artifact in previous["artifacts"].items():
            if digest(OUTPUT / (split + ".jsonl")) != artifact["sha256"]:
                raise ValueError("Staged source changed: " + split)
            old_rows = [json.loads(line) for line in (OUTPUT / (split + ".jsonl")).read_text(encoding="utf-8").splitlines()]
            old_positions = {item["source_line"]: row for item, row in zip(old_retention[split]["positions"], old_rows)}
            new_rows = [json.loads(line) for line in outputs[split + ".jsonl"].decode("utf-8").splitlines()]
            for item, row in zip(retention[split]["positions"], new_rows):
                if old_positions.get(item["source_line"]) != row:
                    raise ValueError("Exclusion revision would introduce or rewrite a conversation")
        for name, content in outputs.items():
            (OUTPUT / name).write_bytes(content)
    elif check_only or refresh_metadata:
        if {path.name for path in OUTPUT.iterdir()} != set(outputs):
            raise ValueError("Unexpected output files")
        for name, content in outputs.items():
            if (OUTPUT / name).read_bytes() != content and not (refresh_metadata and name in metadata):
                raise ValueError("Reassembly differs: " + name)
        if refresh_metadata:
            for name in metadata:
                (OUTPUT / name).write_bytes(outputs[name])
    else:
        OUTPUT.mkdir(exist_ok=False)
        for name, content in outputs.items():
            (OUTPUT / name).write_bytes(content)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check-only", action="store_true")
    modes.add_argument("--refresh-metadata", action="store_true",
                       help="Refresh generated metadata only after all literal datasets match")
    modes.add_argument("--apply-reviewed-exclusions", action="store_true",
                       help="Apply the exact reviewed register to untrained staging; only remove rows")
    args = parser.parse_args()
    result = assemble(args.check_only, args.refresh_metadata, args.apply_reviewed_exclusions)
    print(json.dumps({key: result[key] for key in ("version", "total_rows", "excluded_rows", "development_rows", "changed_user_or_assistant_messages")}, ensure_ascii=False))
