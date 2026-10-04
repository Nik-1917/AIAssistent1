"""Versioned paths, temporal annotations and checkpoint schedule for V12.65."""
from copy import deepcopy
import math
import re
from pathlib import Path

from small_models_common import ROOT, NAMES, read, write, digest, now, prompt, encode_row
from v12_65_evaluation import VERSION, case_from_row, grade, summary, selection_score

CONFIG_PATH = ROOT / "tools/calendar_sft/v12_65_training_config.json"
CONFIG = read(CONFIG_PATH)
if CONFIG["version"] != "v12.65" or CONFIG["grader_version"] != VERSION or CONFIG["holdout_used_for_selection"]:
    raise ValueError("wrong training/evaluation contract")
DATA = ROOT / CONFIG["dataset"]
WORK = ROOT / CONFIG["work_directory"]
SEED = CONFIG["seed"]
SOURCE_LOCK = ROOT / CONFIG["source_lock"]


def verify_source(key):
    if key != CONFIG["model"]:
        raise ValueError("model differs from V12.65 training config")
    lock = read(SOURCE_LOCK)
    folder = Path(lock["directory"])
    for name, item in lock["files"].items():
        if digest(folder / name) != item["sha256"]:
            raise ValueError("model source changed: " + name)
    return lock, folder


def runtime_system(system):
    m = re.fullmatch(r"Сегодня дата и время:(\S+) \((.+)\) (\S+) Europe/Samara ответ JSON", system)
    if not m:
        raise ValueError("unknown calendar context")
    return f"cегодня {m[1]} {m[3]} день недели {m[2]} ответ JSON"


def variant_row(row, variant):
    if variant not in CONFIG["training_transports"]:
        raise ValueError("unknown transport")
    result = deepcopy(row)
    if variant == "android_runtime":
        result["messages"][0]["content"] = runtime_system(result["messages"][0]["content"])
    return result


def encode_variant(tokenizer, row, variant):
    return encode_row(tokenizer, variant_row(row, variant), android=variant != "standard_chatml",
                      max_length=CONFIG["max_length"])


def selection_steps(rows):
    per_epoch = math.ceil(rows / CONFIG["effective_batch_size"])
    if rows <= 0:
        raise ValueError("empty training data")
    # Include every epoch end and the final step; evaluations always get a
    # recoverable checkpoint even when not divisible by checkpoint_interval.
    return sorted({epoch * per_epoch + math.ceil(part * per_epoch / CONFIG["development_evaluations_per_epoch"])
                   for epoch in range(CONFIG["epochs"])
                   for part in range(1, CONFIG["development_evaluations_per_epoch"] + 1)})


def development_cases(rows, dataset=DATA):
    index = read(dataset / "audit_index.json")
    cases = []
    for i, row in enumerate(rows):
        row = deepcopy(row)
        row["audit"] = index.get(row.get("case_id"), {})
        base = case_from_row(row, row.get("case_id", f"DEV_{i}"), "development")
        if row["audit"].get("split") == "holdout":
            raise ValueError("holdout cannot be used for checkpoint selection")
        cases.append(base)
        android = deepcopy(base)
        android.update(id="ANDROID_" + base["id"], system=runtime_system(base["system"]),
                       prompt_format="android_chatml", suite="development_android")
        cases.append(android)
    return cases


def frozen_paths():
    # Capture all versioned Python dependencies used for tokenization, grading,
    # source validation and training, in addition to datasets and rules.
    paths = list((ROOT / "tools/calendar_sft").glob("*.py"))
    paths += [CONFIG_PATH, SOURCE_LOCK]
    paths += list(DATA.glob("*.json")) + list(DATA.glob("*.jsonl"))
    paths += [ROOT / p for p in read(DATA / "manifest.json")["rules_sha256"]]
    return sorted(set(paths))
