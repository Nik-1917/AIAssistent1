"""Isolated V12.67 input transport, source integrity and checkpoint schedule."""
from copy import deepcopy
from datetime import datetime, timezone
from hashlib import sha256
import json
import math
import os
from pathlib import Path
import textwrap
import time

from v12_67_contract import SYSTEM_RE
from v12_67_evaluation import VERSION, case_from_row

ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = ROOT / "tools/calendar_sft/v12_67_training_config.json"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def digest(path):
    with Path(path).open("rb") as stream:
        return __import__("hashlib").file_digest(stream, "sha256").hexdigest()


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for attempt in range(12):
        try:
            os.replace(temporary, path)
            return
        except PermissionError:
            if attempt == 11:
                raise
            time.sleep(0.2 * (attempt + 1))


def now():
    return datetime.now(timezone.utc).isoformat()


CONFIG = read(CONFIG_PATH)
if CONFIG["version"] != "v12.67" or CONFIG["grader_version"] != VERSION or CONFIG["holdout_used_for_selection"]:
    raise ValueError("Wrong V12.67 training contract")
DATA = ROOT / CONFIG["dataset"]
WORK = ROOT / CONFIG["work_directory"]
SOURCE_LOCK = ROOT / CONFIG["source_lock"]
SEED = CONFIG["seed"]
NAMES = {CONFIG["model"]: "Qwen3-1.7B"}
PRESERVATION = WORK / "preservation.json"
PROVIDER = ROOT / "app/src/main/java/com/example/aiassistent1/domain/provider/SystemPromptProvider.kt"
provider_source = PROVIDER.read_text(encoding="utf-8")
CONTRACT = textwrap.dedent(provider_source.split('CALENDAR_CONTRACT = """', 1)[1]
                         .split('""".trimIndent()', 1)[0]).strip()


def system_text(source_system, variant):
    if variant not in CONFIG["training_transports"]:
        raise ValueError("Unknown transport")
    match = SYSTEM_RE.fullmatch(source_system)
    if not match:
        raise ValueError("Unexpected temporal anchor")
    header = source_system if variant == "standard_chatml" else (
        f"cегодня {match[1]} {match[3]} день недели {match[2]} ответ JSON"
    )
    return header + "\n" + CONTRACT


def variant_row(row, variant):
    result = deepcopy(row)
    result["messages"][0]["content"] = system_text(result["messages"][0]["content"], variant)
    return result


def prompt(messages, android=True):
    separator = "\n" if android else ""
    return "".join("<|im_start|>" + message["role"] + "\n" + message["content"] + separator
                   + "<|im_end|>\n" for message in messages) + "<|im_start|>assistant\n"


def encode_variant(tokenizer, row, variant):
    messages = variant_row(row, variant)["messages"]
    if [message["role"] for message in messages] != ["system", "user", "assistant"]:
        raise ValueError("Expected a complete conversation")
    android = variant != "standard_chatml"
    prefix = prompt(messages[:-1], android)
    completion = messages[-1]["content"] + ("\n" if android else "") + "<|im_end|>\n"
    ids = tokenizer.encode(prefix + completion, add_special_tokens=False)
    prefix_ids = tokenizer.encode(prefix, add_special_tokens=False)
    if ids[:len(prefix_ids)] != prefix_ids:
        raise ValueError("Assistant boundary crosses a token")
    if len(ids) > CONFIG["max_length"]:
        raise ValueError(f"Token count {len(ids)} exceeds {CONFIG['max_length']}; truncation is forbidden")
    if tokenizer.decode(ids[len(prefix_ids):], skip_special_tokens=False) != completion:
        raise ValueError("Assistant supervision changed")
    return dict(input_ids=ids, attention_mask=[1] * len(ids),
                labels=[-100] * len(prefix_ids) + ids[len(prefix_ids):])


def selection_steps(rows):
    if rows <= 0:
        raise ValueError("Empty training data")
    per_epoch = math.ceil(rows / CONFIG["effective_batch_size"])
    return sorted({epoch * per_epoch + math.ceil(part * per_epoch / CONFIG["development_evaluations_per_epoch"])
                   for epoch in range(CONFIG["epochs"])
                   for part in range(1, CONFIG["development_evaluations_per_epoch"] + 1)})


def development_cases(rows):
    index = read(DATA / "audit_index.json")
    cases = []
    for number, row in enumerate(rows):
        candidate = deepcopy(row)
        candidate["audit"] = index.get(row.get("case_id"), candidate.get("audit", {}))
        if candidate["audit"].get("split") == "holdout":
            raise ValueError("Holdout cannot be used for checkpoint selection")
        base = case_from_row(candidate, row.get("case_id", f"DEV_{number}"), "development")
        for variant in CONFIG["training_transports"]:
            case = deepcopy(base)
            case.update(id=variant + "_" + base["id"], suite="development_" + variant,
                        source_system=base["system"], system=system_text(base["system"], variant),
                        prompt_format="standard_chatml" if variant == "standard_chatml" else "android_chatml")
            cases.append(case)
    return cases


def verify_source(key):
    if key != CONFIG["model"]:
        raise ValueError("Wrong model")
    lock = read(SOURCE_LOCK)
    if lock["repository"] != CONFIG["repository"] or lock["revision"] != CONFIG["source_revision"]:
        raise ValueError("Wrong source revision")
    folder = Path(lock["directory"]).resolve()
    if not folder.is_relative_to((ROOT / "build/calendar_sft_models/Qwen3-1.7B").resolve()):
        raise ValueError("Model source is outside the selected workspace")
    for name, item in lock["files"].items():
        path = (folder / name).resolve()
        if not path.is_relative_to(folder) or path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]:
            raise ValueError("Source changed: " + name)
    if lock["config"]["model_type"] != "qwen3" or lock["config"]["num_hidden_layers"] != 28:
        raise ValueError("Unexpected base architecture")
    return lock, folder


def verify_launch_inputs():
    from prepare_v12_67 import assemble
    manifest = assemble(check_only=True)
    if manifest["runtime_provider_sha256"] != digest(PROVIDER):
        raise ValueError("Runtime provider changed")
    return dict(status="PASS", dataset_manifest_sha256=digest(DATA / "manifest.json"),
                runtime_provider_sha256=digest(PROVIDER), protected_files=manifest["protected_files_checked"])


def verify_preservation():
    if not PRESERVATION.exists():
        paths = []
        for name in ("app/src", "calendar-core/src", "calendar-storage-android/src"):
            paths += [path for path in (ROOT / name).rglob("*") if path.is_file()]
        paths += list(DATA.iterdir()) + [ROOT / name for name in read(DATA / "manifest.json")["rules_sha256"]]
        write(PRESERVATION, dict(created_at=now(), files={path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(set(paths))}))
    files = read(PRESERVATION)["files"]
    if not files:
        raise ValueError("Empty preservation snapshot")
    for name, expected in files.items():
        if digest(ROOT / name) != expected:
            raise ValueError("Protected Android/data/rules file changed: " + name)
    return dict(status="PASS", files_checked=len(files))


def frozen_paths():
    directory = ROOT / "tools/calendar_sft"
    paths = list(directory.glob("*v12_67*.py")) + [directory / "amp_retry.py", CONFIG_PATH, SOURCE_LOCK, PRESERVATION, PROVIDER]
    paths += list(DATA.iterdir()) + list((ROOT / "docs/calendar_v12_67_manual").iterdir())
    paths += [ROOT / name for name in read(DATA / "manifest.json")["rules_sha256"]]
    return sorted(set(paths))
