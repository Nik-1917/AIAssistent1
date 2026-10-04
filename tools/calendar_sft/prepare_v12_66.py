"""Package literal V12.66 conversations; never generate Russian examples."""
from __future__ import annotations
import argparse
from collections import Counter
from copy import deepcopy
from datetime import timedelta
import hashlib
import json
import re

from dataset_contract import normalize_record, parse_and_validate_assistant_response
from prepare_v12_61 import validate_partitions, norm
from prepare_v12_64 import ROOT, read, digest, core_record, audit_literal, anchor_time
from prepare_v12_65 import audit_rows, jsonl

SOURCE = ROOT / "docs/calendar_sft_v12_65"
MANUAL = ROOT / "docs/calendar_v12_66_manual"
OUTPUT = ROOT / "docs/calendar_sft_v12_66"
RULES = ("docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md",
         "docs/CALENDAR_ASSISTANT_V12_65_REPLY_CONSISTENCY.md",
         "docs/CALENDAR_ASSISTANT_V12_66_INTENT_ROUTING.md")
# Verbs only: event names containing the noun 'напоминание' remain valid.
REMINDER = re.compile(
    r"\b(?:напомн(?:и|ите|ить|ил|ила|ило|или|ю|им|ишь|ит|ите|ят)|"
    r"напомина(?:й|йте|ть|л|ла|ли|ю|ем|ешь|ет|ете|ют))\b", re.I)
LOOKUP = re.compile(r"\b(?:найди|найдите|найти)\b", re.I)


def answer(row):
    return json.loads(row["messages"][-1]["content"])


