"""Copy handwritten reply literals only; never compose Russian training text."""
from collections import Counter
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
MANUAL = ROOT / "docs/calendar_v12_67_manual"
BASELINE = MANUAL / "reply_baseline_20261010"
BASE_DATA = BASELINE / "docs/calendar_sft_v12_67"
PREFIX = "Новое событие: "
UNNAMED = "Создаём событие без названия."
DESIGN_VERSION = "v12.67-title-reply-1"
BASELINE_SNAPSHOT_SHA256 = "e64d9daa9f8af61dffde9e0c23d084a89ff90169ef76c2bcaa979e57531ef4ad"
REPLY_VALUE = re.compile(r'("reply"\s*:\s*)("(?:[^"\\]|\\.)*")')


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def digest(path):
    return sha256(Path(path).read_bytes()).hexdigest()


def replace_reply(content, old, new):
    matches = list(REPLY_VALUE.finditer(content))
    if len(matches) != 1 or json.loads(matches[0][2]) != old:
        raise ValueError("The exact original reply token could not be identified")
    match = matches[0]
    result = content[:match.start(2)] + json.dumps(new, ensure_ascii=False) + content[match.end(2):]
    before, after = json.loads(content), json.loads(result)
    before["reply"] = new
    if before != after:
        raise ValueError("A reply replacement changed another response field")
    return result


class ReplyReview:
    def __init__(self):
        if digest(BASELINE / "snapshot.json") != BASELINE_SNAPSHOT_SHA256:
            raise ValueError("The approved reply baseline manifest changed")
        snapshot = read(BASELINE / "snapshot.json")
        for name, expected in snapshot["files"].items():
            if digest(BASELINE / name) != expected:
                raise ValueError("Reply baseline changed: " + name)
        # The preceding residual cleanup belongs to the baseline and must survive.
        for name in ("exclusions.json",):
            if digest(MANUAL / name) != digest(BASELINE / "docs/calendar_v12_67_manual" / name):
                raise ValueError("The reply revision must not change retained positions")
        literals, references, literal_hashes = {}, {}, {}
        for path in sorted((MANUAL / "reply_literals").glob("*.txt")):
            literal_hashes[path.relative_to(ROOT).as_posix()] = digest(path)
            for line, reply in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if not reply.startswith(PREFIX) or not reply.endswith("."):
                    raise ValueError("Invalid handwritten reply: " + str(path))
                title = reply[len(PREFIX):-1]
                if not title or title in literals:
                    raise ValueError("Empty or duplicate handwritten title")
                literals[title] = reply
                references[title] = dict(file=path.relative_to(ROOT).as_posix(), line=line)
        unnamed_path = MANUAL / "reply_unnamed.txt"
        unnamed = unnamed_path.read_text(encoding="utf-8").removesuffix("\n")
        if unnamed != UNNAMED:
            raise ValueError("The unnamed reply differs from the approved wording")
        literal_hashes[unnamed_path.relative_to(ROOT).as_posix()] = digest(unnamed_path)
        multiple_path = MANUAL / "reply_multiple_events.json"
        multiple = {}
        for index, item in enumerate(read(multiple_path), 1):
            title = item["title"]
            if title in multiple or not item["reply"].startswith(literals[title] + " "):
                raise ValueError("Invalid handwritten multiple-event reply")
            multiple[title] = (item["reply"], dict(file=multiple_path.relative_to(ROOT).as_posix(), item=index))
        literal_hashes[multiple_path.relative_to(ROOT).as_posix()] = digest(multiple_path)
        retention = read(BASE_DATA / "retention_index.json")
        files = sorted(BASE_DATA.glob("*.jsonl")) + [BASE_DATA / "generation_dev.json"]
        self.edits, self.baseline_rows, self.records = {}, {}, []
        seen_titles, seen_multiple, counts = set(), set(), Counter()
        for path in files:
            split = path.stem
            rows = read(path) if path.suffix == ".json" else [json.loads(s) for s in path.read_text(encoding="utf-8").splitlines()]
            positions = retention[split]["positions"]
            if len(rows) != len(positions):
                raise ValueError("Baseline row positions differ")
            for output_line, (row, position) in enumerate(zip(rows, positions), 1):
                key = split, position["source_line"]
                self.baseline_rows[key] = row
                payload = json.loads(row["messages"][-1]["content"])
                if payload["intent"] != "calendar_add":
                    continue
                title = payload["params"].get("title")
                warning = "не обработан" in payload["reply"]
                if warning:
                    new, reference = multiple[title]
                    # Preserve the existing warning literally, including event names.
                    if new[len(literals[title]) + 1:] != payload["reply"].split(". ", 1)[1]:
                        raise ValueError("The handwritten warning changed its meaning")
                    seen_multiple.add(title)
                    counts["multiple_event_rows"] += 1
                elif title:
                    new, reference = literals[title], references[title]
                else:
                    new = unnamed
                    reference = dict(file=unnamed_path.relative_to(ROOT).as_posix(), line=1)
                    counts["unnamed_rows"] += 1
                if title:
                    seen_titles.add(title)
                if new == payload["reply"]:
                    raise ValueError("Expected an old-format baseline reply")
                record = dict(split=split, source_line=position["source_line"], baseline_line=output_line,
                              case_id=row.get("case_id"), baseline_messages_sha256=position["messages_sha256"],
                              old_reply=payload["reply"], new_reply=new, literal=reference)
                self.edits[key] = record
                self.records.append(record)
                counts[split] += 1
        if seen_titles != set(literals) or seen_multiple != set(multiple):
            raise ValueError("Manual literals do not exactly cover the baseline")
        if len(self.edits) != 5681 or counts["unnamed_rows"] != 92 or counts["multiple_event_rows"] != 27:
            raise ValueError("The approved reply revision has a different scope")
        self.info = dict(version=DESIGN_VERSION, handwritten_named_literals=len(literals),
                         handwritten_multiple_event_literals=len(multiple), changed_replies=len(self.edits),
                         counts=dict(counts), user_messages_changed=0, params_changed=0,
                         other_actions_changed=0, snapshot_files_checked=len(snapshot["files"]),
                         baseline_snapshot_sha256=digest(BASELINE / "snapshot.json"), literal_sha256=literal_hashes)

    def apply(self, row, split, source_line):
        key = split, source_line
        baseline = self.baseline_rows[key]
        if row["messages"] != baseline["messages"] or row["category"] != baseline["category"]:
            raise ValueError("A retained conversation differs from the reply baseline")
        result = deepcopy(row)
        item = self.edits.get(key)
        if item:
            result["messages"][-1]["content"] = replace_reply(
                row["messages"][-1]["content"], item["old_reply"], item["new_reply"])
        return result
