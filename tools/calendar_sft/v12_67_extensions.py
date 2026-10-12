"""Import complete handwritten conversations; never construct their Russian text."""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path

from v12_67_contract import load_jsonl
from v12_67_evaluation import case_from_row, grade

ROOT = Path(__file__).resolve().parents[2]
FOLDER = ROOT / "docs/calendar_v12_67_manual/reply_extensions"
BASELINE = ROOT / "docs/calendar_v12_67_manual/notes_baseline_20261011"
BASE_DATA = BASELINE / "docs/calendar_sft_v12_67"
REGISTER_SHA256 = "5e0d16533b7482a98b7f5b122530857cc70bcfcae345b0e053560822bce85f82"
SPLITS = {"train": 12, "validation": 4, "generation_dev": 4, "reply_extensions_holdout": 4}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


class HandwrittenExtensions:
    def __init__(self):
        register_path = FOLDER / "manifest.json"
        if digest(register_path) != REGISTER_SHA256:
            raise ValueError("The reviewed handwritten extension register changed")
        registry = read(register_path)
        snapshot_path = BASELINE / "snapshot.json"
        if digest(snapshot_path) != registry["baseline_snapshot_sha256"]:
            raise ValueError("The pre-extension snapshot register changed")
        snapshot = read(snapshot_path)
        for name, expected in snapshot["files"].items():
            if digest(BASELINE / name) != expected:
                raise ValueError("The pre-extension snapshot changed: " + name)
        if set(registry["splits"]) != set(SPLITS) or registry["rows"] != sum(SPLITS.values()):
            raise ValueError("Wrong handwritten extension scope")
        self.rows, self.sources, identifiers = {}, {}, set()
        for split, count in SPLITS.items():
            item = registry["splits"][split]
            path = (ROOT / item["file"]).resolve()
            if not path.is_relative_to(FOLDER.resolve()) or digest(path) != item["sha256"]:
                raise ValueError("A handwritten extension source changed: " + split)
            rows = load_jsonl(path)
            if len(rows) != count or item["rows"] != count:
                raise ValueError("Wrong handwritten extension row count")
            for row in rows:
                if row["case_id"] in identifiers:
                    raise ValueError("Duplicate handwritten extension case ID")
                identifiers.add(row["case_id"])
                payload = json.loads(row["messages"][-1]["content"])
                if payload["intent"] != "calendar_add" or not grade(case_from_row(row), row["messages"][-1]["content"])["passed"]:
                    raise ValueError("A handwritten target fails the creation contract")
            self.rows[split], self.sources[split] = rows, item["file"]
        self.info = dict(version=registry["version"], rows=registry["rows"],
                         splits=dict(SPLITS), register_sha256=REGISTER_SHA256,
                         source_sha256={name: item["sha256"] for name, item in registry["splits"].items()},
                         baseline_snapshot_sha256=digest(snapshot_path), baseline_files_checked=len(snapshot["files"]))

    def append(self, rows, positions, split, messages_digest):
        added_rows, added_positions = deepcopy(rows), deepcopy(positions)
        for number, row in enumerate(self.rows.get(split, []), 1):
            added_rows.append(deepcopy(row))
            added_positions.append(dict(source_file=self.sources[split], source_line=number,
                                        output_line=len(added_rows), case_id=row["case_id"],
                                        messages_sha256=messages_digest(row)))
        baseline_path = BASE_DATA / ("generation_dev.json" if split == "generation_dev" else split + ".jsonl")
        if baseline_path.exists():
            baseline = read(baseline_path) if split == "generation_dev" else load_jsonl(baseline_path)
            if rows != baseline:
                raise ValueError("An inherited conversation changed before extension: " + split)
        elif rows:
            raise ValueError("An extension split unexpectedly contains inherited rows")
        return added_rows, added_positions
