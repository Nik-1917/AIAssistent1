"""Validate and serialize handwritten V12.64 records without generating text."""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from datetime import datetime, timedelta
import hashlib
from itertools import permutations
import json
from pathlib import Path
import re

from dataset_contract import normalize_record, parse_and_validate_assistant_response
from prepare_v12_61 import NUMBERS, norm, number, compact_clock, validate_partitions
from prepare_v12_63 import full_clocks, relative_clocks
from v12_64_evaluation import compare_clock_reply, expected_clocks

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_v12_63"
MANUAL = ROOT / "docs/calendar_v12_64_manual"
OUTPUT = ROOT / "docs/calendar_sft_v12_64"
WEEKDAYS = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
COUNTS = {**NUMBERS, "шестьдесят": 60, "семьдесят": 70, "восемьдесят": 80,
          "девяносто": 90, "сто": 100, "двести": 200}
NUMBER_SPAN = r"(?:\d+|" + "|".join(sorted(COUNTS, key=len, reverse=True)) + r")(?: (?:" + "|".join(sorted(COUNTS, key=len, reverse=True)) + r")){0,2}"
DURATION = re.compile(r"\bна (?P<span>полчаса|четверть часа|половину часа|полтора часа|(?:" + NUMBER_SPAN + r") (?:часов|часа|час|минуту|минуты|минут)|час|минуту)\b")
VALUE = re.compile(r"\bзначение (?P<span>" + NUMBER_SPAN + r")\b")


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def core_record(row):
    return {k: v for k, v in row.items() if k in ("category", "case_id", "contract_version", "messages")}


def amount(text):
    text = norm(text)
    if text.isdigit():
        return int(text)
    # Reject malformed chains instead of summing arbitrary number words.
    if text.startswith("сто "):
        return 100 + amount(text[4:])
    return number(text, COUNTS)


def elapsed_minutes(phrase):
    """Parse only the reviewed offset span, independently from its JSON label."""
    phrase = norm(phrase)
    reversed_order = re.fullmatch(r"(минуту|минуты|минут|час|часа|часов) через (.+)", phrase)
    if reversed_order:
        count = amount(reversed_order[2])
        if count <= 0:
            raise ValueError("offset must be positive")
        return count * (60 if reversed_order[1].startswith("час") else 1)
    if not phrase.startswith("через "):
        raise ValueError(f"not an elapsed offset: {phrase}")
    value = phrase[6:]
    specials = {"минуту": 1, "час": 60, "полчаса": 30, "половину часа": 30,
                "четверть часа": 15, "полтора часа": 90, "полторы минуты": None}
    if value in specials:
        if specials[value] is None:
            raise ValueError("fractional minutes are outside the minute-resolution contract")
        return specials[value]
    half = re.fullmatch(r"(.+) с половиной часа", value)
    if half:
        return amount(half[1]) * 60 + 30
    if value.startswith("час "):
        return 60 + elapsed_minutes("через " + value[4:].removeprefix("и "))
    total, end, units_seen = 0, 0, set()
    for match in re.finditer(r"(.+?) (часов|часа|час|минуту|минуты|минут)(?: и | |$)", value):
        if match.start() != end:
            raise ValueError(f"unconsumed offset: {value}")
        unit = "hours" if match[2].startswith("час") else "minutes"
        if unit in units_seen:
            raise ValueError(f"duplicate offset unit: {value}")
        units_seen.add(unit)
        total += (1 if match[1] == "один" else amount(match[1])) * (60 if unit == "hours" else 1)
        end = match.end()
    if end != len(value) or total <= 0:
        raise ValueError(f"unrecognized offset: {phrase}")
    return total


def anchor_time(system):
    match = re.fullmatch(r"Сегодня дата и время:(\d{4}-\d{2}-\d{2}) \((\S+)\) (\d{2}:\d{2}) Europe/Samara ответ JSON", system)
    if not match:
        raise ValueError(f"unrecognized training system format: {system}")
    now = datetime.fromisoformat(match[1] + "T" + match[3])
    if WEEKDAYS[now.weekday()] != match[2]:
        raise ValueError(f"wrong weekday: {system}")
    return now


