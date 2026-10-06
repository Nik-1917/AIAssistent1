"""Isolated Qwen3-0.6B run using the unchanged approved V12.66 contract."""
from copy import deepcopy
import math
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from small_models_common import ROOT, read, write, digest, now, prompt, encode_row
from v12_66_evaluation import VERSION
from v12_66_training import runtime_system, development_cases as original_development_cases

CONFIG_PATH = HERE / "config.json"
CONFIG = read(CONFIG_PATH)
if (CONFIG["version"] != "v12.66" or CONFIG["grader_version"] != VERSION
        or CONFIG["model"] != "qwen3_0_6b" or CONFIG["holdout_used_for_selection"]):
    raise ValueError("Wrong isolated training contract")
NAMES = {CONFIG["model"]: "Qwen3-0.6B"}
DATA = ROOT / CONFIG["dataset"]
WORK = ROOT / CONFIG["work_directory"]
SOURCE_LOCK = ROOT / CONFIG["source_lock"]
SEED = CONFIG["seed"]
RUN = WORK / CONFIG["model"]
PRESERVATION = WORK / "preservation.json"


def verify_source(key):
    if key != CONFIG["model"]:
        raise ValueError("Wrong model")
    lock = read(SOURCE_LOCK)
    if lock["repository"] != CONFIG["repository"] or len(lock["revision"]) != 40:
        raise ValueError("Wrong official source")
    folder = Path(lock["directory"]).resolve()
    if not folder.is_relative_to((ROOT / "build/calendar_sft_models/Qwen3-0.6B").resolve()):
        raise ValueError("Source directory is outside the selected workspace")
    for name, item in lock["files"].items():
        file = (folder / name).resolve()
        if not file.is_relative_to(folder) or file.stat().st_size != item["bytes"] or digest(file) != item["sha256"]:
            raise ValueError("Source changed: " + name)
    if lock["config"]["model_type"] != "qwen3" or lock["config"]["num_hidden_layers"] != 28:
        raise ValueError("Unexpected Qwen3-0.6B architecture")
    return lock, folder


def encode_variant(tokenizer, row, variant):
    if variant not in CONFIG["training_transports"]:
        raise ValueError("Unknown transport")
    result = deepcopy(row)
    if variant == "android_runtime":
        result["messages"][0]["content"] = runtime_system(result["messages"][0]["content"])
    return encode_row(tokenizer, result, android=variant != "standard_chatml", max_length=CONFIG["max_length"])


def development_cases(rows):
    return original_development_cases(rows, dataset=DATA)


def selection_steps(rows):
    if rows <= 0:
        raise ValueError("Empty training data")
    per_epoch = math.ceil(rows / CONFIG["effective_batch_size"])
    return sorted({epoch * per_epoch + math.ceil(part * per_epoch / CONFIG["development_evaluations_per_epoch"])
                   for epoch in range(CONFIG["epochs"])
                   for part in range(1, CONFIG["development_evaluations_per_epoch"] + 1)})


def snapshot_protected():
    if PRESERVATION.exists():
        return verify_preservation()
    paths = list((ROOT / "app/src").rglob("*")) + list(DATA.iterdir())
    paths += [ROOT / name for name in read(DATA / "manifest.json")["rules_sha256"]]
    files = {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(set(paths)) if p.is_file()}
    write(PRESERVATION, {"created_at": now(), "files": files})
    return {"status": "PASS", "files_checked": len(files)}


def verify_preservation():
    files = read(PRESERVATION)["files"]
    if not files:
        raise ValueError("Empty preservation snapshot")
    for name, expected in files.items():
        if digest(ROOT / name) != expected:
            raise ValueError("Protected Android/data/rules file changed: " + name)
    return {"status": "PASS", "files_checked": len(files)}


def frozen_paths():
    paths = list(HERE.parent.glob("*.py")) + list(HERE.glob("*.py"))
    paths += [CONFIG_PATH, SOURCE_LOCK, PRESERVATION]
    paths += list(DATA.glob("*.json")) + list(DATA.glob("*.jsonl"))
    paths += [ROOT / name for name in read(DATA / "manifest.json")["rules_sha256"]]
    return sorted(set(paths))
