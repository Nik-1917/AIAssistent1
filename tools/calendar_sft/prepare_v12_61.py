"""Assemble literal V12.61 conversations; never generate linguistic content."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
import re

from dataset_contract import normalize_record, parse_and_validate_assistant_response
from dataset_provenance import verify_dataset_provenance

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/calendar_sft_order_coverage"
MANUAL = ROOT / "docs/calendar_v12_61_manual"
OUTPUT = ROOT / "docs/calendar_sft_v12_61"
WEEKDAYS = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
CARDINAL = ("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
NUMBERS = {word: n for n, word in enumerate(CARDINAL)}
NUMBERS.update({"час": 1, "одна": 1, "одну": 1, "две": 2, "двадцать": 20, "тридцать": 30, "сорок": 40, "пятьдесят": 50})
GENITIVE = {"одной": 1, "двух": 2, "трех": 3, "четырех": 4, "пяти": 5, "шести": 6, "семи": 7, "восьми": 8, "девяти": 9, "десяти": 10, "одиннадцати": 11, "двенадцати": 12, "тринадцати": 13, "четырнадцати": 14, "пятнадцати": 15, "шестнадцати": 16, "семнадцати": 17, "восемнадцати": 18, "девятнадцати": 19, "двадцати": 20}
ORDINAL = {word: n for n, word in enumerate(("первого", "второго", "третьего", "четвертого", "пятого", "шестого", "седьмого", "восьмого", "девятого", "десятого", "одиннадцатого", "двенадцатого"), 1)}
# Only numerical context metadata is shared. All user/reply sentences are literal.
CONTEXT_DATES = (
    "2026-09-30", "2027-01-31", "2028-02-28", "2026-04-30",
    "2027-12-31", "2028-06-30", "2026-08-31", "2027-10-31",
    "2028-03-31", "2026-11-30", "2027-05-31", "2028-07-31",
    "2026-10-12", "2027-03-17", "2028-02-29", "2026-06-14",
    "2027-08-22", "2028-01-19", "2026-12-27", "2027-04-18",
    "2028-09-16", "2026-05-23", "2027-11-24", "2028-12-31",
)
CONTEXT_TIMES = ("06:10", "13:20", "22:45", "10:05", "18:30", "00:15")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def norm(value):
    return re.sub(r"\s+", " ", value.lower().replace("ё", "е")).strip()


def number(words, mapping=NUMBERS):
    words = words.split() if isinstance(words, str) else words
    if len(words) == 1 and words[0] in mapping:
        return mapping[words[0]]
    if len(words) == 2 and mapping.get(words[0], -1) >= 20 and 1 <= mapping.get(words[1], -1) <= 9:
        return mapping[words[0]] + mapping[words[1]]
    raise ValueError(f"unrecognized number: {words}")


def compact_clock(user):
    """Read number-word spans independently of declared target numbers."""
    found = []
    words = re.findall(r"[а-я]+", norm(user))
    spans = []
    start = 0
    while start < len(words):
        if words[start] not in NUMBERS:
            start += 1
            continue
        end = start
        while end < len(words) and words[end] in NUMBERS:
            end += 1
        spans.append(words[start:end])
        start = end
    for tokens in spans:
        candidates = []
        for boundary in (1, 2):
            try:
                hour = number(tokens[:boundary])
            except ValueError:
                continue
            if not 0 <= hour <= 23:
                continue
            for length in (1, 2, 3):
                minute_words = tokens[boundary:boundary + length]
                if len(minute_words) != length:
                    continue
                if len(minute_words) > 1 and minute_words[0] == "ноль":
                    minute_words = minute_words[1:]
                try:
                    minute = number(minute_words)
                except ValueError:
                    continue
                if 0 <= minute <= 59:
                    candidates.append((boundary + length, hour, minute))
        if candidates:
            longest = max(n for n, _, _ in candidates)
            if longest != len(tokens):
                raise ValueError(f"unconsumed clock number words: {user}")
            meanings = {(h, m) for n, h, m in candidates if n == longest}
            if len(meanings) != 1:
                raise ValueError(f"ambiguous compact clock: {user}")
            found.append(next(iter(meanings)))
    if len(found) != 1:
        raise ValueError(f"expected exactly one compact clock: {user}")
    return found[0]


def reply_clock(reply):
    """Validate handwritten clock speech, not synthesize a target reply."""
    text = norm(reply).rstrip(".")
    if re.search(r"\b(?:в|с|до) без\b", text):
        raise ValueError(f"invalid preposition before minutes-to clock: {reply}")
    if " без " in text:
        phrase = text.rsplit(" без ", 1)[1]
        if phrase.startswith("четверти "):
            remaining, hour_words = 15, phrase[len("четверти "):]
        else:
            match = re.fullmatch(r"(.+?) минут(?:ы)? (.+)", phrase)
            if not match:
                raise ValueError(f"bad minutes-to phrase: {reply}")
            remaining, hour_words = number(match[1], GENITIVE), match[2]
        hour = number(hour_words)
        if not 1 <= remaining <= 29 or not 1 <= hour <= 12:
            raise ValueError(f"bad minutes-to clock: {reply}")
        return ((hour - 1) % 12, 60 - remaining)
    phrase = text.rsplit(" в ", 1)[-1]
    for prefix, minute in (("четверть ", 15), ("половине ", 30)):
        if phrase.startswith(prefix):
            return ((ORDINAL[phrase[len(prefix):]] - 1) % 12, minute)
    match = re.fullmatch(r"(.+?) минут(?:у|ы)? (.+)", phrase)
    if match and match[2] in ORDINAL:
        minute = number(match[1])
        if not 1 <= minute <= 29:
            raise ValueError(f"invalid minute-of-hour clock: {reply}")
        return ((ORDINAL[match[2]] - 1) % 12, minute)
    if phrase == "час":
        return (1, 0)
    match = re.fullmatch(r"(.+?) час(?:а|ов)?", phrase)
    if match:
        return (number(match[1]) % 12, 0)
    raise ValueError(f"unrecognized reply clock: {reply}")


def clock_rows(require_complete=True):
    rows, cells = [], set()
    for path in sorted(MANUAL.glob("clock_[0-2][0-9].txt")):
        file_hour = int(path.stem[-2:])
        for line, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not text.strip() or text.startswith("#"):
                continue
            columns = text.split("|")
            if len(columns) not in (4, 5):
                raise ValueError(f"{path}:{line}: expected four or five columns")
            clock, title, user, reply = columns[:4]
            offset = int(columns[4]) if len(columns) == 5 else 1
            hour, minute = map(int, clock.split(":"))
            if hour != file_hour or not 0 <= minute <= 59 or clock in cells:
                raise ValueError(f"{path}:{line}: duplicate or invalid clock")
            context_index = (hour + minute) % len(CONTEXT_DATES)
            today = date.fromisoformat(CONTEXT_DATES[context_index])
            system = f"Сегодня дата и время:{today} ({WEEKDAYS[today.weekday()]}) {CONTEXT_TIMES[context_index % 6]} Europe/Samara ответ JSON"
            if compact_clock(user) != (hour, minute):
                raise ValueError(f"{path}:{line}: input clock differs from {clock}")
            if reply_clock(reply) != (hour % 12, minute):
                raise ValueError(f"{path}:{line}: reply clock differs from {clock}")
            date_word = {0: "сегодня", 1: "завтра", 2: "послезавтра"}[offset]
            if date_word not in re.findall(r"[а-я]+", norm(user)):
                raise ValueError(f"{path}:{line}: missing declared date word")
            # Human accusative forms are legitimate, e.g. доставка -> доставку.
            title_forms = {norm(title), norm(title[:-1] + "у") if title.endswith("а") else norm(title)}
            if title.endswith("я"):
                title_forms.add(norm(title[:-1] + "ю"))
            if not any(form in norm(user) for form in title_forms):
                raise ValueError(f"{path}:{line}: title absent from human utterance")
            if any(word in norm(reply).split() for word in ("утра", "дня", "вечера", "ночи")):
                raise ValueError(f"{path}:{line}: clock daypart in reply")
            cells.add(clock)
            assistant = {"intent": "calendar_add", "reply": reply, "params": {"title": title, "starts_at": f"{today + timedelta(days=offset)}T{clock}"}}
            row = {"case_id": f"V1261T{hour:02d}{minute:02d}", "category": "v12_61_compact_all_minutes", "contract_version": "v12.57", "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}, {"role": "assistant", "content": json.dumps(assistant, ensure_ascii=False, separators=(",", ":"))}]}
            normalize_record(row, row["case_id"])
            rows.append(row)
    # Reuse previously handwritten whole-hour/five-minute examples instead of
    # inflating the corpus with another copy of an already covered clock cell.
    inherited = inherited_clock_cells()
    if cells & set(inherited):
        raise ValueError("new grid overlaps the explicitly selected inherited cells")
    covered = cells | set(inherited)
    if require_complete and covered != {f"{h:02d}:{m:02d}" for h in range(24) for m in range(60)}:
        raise ValueError(f"incomplete literal grid: {len(covered)}/1440")
    return rows


def inherited_clock_cells():
    cells = {}
    for line, text in enumerate((SOURCE / "train.jsonl").read_text(encoding="utf-8").splitlines(), 1):
        row = json.loads(text)
        if row["category"] not in {"v12_53_compact_clock", "v12_51_on_hour_compact"}:
            continue
        answer = json.loads(row["messages"][-1]["content"])
        clock = answer["params"]["starts_at"][-5:]
        hour, minute = map(int, clock.split(":"))
        if hour >= 4 and minute % 5 == 0:
            if clock in cells:
                raise ValueError(f"duplicate inherited clock: {clock}")
            cells[clock] = {"source_line": line, "case_id": row["case_id"]}
    if len(cells) != 240:
        raise ValueError(f"inherited grid changed: {len(cells)}/240")
    return cells


def user_key(row):
    return norm("\n".join(m["content"] for m in row["messages"] if m["role"] == "user")).strip(" .!?")


def speech_rows():
    """Copy complete authored conversations, including their literal answers."""
    contexts = read_json(MANUAL / "contexts.json")
    for key, context in contexts.items():
        match = re.fullmatch(r"Сегодня дата и время:(\d{4}-\d{2}-\d{2}) \(([^)]+)\) (\d{2}:\d{2}) Europe/Samara ответ JSON", context)
        if not match or WEEKDAYS[date.fromisoformat(match[1]).weekday()] != match[2]:
            raise ValueError(f"invalid authored system context: {key}")
    result, ids = defaultdict(list), set()
    for name, split in (("train", "train"), ("validation", "validation"), ("holdout", "speech_holdout")):
        pack = read_json(MANUAL / f"speech_{name}.json")
        if pack["split"] != split:
            raise ValueError(f"invalid authored split: {name}")
        for example in pack["examples"]:
            case_id = example["id"]
            if case_id in ids:
                raise ValueError(f"duplicate authored case id: {case_id}")
            ids.add(case_id)
            row = {"case_id": case_id, "category": "v12_61_" + example["skill"], "contract_version": "v12.57", "messages": [
                {"role": "system", "content": contexts[example["context"]]},
                {"role": "user", "content": example["user"]},
                {"role": "assistant", "content": json.dumps(example["assistant"], ensure_ascii=False, separators=(",", ":"))},
            ]}
            normalize_record(row, case_id)
            if example["skill"].startswith("ambiguous_"):
                params = example["assistant"]["params"]
                if any(key in params for key in ("time", "starts_at", "ends_at")):
                    raise ValueError(f"unresolved clock must not acquire a time: {case_id}")
            result[split].append(row)
    return dict(result)


def clock_coverage_index(train):
    inherited = inherited_clock_cells()
    old_ids = {meta["case_id"]: (clock, meta) for clock, meta in inherited.items()}
    index = {}
    for line, row in enumerate(train, 1):
        case_id = row.get("case_id", "")
        if not case_id.startswith("V1261T") and case_id not in old_ids:
            continue
        answer = json.loads(row["messages"][-1]["content"])
        clock = answer["params"]["starts_at"][-5:]
        hour, minute = map(int, clock.split(":"))
        if clock in index:
            raise ValueError(f"duplicate selected base cell: {clock}")
        user, reply = row["messages"][1]["content"], answer["reply"]
        # In inherited grid records duration is a separate final clause. Only
        # this known clause is removed for the clock check; no text is edited.
        if case_id in old_ids:
            if old_ids[case_id][0] != clock:
                raise ValueError(f"inherited target changed: {case_id}")
            user = user.rsplit(" на ", 1)[0]
            reply = reply.split(", на ", 1)[0].rstrip(" ,.")
        if compact_clock(user) != (hour, minute) or reply_clock(reply) != (hour % 12, minute):
            raise ValueError(f"selected clock meaning differs: {case_id}")
        index[clock] = {"case_id": case_id, "train_line": line, "origin": "inherited" if case_id in old_ids else "new_literal", "title": answer["params"]["title"], "system": row["messages"][0]["content"], "source": str(SOURCE.relative_to(ROOT) / "train.jsonl") if case_id in old_ids else str(MANUAL.relative_to(ROOT) / f"clock_{hour:02d}.txt")}
        if case_id in old_ids:
            index[clock]["source_line"] = old_ids[case_id][1]["source_line"]
    expected = {f"{h:02d}:{m:02d}" for h in range(24) for m in range(60)}
    if set(index) != expected:
        raise ValueError(f"release base clock coverage: {len(index)}/1440")
    return dict(sorted(index.items()))


def validate_partitions(outputs):
    for split, rows in outputs.items():
        ids, prompts = set(), set()
        for n, row in enumerate(rows, 1):
            normalize_record(row, f"{split}:{n}")
            parse_and_validate_assistant_response(row["messages"][-1]["content"], f"{split}:{n}", contract_version="v12.57")
            if [m["role"] for m in row["messages"]] != ["system", "user", "assistant"]:
                raise ValueError(f"runtime-incompatible history: {split}:{n}")
            case_id = row.get("case_id")
            if case_id and case_id in ids:
                raise ValueError(f"duplicate case id: {split}:{case_id}")
            ids.add(case_id)
            prompt = tuple(m["content"] for m in row["messages"][:-1])
            if prompt in prompts:
                raise ValueError(f"duplicate full prompt: {split}:{n}")
            prompts.add(prompt)
    seen = {user_key(row) for row in outputs["train"]}
    for split, rows in outputs.items():
        if split in ("train", "context_transfer_eval"):
            continue
        overlap = [n for n, row in enumerate(rows, 1) if user_key(row) in seen]
        if overlap:
            raise ValueError(f"training phrasing in {split}: {overlap}")


def grid_surface_report(rows):
    """Measure positions in literal text; never produce example sentences."""
    orders, dates, contexts_by_hour = Counter(), Counter(), defaultdict(set)
    for row in rows:
        if row.get("category") != "v12_61_compact_all_minutes":
            continue
        payload = json.loads(row["messages"][-1]["content"])
        user, title = norm(row["messages"][1]["content"]), norm(payload["params"]["title"])
        forms = {title}
        if title.endswith("а"):
            forms.add(title[:-1] + "у")
        if title.endswith("я"):
            forms.add(title[:-1] + "ю")
        title_pos = min(user.index(form) for form in forms if form in user)
        day = re.search(r"\b(сегодня|завтра|послезавтра)\b", user)
        time_pos = min(word.start() for word in re.finditer(r"[а-я]+", user) if word[0] in NUMBERS)
        order = "-".join(label for _, label in sorted(((title_pos, "title"), (day.start(), "date"), (time_pos, "clock"))))
        orders[order] += 1
        dates[day[0]] += 1
        contexts_by_hour[payload["params"]["starts_at"][-5:-3]].add(row["messages"][0]["content"])
    if len(orders) != 6 or len(contexts_by_hour) != 24 or any(len(v) != 24 for v in contexts_by_hour.values()):
        raise ValueError("literal grid lost word-order or context diversity")
    return {"word_orders": dict(sorted(orders.items())), "date_words": dict(dates), "distinct_contexts_per_hour": {k: len(v) for k, v in sorted(contexts_by_hour.items())}}


def assemble():
    manifest = read_json(SOURCE / "manifest.json")
    sources, outputs, changes, exclusions = {}, {}, [], []
    for name, meta in manifest["artifacts"].items():
        path = SOURCE / (name + ".jsonl")
        if digest(path) != meta["sha256"]:
            raise ValueError(f"source changed: {path}")
        sources[name] = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    corrected = deepcopy(sources)
    selected = set()
    for group in read_json(MANUAL / "query_repairs.json")["groups"]:
        for split in sources:
            for line in group.get(split, []):
                if (split, line) in selected:
                    raise ValueError(f"overlapping correction: {split}:{line}")
                selected.add((split, line))
                row = corrected[split][line - 1]
                answer = json.loads(row["messages"][-1]["content"])
                target = answer["params"] if group.get("path") == "query" else answer["params"]["target"]
                if target.get("query") != group["old"]:
                    raise ValueError(f"stale literal correction: {split}:{line}")
                target["query"] = group["new"]
                row["messages"][-1]["content"] = json.dumps(answer, ensure_ascii=False, separators=(",", ":"))
                normalize_record(row, f"{split}:{line}")
                changes.append({"source": split, "line": line, "old_query": group["old"], "new_query": group["new"], "user": [m["content"] for m in row["messages"] if m["role"] == "user"]})
    # Multi-message rows are retained for review, not silently relabelled using
    # information the calendar runtime no longer sends to the model.
    for split, rows in corrected.items():
        outputs[split] = []
        for line, row in enumerate(rows, 1):
            if sum(m["role"] == "user" for m in row["messages"]) > 1:
                exclusions.append({"source": split, "line": line, "reason": "calendar_runtime_sends_one_user_message", "record": row})
            else:
                outputs[split].append(row)
    known_users = {user_key(row) for row in outputs["train"]}
    transfer = [row for row in outputs["validation"] if user_key(row) in known_users]
    outputs["validation"] = [row for row in outputs["validation"] if user_key(row) not in known_users]
    outputs["context_transfer_eval"] = transfer
    outputs["train"].extend(clock_rows())
    for split, rows in speech_rows().items():
        outputs.setdefault(split, []).extend(rows)
    validate_partitions(outputs)
    clock_coverage_index(outputs["train"])
    grid_surface_report(outputs["train"])
    return outputs, changes, exclusions


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--partial-clocks", action="store_true")
    args = parser.parse_args()
    if args.partial_clocks:
        print(json.dumps({"validated_new_clocks": len(clock_rows(require_complete=False)), "inherited_cells": len(inherited_clock_cells())}))
        return
    outputs, changes, exclusions = assemble()
    index = clock_coverage_index(outputs["train"])
    summary = {"version": "v12.61", "rows": {k: len(v) for k, v in outputs.items()}, "manual_query_repairs": len(changes), "legacy_multiturn_retained_for_review": len(exclusions), "excluded_by_split": dict(Counter(r["source"] for r in exclusions)), "clock_cells": len(index), "clock_origins": dict(Counter(r["origin"] for r in index.values())), "speech_examples": {k: len(v) for k, v in speech_rows().items()}, "training": "NOT_RUN", "inference": "NOT_RUN"}
    if args.check_only:
        print(json.dumps(summary, ensure_ascii=False))
        return
    if OUTPUT.exists():
        raise ValueError("output already exists; refusing to overwrite")
    OUTPUT.mkdir()
    for split, rows in outputs.items():
        (OUTPUT / (split + ".jsonl")).write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows), encoding="utf-8")
    artifacts = {split: {"sha256": digest(OUTPUT / (split + ".jsonl"))} for split in outputs}
    write_json(OUTPUT / "manifest.json", {**summary, "source": str(SOURCE.relative_to(ROOT)), "source_sha256": {p.name: digest(p) for p in SOURCE.glob("*.jsonl")}, "manual_sha256": {p.name: digest(p) for p in MANUAL.glob("*.*")}, "artifacts": artifacts})
    write_json(OUTPUT / "correction_log.json", changes)
    write_json(OUTPUT / "legacy_multiturn_review.json", exclusions)
    write_json(OUTPUT / "clock_coverage_index.json", index)
    write_json(OUTPUT / "coverage_report.json", {**summary, "base_clock_hours": dict(sorted(Counter(k[:2] for k in index).items())), "base_clock_minutes": dict(sorted(Counter(k[3:] for k in index).items())), "base_clock_titles": len({r["title"] for r in index.values()}), "base_clock_contexts": len({r["system"] for r in index.values()}), "new_grid_surface": grid_surface_report(outputs["train"]), "intents": {s: dict(Counter(json.loads(r["messages"][-1]["content"])["intent"] for r in rs)) for s, rs in outputs.items()}, "categories": {s: dict(Counter(r["category"] for r in rs)) for s, rs in outputs.items()}})
    write_json(OUTPUT / "validation_report.json", {"status": "PASS", "rows": sum(map(len, outputs.values())), "contract": "v12.57", "base_clock_input_and_reply_matches": len(index), "runtime_single_user_message": "PASS", "duplicate_full_prompts_within_splits": 0, "training_wording_in_evaluation": 0, "context_transfer_eval_intentionally_overlaps_train": len(outputs["context_transfer_eval"]), "source_hashes": "PASS", "training": "NOT_RUN", "model_evaluation": "NOT_RUN", "gguf": "NOT_RUN", "device": "NOT_RUN"})
    write_json(OUTPUT / "provenance.json", {"format_version": 1, "status": "VERIFIED", "dataset_id": "aiassistent1-v12.61-manual-repair", "review": {"reviewed_by": "Codex under the project owner's explicit instruction", "reviewed_on": "2026-09-28", "decision_evidence": "Owner approved manual V12.61 repairs and all 1440 clock combinations; existing rules remain unchanged."}, "artifacts": {k: artifacts[k] for k in ("train", "validation")}, "records": [{"source_type": "internal_authored", "source_reference": "workspace:docs/calendar_sft_order_coverage/provenance.json and docs/calendar_v12_61_manual", "rights_evidence": "Existing approved project examples plus literal AI-authored fictional conversations manually written in this task; no external corpus or personal records.", "permits_model_training": True, "permits_derivative_weight_distribution": True, "contains_personal_data": False}]})
    verify_dataset_provenance(OUTPUT / "provenance.json", OUTPUT / "train.jsonl", OUTPUT / "validation.jsonl")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