def audit_literal(row, phrase):
    user = row["messages"][1]["content"]
    raw = row["messages"][-1]["content"]
    normalize_record(core_record(row), row["case_id"])
    a = parse_and_validate_assistant_response(raw, contract_version="v12.57")
    now = anchor_time(row["messages"][0]["content"])
    p = a["params"]
    audit = {"anchor_time": now.isoformat(timespec="minutes"), "temporal_phrase": phrase}
    if phrase not in ("?", "chat"):
        span = phrase.removeprefix("@")
        if norm(user).count(norm(span)) != 1:
            raise ValueError(f"temporal span missing or duplicated: {row['case_id']}")
        if phrase.startswith("@"):
            clocks = full_clocks(span)
            if not clocks:
                digital = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)\b", span)
                if digital:
                    clocks = [tuple(map(int, digital.groups()))]
                else:
                    try:
                        clocks = [compact_clock(span)]
                    except ValueError:
                        clocks = relative_clocks(span)
            if len(clocks) != 1:
                raise ValueError("expected a single absolute input clock")
            target = now.replace(hour=clocks[0][0], minute=clocks[0][1])
            if re.search(r"\bзавтра\b", norm(user)):
                target += timedelta(days=1)
            elif target < now and not re.search(r"\bсегодня\b", norm(user)):
                target += timedelta(days=1)
            audit["input_clock"] = target.strftime("%H:%M")
        else:
            offset = elapsed_minutes(phrase)
            target = now + timedelta(minutes=offset)
            audit["offset_minutes"] = offset
        if a["intent"] != "calendar_add" or p.get("starts_at") != target.isoformat(timespec="minutes"):
            raise ValueError(f"incorrect handwritten target {row['case_id']}: expected {target}, got {p}")
    elif phrase == "?":
        if a["intent"] != "calendar_add" or any(k in p for k in ("starts_at", "time", "ends_at")):
            raise ValueError("a duration-only contrast must not invent a start time")
    elif a["intent"] != "chat":
        raise ValueError("a time question is not an event command")
    if a["intent"] == "calendar_add":
        text = norm(user)
        title = norm(p.get("title", "__NO_TITLE__"))
        field_text = text.replace(title, "event", 1)
        durations = list(DURATION.finditer(field_text))
        values = list(VALUE.finditer(field_text))
        if len(durations) > 1 or len(values) > 1:
            raise ValueError(f"duplicate field declaration: {row['case_id']}")
        duration = elapsed_minutes("через " + durations[0]["span"]) if durations else None
        value = amount(values[0]["span"]) if values else None
        if p.get("duration_min") != duration or p.get("value") != value:
            raise ValueError(f"duration/value differs from literal input: {row['case_id']}")
        meaning, style, _ = compare_clock_reply(a["reply"], a, expected_clocks(a))
        if meaning is False or style is False:
            raise ValueError(f"handwritten reply clock/style differs: {row['case_id']}: {a['reply']}")
        if p.get("title") and norm(p["title"]) not in norm(a["reply"]):
            raise ValueError(f"reply lost title: {row['case_id']}")
        if title in text and not phrase.startswith("@") and phrase not in ("?", "chat"):
            positions = [(text.index(title), "title"), (text.index(norm(phrase)), "offset")]
            if durations:
                positions.append((DURATION.search(text).start(), "duration"))
            if values:
                positions.append((VALUE.search(text).start(), "value"))
            audit["field_order"] = "-".join(label for _, label in sorted(positions))
    row["audit"] = audit
    return row


