"""Serialize reviewed literal replies; never generate Russian training text."""
from __future__ import annotations
import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path

from dataset_contract import normalize_record, parse_and_validate_assistant_response
from prepare_v12_61 import validate_partitions, norm
from prepare_v12_64 import ROOT, read, digest, core_record, audit_literal, anchor_time
from v12_65_evaluation import check_reply, expected_clocks

SOURCE = ROOT / "docs/calendar_sft_v12_64"
MANUAL = ROOT / "docs/calendar_v12_65_manual"
OUTPUT = ROOT / "docs/calendar_sft_v12_65"
RULES = ("docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md",
         "docs/CALENDAR_ASSISTANT_V12_65_REPLY_CONSISTENCY.md")


def literals(name):
    contexts = read(MANUAL / "contexts.json")
    rows = []
    for n, line in enumerate((MANUAL / (name + ".txt")).read_text(encoding="utf-8").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        key, context, phrase, user, raw = line.split("|", 4)
        row = dict(case_id="V1265" + key, category="v12_65_" + name, contract_version="v12.57",
                   messages=[dict(role="system", content=contexts[context]), dict(role="user", content=user),
                             dict(role="assistant", content=raw)],
                   literal_reference=f"docs/calendar_v12_65_manual/{name}.txt:{n}")
        payload = json.loads(raw)
        if payload["intent"] == "calendar_update":
            normalize_record(core_record(row), row["case_id"])
            parse_and_validate_assistant_response(raw, contract_version="v12.57")
            # Audit the literal clock with the existing input-time oracle using
            # a temporary creation. Neither the saved command nor its text changes.
            surrogate = deepcopy(row)
            now = anchor_time(contexts[context])
            proxy = dict(intent="calendar_add", reply=payload["reply"].removeprefix("Событие изменено:").strip(),
                         params={"starts_at": now.date().isoformat() + "T" + payload["params"]["changes"]["time"]})
            surrogate["messages"][1]["content"] = "Сегодня " + user
            surrogate["messages"][-1]["content"] = json.dumps(proxy, ensure_ascii=False)
            row["audit"] = audit_literal(surrogate, phrase)["audit"]
        else:
            row = audit_literal(row, phrase)
        rows.append(row)
    return rows


def repairs():
    result = {}
    for n, line in enumerate((MANUAL / "reply_repairs.txt").read_text(encoding="utf-8").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        before, clock, after = line.split("|")
        key = before, (clock,)
        if key in result or before == after:
            raise ValueError("duplicate or empty correction")
        result[key] = (after, n)
    return result


def correct(row, replacements):
    result = deepcopy(row)
    a = json.loads(row["messages"][-1]["content"])
    key = a["reply"], tuple(expected_clocks(a))
    if key not in replacements:
        return result, None
    before = deepcopy(a)
    a["reply"], line = replacements[key]
    if {k: v for k, v in before.items() if k != "reply"} != {k: v for k, v in a.items() if k != "reply"}:
        raise ValueError("correction changed parameters")
    result["messages"][-1]["content"] = json.dumps(a, ensure_ascii=False, separators=(",", ":"))
    # V12.1 required title-only creation replies; this new copy adopts the
    # existing spoken-time contract without changing any command fields.
    if result.get("contract_version") == "v12.1":
        result["contract_version"] = "v12.57"
    return result, dict(before=before["reply"], after=a["reply"], clocks=expected_clocks(a),
                        manual_line=line, contract_before=row.get("contract_version"),
                        contract_after=result.get("contract_version"),
                        reason="known_clock_omitted_or_legacy_wording")


def audit_rows(rows):
    known, missing, bad = 0, 0, []
    for n, row in enumerate(rows, 1):
        a = json.loads(row["messages"][-1]["content"])
        if a["intent"] not in ("calendar_add", "calendar_update"):
            continue
        clocks = expected_clocks(a)
        meaning, style, mentions = check_reply(a)
        known += bool(clocks)
        missing += not bool(clocks)
        if meaning is False or style is False:
            bad.append(dict(line=n, clocks=clocks, meaning=meaning, style=style, reply=a["reply"]))
    return dict(known_clock_rows=known, no_known_clock_rows=missing, failures=bad)


def jsonl(rows):
    return "".join(json.dumps(core_record(r), ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows).encode("utf-8")


def assemble(check_only=False):
    lock = read(MANUAL / "source_lock.json")["files"]
    for rel, sha in lock.items():
        if digest(ROOT / rel) != sha:
            raise ValueError("protected source changed: " + rel)
    source = {p.stem: [json.loads(line) for line in p.read_bytes().splitlines()]
              for p in sorted(SOURCE.glob("*.jsonl"))}
    replacements = repairs()
    outputs, changes, used = {}, [], set()
    for split, rows in source.items():
        outputs[split] = []
        for n, row in enumerate(rows, 1):
            new, change = correct(row, replacements)
            outputs[split].append(new)
            if change:
                changes.append(dict(split=split, line=n, **change))
                used.add(change["manual_line"])
    if used != {line for _, line in replacements.values()}:
        raise ValueError("manual repair not applied")
    additions = dict(train=[r for p in sorted(MANUAL.glob("*_train.txt")) for r in literals(p.stem)],
                     validation=literals("validation"), holdout=literals("holdout"))
    outputs["train"].extend(additions["train"])
    outputs["validation"].extend(additions["validation"])
    outputs["reply_consistency_holdout"] = additions["holdout"]
    validate_partitions({s: [core_record(r) for r in rows] for s, rows in outputs.items()})
    # Additional split independence includes update targets and training history.
    def names(rows):
        names = set()
        for r in rows:
            p = json.loads(r["messages"][-1]["content"])["params"]
            names.update(norm(x) for x in (p.get("title"), p.get("target", {}).get("query")) if x)
        return names
    prior_names = names(source["train"] + source["validation"])
    split_names = {k: names(v) for k, v in additions.items()}
    if (split_names["validation"] & (prior_names | split_names["train"]) or
            split_names["holdout"] & (prior_names | split_names["train"] | split_names["validation"])):
        raise ValueError("new validation/holdout names overlap")
    development = [core_record(correct(r, replacements)[0]) for r in read(SOURCE / "generation_dev.json")]
    development += [core_record(r) for r in additions["validation"]]
    # Keep V12.64 temporal annotations available to training-time evaluation.
    index = deepcopy(read(SOURCE / "audit_index.json"))
    index.update({r["case_id"]: {**r["audit"], "literal_reference": r["literal_reference"], "split": split}
                  for split, rows in additions.items() for r in rows})
    audits = {s: audit_rows(rows) for s, rows in outputs.items()}
    audits["generation_dev"] = audit_rows(development)
    if any(a["failures"] for a in audits.values()):
        raise ValueError("inconsistent reference replies: " + json.dumps({s: a["failures"] for s, a in audits.items() if a["failures"]}, ensure_ascii=False))
    clock_rows = [json.loads(r["messages"][-1]["content"])["params"]["starts_at"][-5:]
                  for r in literals("clock_train")]
    quarter_hours = sorted(int(c[:2]) for c in clock_rows if c[3:] == "15")
    if quarter_hours != list(range(24)):
        raise ValueError("quarter coverage is incomplete")
    quartet_hours = sorted(h for h in range(24) if {f"{h:02}:{m:02}" for m in (12, 14, 15, 16)} <= set(clock_rows))
    if len(quartet_hours) != 12:
        raise ValueError("contrast quartet coverage changed")
    artifacts = {s: dict(rows=len(rows), sha256=hashlib.sha256(jsonl(rows)).hexdigest()) for s, rows in outputs.items()}
    report = dict(version="v12.65", contract_version="v12.57", artifacts=artifacts,
                  inherited_rows=sum(map(len, source.values())), additions={s: len(v) for s, v in additions.items()},
                  corrections=dict(Counter(c["split"] for c in changes)), manual_repair_groups=len(replacements),
                  manual_sha256={p.name: digest(p) for p in sorted(MANUAL.iterdir()) if p.is_file()},
                  rules_sha256={p: digest(ROOT / p) for p in RULES}, protected_files_checked=len(lock),
                  coverage=dict(quarter_hours=quarter_hours, contrast_quartet_hours=quartet_hours,
                                contrast_minutes=[12, 14, 15, 16], inherited_clock_grid=1440,
                                inherited_offsets_preserved=True, exhaustive_language_coverage=False),
                  training="NOT_RUN", inference="NOT_RUN")
    values = {s + ".jsonl": jsonl(rows) for s, rows in outputs.items()}
    provenance = deepcopy(read(SOURCE / "provenance.json"))
    provenance.update(dataset_id="aiassistent1-v12.65-reply-consistency", artifacts={s: {"sha256": artifacts[s]["sha256"]} for s in ("train", "validation")})
    provenance["review"].update(reviewed_on="2026-10-04", decision_evidence="Owner confirmed V12.65 data, rules, semantic checks and next-training preparation. Fictional literal conversations and exact reply edits were reviewed and independently audited; no external calendar records.")
    provenance["records"][0].update(source_reference="workspace:docs/calendar_sft_v12_64/provenance.json and docs/calendar_v12_65_manual", rights_evidence="Inherited authorized fictional examples plus literal authored conversations and reply replacements; scripts do not generate Russian sentences.")
    metadata = {"manifest.json": report, "provenance.json": provenance, "audit_index.json": index,
                "generation_dev.json": development, "correction_log.json": changes,
                "reply_audit.json": audits, "clock_coverage_index.json": read(SOURCE / "clock_coverage_index.json")}
    for name, value in metadata.items():
        values[name] = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if check_only:
        if set(p.name for p in OUTPUT.iterdir()) != set(values):
            raise ValueError("unexpected dataset files")
        for name, value in values.items():
            if (OUTPUT / name).read_bytes() != value:
                raise ValueError("reassembly differs: " + name)
    else:
        OUTPUT.mkdir(exist_ok=False)
        for name, value in values.items():
            (OUTPUT / name).write_bytes(value)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--audit-literals", action="store_true")
    args = parser.parse_args()
    if args.audit_literals:
        print({p.stem: len(literals(p.stem)) for p in sorted(MANUAL.glob("*.txt")) if p.stem != "reply_repairs"})
    else:
        report = assemble(args.check_only)
        print(json.dumps({k: report[k] for k in ("version", "additions", "corrections", "protected_files_checked", "training")}, ensure_ascii=False))
