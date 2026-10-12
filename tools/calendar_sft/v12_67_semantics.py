"""Copy twelve reviewed parameter literals; never infer or generate training text."""
from collections import Counter
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import re

from v12_67_contract import SYSTEM_RE, normalize_record

ROOT = Path(__file__).resolve().parents[2]
REGISTER = ROOT / "docs/calendar_v12_67_manual/semantic_corrections.json"
REGISTER_SHA256 = "00a8eac1623af6735805d2165a62626632ccf2389c4bd7cff68aa39d7bb14d2c"
SCOPE = {"train": {82, 118, 178, 234, 239, 280, 325, 331, 397, 3485},
         "validation": {592}, "speech_holdout": {24}}


def replace_params(content, before, after, *, separators=None):
    """Replace one complete object, preserving reply and surrounding JSON bytes."""
    matches = list(re.finditer(r'"params"\s*:\s*', content))
    if len(matches) != 1:
        raise ValueError("Expected one literal params object")
    start = matches[0].end()
    found, end = json.JSONDecoder().raw_decode(content, start)
    if found != before:
        raise ValueError("The reviewed original params changed")
    result = content[:start] + json.dumps(after, ensure_ascii=False, separators=separators) + content[end:]
    expected = json.loads(content)
    expected["params"] = after
    if json.loads(result) != expected:
        raise ValueError("A parameter replacement changed another response field")
    return result


class SemanticCorrections:
    def __init__(self):
        if sha256(REGISTER.read_bytes()).hexdigest() != REGISTER_SHA256:
            raise ValueError("The reviewed semantic correction register changed")
        registry = json.loads(REGISTER.read_text(encoding="utf-8"))
        self.records = registry["corrections"]
        self.edits = {(item["split"], item["output_line"]): item for item in self.records}
        scope = {(split, line) for split, lines in SCOPE.items() for line in lines}
        if len(self.records) != 12 or set(self.edits) != scope:
            raise ValueError("The twelve reviewed correction positions changed")
        if len({item["id"] for item in self.records}) != len(self.records):
            raise ValueError("Duplicate semantic correction ID")
        kinds = Counter()
        for item in self.records:
            before, after = item["before"], item["after_params"]
            old = before["params"]
            if before["intent"] == "calendar_search":
                preserved = dict(after, query=old["query"])
                if preserved != old or not after["query"] or after["query"] == old["query"]:
                    raise ValueError("A search correction must only restore its query")
                kinds["query_restored"] += 1
            elif before["intent"] == "calendar_add":
                if (set(old) != {"title", "date"} or set(after) != {"title", "starts_at"}
                        or after["title"] != old["title"] or after["starts_at"][:10] != old["date"]):
                    raise ValueError("A time correction must preserve the named event and day")
                kinds["time_restored"] += 1
            else:
                raise ValueError("Unexpected semantic correction action")
        if kinds != {"query_restored": 9, "time_restored": 3}:
            raise ValueError("Wrong semantic correction scope")
        self.applied = set()
        self.info = dict(version=registry["version"], rows=12,
                         splits=dict(sorted(Counter(item["split"] for item in self.records).items())),
                         fields=dict(kinds), register=REGISTER.relative_to(ROOT).as_posix(),
                         register_sha256=REGISTER_SHA256,
                         baseline_manifest_sha256=registry["baseline_manifest_sha256"])

    def apply(self, rows, positions, split, messages_digest):
        for (name, line), item in self.edits.items():
            if name != split:
                continue
            if line > len(rows) or len(rows) != len(positions):
                raise ValueError("A correction is outside the prepared rows")
            row, position = rows[line - 1], positions[line - 1]
            match = SYSTEM_RE.fullmatch(row["messages"][0]["content"])
            if (not match or match[1] + "T" + match[3] != item["anchor"]
                    or row["messages"][1]["content"] != item["question"]
                    or row.get("case_id") != item.get("case_id")
                    or position["source_line"] != item["source_line"]
                    or position["output_line"] != line
                    or json.loads(row["messages"][-1]["content"]) != item["before"]):
                raise ValueError(f"The reviewed semantic source changed: {split}:{line}")
            corrected = deepcopy(row)
            corrected["messages"][-1]["content"] = replace_params(
                row["messages"][-1]["content"], item["before"]["params"], item["after_params"])
            core = {key: value for key, value in corrected.items()
                    if key in {"category", "messages", "case_id", "contract_version"}}
            normalize_record(core, f"{split}:{line}")
            rows[line - 1] = corrected
            positions[line - 1] = dict(position, messages_sha256=messages_digest(corrected),
                                        semantic_correction=item["id"])
            self.applied.add((split, line))
        return rows, positions

    def verify_applied(self):
        if self.applied != set(self.edits):
            raise ValueError("Not every reviewed semantic correction was applied")