def literals(name):
    contexts = read(MANUAL / "contexts.json")
    rows = []
    for line_no, line in enumerate((MANUAL / (name + ".txt")).read_text(encoding="utf-8").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        key, context, phrase, user, raw = line.split("|", 4)
        row = dict(case_id="V1266" + key, category="v12_66_" + name, contract_version="v12.57",
                   messages=[dict(role="system", content=contexts[context]), dict(role="user", content=user),
                             dict(role="assistant", content=raw)],
                   literal_reference=f"docs/calendar_v12_66_manual/{name}.txt:{line_no}")
        normalize_record(core_record(row), row["case_id"])
        payload = parse_and_validate_assistant_response(raw, contract_version="v12.57")
        now = anchor_time(contexts[context])
        if phrase.startswith("date:"):
            audit_literal(row, "?")
            day = phrase.split(":", 1)[1]
            if day not in ("завтра", "сегодня", "implicit_today"):
                raise ValueError("unsupported literal day")
            if day != "implicit_today" and not re.search(r"\b" + day + r"\b", user, re.I):
                raise ValueError("missing stated date")
            expected = (now + timedelta(days=day == "завтра")).date().isoformat()
            if payload["params"].get("date") != expected:
                raise ValueError("wrong handwritten date: " + row["case_id"])
            row["audit"].update(temporal_phrase=phrase, expected_date=expected)
        elif phrase.startswith("search:"):
            if payload["intent"] != "calendar_search":
                raise ValueError("lookup literal has wrong intent")
            day = phrase.split(":", 1)[1]
            midnight = now.replace(hour=0, minute=0)
            if day == "none":
                expected = {}
            elif day in ("завтра", "сегодня"):
                # The descriptor is manually reviewed. Check the stated day
                # and independently calculate its half-open search interval.
                if not re.search(r"\b" + day, user, re.I):
                    raise ValueError("missing search day")
                start = midnight + timedelta(days=1) if day == "завтра" else now
                end = midnight + timedelta(days=2 if day == "завтра" else 1)
                expected = dict(range_start=start.isoformat(timespec="minutes"),
                                range_end=end.isoformat(timespec="minutes"))
            else:
                raise ValueError("unsupported search period")
            ranges = {k: v for k, v in payload["params"].items() if k.startswith("range_")}
            if ranges != expected:
                raise ValueError("wrong handwritten search interval: " + row["case_id"])
            row["audit"] = dict(anchor_time=now.isoformat(timespec="minutes"),
                                temporal_phrase=phrase, expected_search_range=expected)
        else:
            audit_literal(row, phrase)
        intent = payload["intent"]
        if bool(REMINDER.search(user)) != (intent == "calendar_add"):
            raise ValueError("new literal reminder routing differs: " + row["case_id"])
        row["audit"]["routing_family"] = {
            "calendar_add": "reminder_create", "calendar_search": "lookup", "chat": "ordinary_chat"
        }[intent]
        rows.append(row)
    return rows


def repair_inputs(source):
    outputs = deepcopy(source)
    changes, seen = [], set()
    for repair in read(MANUAL / "input_repairs.json"):
        split, line = repair["split"], repair["line"]
        if (split, line) in seen:
            raise ValueError("duplicate input repair")
        seen.add((split, line))
        row = outputs[split][line - 1]
        if row["messages"][1]["content"] != repair["before"] or answer(row)["intent"] != "chat":
            raise ValueError(f"repair source differs: {split}:{line}")
        if not REMINDER.search(repair["before"]) or REMINDER.search(repair["after"]):
            raise ValueError("invalid reminder wording repair")
        row["messages"][1]["content"] = repair["after"]
        changes.append({**repair, "case_id": row.get("case_id"),
                        "assistant_sha256": hashlib.sha256(row["messages"][-1]["content"].encode("utf-8")).hexdigest()})
    return outputs, changes


def routing_audit(rows):
    verbs, lookup, intents, bad = Counter(), Counter(), Counter(), []
    for n, row in enumerate(rows, 1):
        payload = answer(row)
        intent = payload["intent"]
        intents[intent] += 1
        user = row["messages"][1]["content"]
        if REMINDER.search(user):
            verbs[intent] += 1
        if LOOKUP.search(user):
            lookup[intent] += 1
        if intent != "calendar_add" and (REMINDER.search(user) or REMINDER.search(payload["reply"])):
            bad.append(dict(line=n, case_id=row.get("case_id"), intent=intent))
    return dict(intents=dict(sorted(intents.items())), reminder_verbs=dict(sorted(verbs.items())),
                lookup_verbs=dict(sorted(lookup.items())), violations=bad)


def inherited_audit(source, outputs, changes):
    allowed = {(c["split"], c["line"]) for c in changes}
    unchanged, changed, chat, lookup = 0, 0, 0, 0
    for split, rows in source.items():
        for line, before in enumerate(rows, 1):
            after = outputs[split][line - 1]
            check = deepcopy(after)
            check["messages"][1]["content"] = before["messages"][1]["content"]
            if check != before:
                raise ValueError(f"inherited answer/context/metadata changed: {split}:{line}")
            if before != after:
                if (split, line) not in allowed:
                    raise ValueError("unapproved inherited input change")
                changed += 1
            else:
                unchanged += 1
            chat += answer(before)["intent"] == "chat"
            if LOOKUP.search(before["messages"][1]["content"]):
                if before != after:
                    raise ValueError("inherited lookup example changed")
                lookup += 1
    if changed != len(changes):
        raise ValueError("input repair count differs")
    return dict(unchanged_rows=unchanged, reworded_inputs=changed,
                assistant_messages_preserved=unchanged + changed,
                inherited_chat_rows_preserved=chat, inherited_lookup_rows_preserved=lookup,
                rows_deleted=0)


def assemble(check_only=False):
    lock = read(MANUAL / "source_lock.json")["files"]
    for rel, sha in lock.items():
        if digest(ROOT / rel) != sha:
            raise ValueError("protected source changed: " + rel)
    source = {p.stem: [json.loads(line) for line in p.read_bytes().splitlines()]
              for p in sorted(SOURCE.glob("*.jsonl"))}
    outputs, changes = repair_inputs(source)
    preservation = inherited_audit(source, outputs, changes)
    additions = dict(train=literals("add_train") + literals("search_train"),
                     validation=literals("validation"), holdout=literals("holdout"))
    outputs["train"].extend(additions["train"])
    outputs["validation"].extend(additions["validation"])
    outputs["intent_holdout"] = additions["holdout"]
    validate_partitions({s: [core_record(r) for r in rows] for s, rows in outputs.items()})
    # New evaluation names, including search filters, must be separate from
    # training and from earlier validation. This is not a language-coverage claim.
    def names(rows):
        values = set()
        for row in rows:
            p = answer(row)["params"]
            values.update(norm(v) for v in (p.get("title"), p.get("query"), p.get("target", {}).get("query")) if v)
        return values
    prior = names(source["train"] + source["validation"])
    titles = {split: names(rows) for split, rows in additions.items()}
    if (titles["validation"] & (prior | titles["train"]) or
            titles["holdout"] & (prior | titles["train"] | titles["validation"])):
        raise ValueError("new evaluation names overlap")
    # Preserve the development suite, applying a correction only to the exact
    # same context/query if a corrected source row is present there.
    dev = deepcopy(read(SOURCE / "generation_dev.json"))
    fixes = {(source[c["split"]][c["line"] - 1]["messages"][0]["content"], c["before"]): c["after"] for c in changes}
    for row in dev:
        key = (row["messages"][0]["content"], row["messages"][1]["content"])
        if key in fixes:
            row["messages"][1]["content"] = fixes[key]
    dev += [core_record(r) for r in additions["validation"]]
    index = deepcopy(read(SOURCE / "audit_index.json"))
    index.update({r["case_id"]: {**r["audit"], "literal_reference": r["literal_reference"], "split": split}
                  for split, rows in additions.items() for r in rows})
    reply_audits = {s: audit_rows(rows) for s, rows in {**outputs, "generation_dev": dev}.items()}
    routing = {s: routing_audit(rows) for s, rows in {**outputs, "generation_dev": dev}.items()}
    if any(a["failures"] for a in reply_audits.values()):
        raise ValueError("inconsistent known-time reference reply")
    if any(a["violations"] for a in routing.values()):
        raise ValueError("reminder verb outside calendar_add: " + json.dumps(routing, ensure_ascii=False))
    artifacts = {s: dict(rows=len(rows), sha256=hashlib.sha256(jsonl(rows)).hexdigest()) for s, rows in outputs.items()}
    report = dict(version="v12.66", contract_version="v12.57", artifacts=artifacts,
                  inherited_rows=sum(map(len, source.values())), additions={s: len(rows) for s, rows in additions.items()},
                  corrections=dict(Counter(c["split"] for c in changes)), preservation=preservation,
                  manual_sha256={p.name: digest(p) for p in sorted(MANUAL.iterdir()) if p.is_file()},
                  rules_sha256={p: digest(ROOT / p) for p in RULES}, protected_files_checked=len(lock),
                  development_rows=len(dev), inherited_clock_grid=1440, exhaustive_language_coverage=False,
                  training="NOT_RUN", inference="NOT_RUN")
    provenance = deepcopy(read(SOURCE / "provenance.json"))
    provenance.update(dataset_id="aiassistent1-v12.66-intent-routing",
                      artifacts={s: {"sha256": artifacts[s]["sha256"]} for s in ("train", "validation")})
    provenance["review"].update(reviewed_on="2026-10-04", decision_evidence="Owner confirmed manual V12.66 preparation and clarified: reminder verbs only in calendar_add, preserve date/time chat and lookup verbs. Literal requests and JSON were reviewed; automated checks cover contracts, routing and temporal arithmetic, not model quality.")
    provenance["records"][0].update(source_reference="workspace:docs/calendar_sft_v12_65/provenance.json and docs/calendar_v12_66_manual",
                                      rights_evidence="Authorized inherited fictional conversations plus handwritten conversations and four literal input replacements; no external calendar records or generated Russian sentences.")
    metadata = {"manifest.json": report, "provenance.json": provenance, "audit_index.json": index,
                "generation_dev.json": dev, "input_correction_log.json": changes, "routing_audit.json": routing,
                "reply_audit.json": reply_audits, "clock_coverage_index.json": read(SOURCE / "clock_coverage_index.json")}
    values = {s + ".jsonl": jsonl(rows) for s, rows in outputs.items()}
    values.update({name: (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8") for name, value in metadata.items()})
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
        print({p.stem: len(literals(p.stem)) for p in sorted(MANUAL.glob("*.txt"))})
    else:
        result = assemble(args.check_only)
        print(json.dumps({k: result[k] for k in ("version", "additions", "corrections", "preservation", "protected_files_checked")}, ensure_ascii=False))