def literals(name):
    contexts = read(MANUAL / "contexts.json")
    result = []
    for n, line in enumerate((MANUAL / (name + ".txt")).read_text(encoding="utf-8").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        key, context, phrase, user, raw = line.split("|", 4)
        row = dict(case_id="V1264" + key, category="v12_64_" + name, contract_version="v12.57",
                   messages=[dict(role="system", content=contexts[context]), dict(role="user", content=user),
                             dict(role="assistant", content=raw)],
                   literal_reference=f"docs/calendar_v12_64_manual/{name}.txt:{n}")
        result.append(audit_literal(row, phrase))
    return result


def verify_protected():
    files = read(MANUAL / "source_lock.json")["files"]
    changed = [p for p, sha in files.items() if not (ROOT / p).is_file() or digest(ROOT / p) != sha]
    if changed:
        raise ValueError(f"protected source changed: {changed}")
    return len(files)


def jsonl(rows):
    return "".join(json.dumps(core_record(r), ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows).encode("utf-8")


def assemble(check_only=False):
    protected = verify_protected()
    source = {p.stem: [json.loads(line) for line in p.read_bytes().splitlines()] for p in SOURCE.glob("*.jsonl")}
    outputs = deepcopy(source)
    extra = [r for path in sorted(MANUAL.glob("*_train.txt")) for r in literals(path.stem)]
    outputs["train"].extend(extra)
    outputs["validation"].extend(literals("validation"))
    outputs["offset_reply_holdout"] = literals("holdout")
    validate_partitions({s: [core_record(r) for r in rows] for s, rows in outputs.items()})
    # New evaluation wording and titles are also disjoint from each other.
    additions = {"train": extra, "validation": literals("validation"), "holdout": literals("holdout")}
    audit_index = {r["case_id"]: {**r["audit"], "literal_reference": r["literal_reference"], "split": split}
                   for split, rows in additions.items() for r in rows}
    seen = set()
    for split, rows in additions.items():
        keys = {norm(r["messages"][1]["content"]).rstrip(".!?") for r in rows}
        if len(keys) != len(rows) or keys & seen:
            raise ValueError(f"duplicate new prompt across splits: {split}")
        seen |= keys
    training_titles = {norm(json.loads(r["messages"][-1]["content"])["params"].get("title", "")) for r in outputs["train"]} - {""}
    validation_titles = {norm(json.loads(r["messages"][-1]["content"])["params"].get("title", "")) for r in additions["validation"]} - {""}
    holdout_titles = {norm(json.loads(r["messages"][-1]["content"])["params"].get("title", "")) for r in additions["holdout"]} - {""}
    if (training_titles & validation_titles or training_titles & holdout_titles or validation_titles & holdout_titles):
        raise ValueError("new validation/holdout titles overlap training or each other")
    minute_rows = literals("minutes_train")
    hour_rows = literals("hours_train")
    mixed_rows = literals("mixed_train")
    minute_values = {r["audit"]["offset_minutes"] for r in minute_rows}
    hour_values = {r["audit"]["offset_minutes"] // 60 for r in hour_rows}
    if not set(range(1, 60)) <= minute_values or not set(range(1, 25)) <= hour_values:
        raise ValueError("incomplete minute/hour offset coverage")
    mixed = {divmod(r["audit"]["offset_minutes"], 60) for r in mixed_rows}
    if not set(range(1, 60)) <= {m for h, m in mixed} or not set(range(1, 25)) <= {h for h, m in mixed}:
        raise ValueError("mixed offsets do not cover every reviewed hour/minute value")
    orders = Counter(r["audit"].get("field_order", "") for r in literals("order_train"))
    if set(orders) != {"-".join(p) for p in permutations(("title", "offset", "duration", "value"))}:
        raise ValueError("incomplete four-field ordering matrix")
    three_orders = {r["audit"].get("field_order") for r in literals("contrast_train") if r["case_id"] in {f"V1264R{n}" for n in range(25, 31)}}
    if three_orders != {"-".join(p) for p in permutations(("title", "offset", "duration"))}:
        raise ValueError("incomplete three-field ordering matrix")
    for split, rows in source.items():
        if outputs[split][:len(rows)] != rows:
            raise ValueError(f"inherited records changed: {split}")
    artifacts = {s: dict(rows=len(rows), sha256=hashlib.sha256(jsonl(rows)).hexdigest()) for s, rows in outputs.items()}
    development = read(SOURCE / "generation_dev.json") + [core_record(r) for r in additions["validation"]]
    clock_index = read(SOURCE / "clock_coverage_index.json")
    if len(clock_index) != 1440:
        raise ValueError("inherited clock grid is incomplete")
    for source_path in SOURCE.glob("*.jsonl"):
        if not jsonl(outputs[source_path.stem]).startswith(source_path.read_bytes()):
            raise ValueError(f"source prefix is not byte-identical: {source_path.name}")
    report = dict(version="v12.64", contract_version="v12.57", artifacts=artifacts,
                  inherited_rows=sum(map(len, source.values())), protected_files_checked=protected,
                  additions={k: len(v) for k, v in additions.items()},
                  categories=dict(Counter(r["category"] for r in extra)),
                  coverage=dict(minute_offsets=sorted(minute_values), hour_offsets=sorted(hour_values),
                                mixed_pairs=[list(pair) for pair in sorted(mixed)], mixed_full_cartesian=False,
                                inherited_clock_grid=1440, four_field_orders=dict(orders),
                                three_field_orders=sorted(three_orders), new_eval_titles_disjoint=True),
                  manual_sha256={p.name: digest(p) for p in sorted(MANUAL.iterdir()) if p.is_file()},
                  training="NOT_RUN", inference="NOT_RUN")
    if check_only:
        for split, rows in outputs.items():
            if (OUTPUT / (split + ".jsonl")).read_bytes() != jsonl(rows):
                raise ValueError(f"reassembly differs: {split}")
        if read(OUTPUT / "manifest.json") != report:
            raise ValueError("manifest differs")
        if read(OUTPUT / "audit_index.json") != audit_index:
            raise ValueError("audit index differs")
        if read(OUTPUT / "generation_dev.json") != development or read(OUTPUT / "clock_coverage_index.json") != clock_index:
            raise ValueError("development or clock index differs")
        return report
    if OUTPUT.exists():
        raise ValueError("output exists; refusing to overwrite a frozen dataset")
    OUTPUT.mkdir()
    for split, rows in outputs.items():
        (OUTPUT / (split + ".jsonl")).write_bytes(jsonl(rows))
    (OUTPUT / "manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "audit_index.json").write_text(json.dumps(audit_index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "generation_dev.json").write_text(json.dumps(development, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "clock_coverage_index.json").write_text(json.dumps(clock_index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    provenance = deepcopy(read(SOURCE / "provenance.json"))
    provenance.update(dataset_id="aiassistent1-v12.64-offset-reply", artifacts={k: {"sha256": artifacts[k]["sha256"]} for k in ("train", "validation")})
    provenance["review"].update(reviewed_on="2026-10-03", decision_evidence="Owner confirmed the offset/reply data and evaluation plan in this chat. New fictional conversations are literal authored examples with arithmetic and contract audits; no external calendar data.")
    provenance["records"][0].update(source_reference="workspace:docs/calendar_sft_v12_63/provenance.json and docs/calendar_v12_64_manual", rights_evidence="Inherited approved fictional data and new complete literal fictional conversations. The serializer never generates Russian user or reply text.")
    (OUTPUT / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
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
        print(json.dumps({k: result[k] for k in ("version", "additions", "protected_files_checked", "training", "inference")}, ensure_ascii=False))
