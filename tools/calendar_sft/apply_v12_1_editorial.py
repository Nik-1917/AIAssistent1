"""Apply a finite, handwritten editorial ledger to hash-locked V12 rows.

No sampling, templates, date inference, model calls or data generator imports.
The only new cases are copied one-for-one from the handwritten focused file.
"""

from __future__ import annotations

from collections import Counter
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path

from dataset_contract import (
    category_counts, file_sha256, load_jsonl, message_signature,
    normalize_record, normalized_user_prompt, v12_1_creation_reply,
)

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "build/calendar_sft_dataset_v12"
DESTINATION = ROOT / "docs/calendar_sft_v12_1"
LEDGER = ROOT / "docs/calendar_assistant_v12_1_editorial.json"
FOCUSED = ROOT / "docs/calendar_assistant_v12_1_focused.json"
SPLITS = ("train", "validation", "holdout")


def user_texts(row: dict) -> tuple[str, ...]:
    return tuple(message["content"] for message in row["messages"][1:-1])


def preserved_payload_sha256(rows: list[dict]) -> str:
    payload = []
    for row in rows:
        response = json.loads(row["messages"][-1]["content"])
        if response["intent"] == "calendar_add":
            response.pop("reply")
        item = {
            "category": row["category"], "system": row["messages"][0],
            "response": response, "case_id": row.get("case_id"),
        }
        if response["intent"] == "note_add":
            item["users"] = user_texts(row)
        payload.append(item)
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return sha256(encoded.encode("utf-8")).hexdigest()


def assemble() -> tuple[dict, dict]:
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    focused = json.loads(FOCUSED.read_text(encoding="utf-8"))
    sources = {}
    for split in SPLITS:
        path = SOURCE / f"{split}.jsonl"
        if file_sha256(path) != ledger["source_sha256"][split]:
            raise ValueError(f"{split}: original V12 hash mismatch")
        sources[split] = load_jsonl(path)

    # Identical reviewed wording receives the same edit in every temporal context.
    edits = {}
    for reference, replacement in ledger["prompt_edits"].items():
        split, number = reference.split("/")
        key = user_texts(sources[split][int(number) - 1])
        if key in edits:
            raise ValueError(f"duplicate editorial entry: {reference}")
        edits[key] = replacement

    outputs = {}
    audit = {"version": "v12.1", "sources": ledger["source_sha256"], "splits": {}}
    for split, rows in sources.items():
        result = []
        changed_prompts, changed_replies, turns_split = [], [], []
        for number, original in enumerate(rows, 1):
            row = deepcopy(original)
            response = json.loads(row["messages"][-1]["content"])
            old_users = user_texts(row)
            replacements = edits.get(old_users, old_users)
            users = []
            for content in replacements:
                for old, new in ledger["literal_user_edits"].get(response["intent"], []):
                    content = content.replace(old, new)
                users.append({"role": "user", "content": content})
            row["messages"] = [row["messages"][0], *users, row["messages"][-1]]
            if user_texts(row) != old_users:
                changed_prompts.append(number)
            if len(users) > len(old_users):
                turns_split.append(number)
            if response["intent"] == "calendar_add":
                response["reply"] = v12_1_creation_reply(response["params"])
                changed_replies.append(number)
            row["messages"][-1]["content"] = json.dumps(
                response, ensure_ascii=False, separators=(",", ":"),
            )
            row["contract_version"] = "v12.1"
            result.append(normalize_record(row, f"{split}/{number}"))

        for case in focused[split]["cases"]:
            result.append(normalize_record({
                "case_id": case["id"], "category": case["category"],
                "contract_version": "v12.1",
                "messages": [
                    {"role": "system", "content": focused[split]["system"]},
                    *({"role": "user", "content": text} for text in case["users"]),
                    {"role": "assistant", "content": json.dumps(case["response"], ensure_ascii=False)},
                ],
            }, case["id"]))

        for number, row in enumerate(result, 1):
            response = json.loads(row["messages"][-1]["content"])
            if response["intent"] != "note_add":
                if any(len(text.split()) > ledger["max_user_words"] for text in user_texts(row)):
                    raise ValueError(f"{split}/{number}: unreviewed long request: {user_texts(row)}")
                if response["intent"] == "calendar_add" and len(response["params"]) > 3 and len(user_texts(row)) != 2:
                    raise ValueError(f"{split}/{number}: overloaded single-turn creation")
        signatures = [message_signature(row) for row in result]
        if len(set(signatures)) != len(signatures):
            duplicates = [key for key, count in Counter(signatures).items() if count > 1]
            raise ValueError(f"{split}: duplicate contexts: {duplicates}")
        outputs[split] = result
        source_payload_hash = preserved_payload_sha256(rows)
        if preserved_payload_sha256(result[:len(rows)]) != source_payload_hash:
            raise ValueError(f"{split}: unrelated V12 behavior changed")
        audit["splits"][split] = {
            "original_rows": len(rows), "rows": len(result),
            "preserved_payload_sha256": source_payload_hash,
            "prompt_edit_rows": changed_prompts, "reply_edit_rows": changed_replies,
            "split_into_two_users_rows": turns_split,
            "manual_added_rows": len(focused[split]["cases"]),
            "category_counts": category_counts(result),
        }

    for left, right in (("train", "validation"), ("train", "holdout"), ("validation", "holdout")):
        if {message_signature(r) for r in outputs[left]} & {message_signature(r) for r in outputs[right]}:
            raise ValueError(f"exact context leakage: {left}/{right}")
    holdout_prompts = {normalized_user_prompt(r) for r in outputs["holdout"]}
    for split in ("train", "validation"):
        if holdout_prompts & {normalized_user_prompt(r) for r in outputs[split]}:
            raise ValueError(f"holdout wording leakage into {split}")
    for split in SPLITS:
        added = outputs[split][len(sources[split]):]
        other_prompts = {
            normalized_user_prompt(r) for other in SPLITS if other != split for r in outputs[other]
        }
        if {normalized_user_prompt(r) for r in added} & other_prompts:
            raise ValueError(f"new focused wording leakage: {split}")
    return outputs, audit


def main() -> None:
    outputs, audit = assemble()
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for split, rows in outputs.items():
        path = DESTINATION / f"{split}.jsonl"
        path.write_text(
            "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows),
            encoding="utf-8", newline="\n",
        )
        audit["splits"][split]["sha256"] = file_sha256(path)
    for name, path in (("editorial", LEDGER), ("focused", FOCUSED)):
        audit[f"{name}_sha256"] = file_sha256(path)
    audit["training"] = "NOT_RUN"
    audit["gguf_conversion"] = "NOT_RUN"
    audit["android_device_validation"] = "NOT_RUN"
    (DESTINATION / "manifest.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n",
    )
    print(json.dumps({split: len(rows) for split, rows in outputs.items()}))


if __name__ == "__main__":
    main()
