"""Isolated V12.67 Qwen3-1.7B training profile; shared contract code remains unchanged."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parents[0]))
import v12_67_training as shared

read, write, digest, now = shared.read, shared.write, shared.digest, shared.now
CONFIG_PATH = HERE / "config.json"
CONFIG = read(CONFIG_PATH)
if (CONFIG.get("version") != "v12.67"
        or CONFIG.get("model") != "qwen3_1_7b"
        or CONFIG.get("repository") != "Qwen/Qwen3-1.7B"
        or CONFIG.get("source_revision") != "70d244cc86ccca08cf5af4e1e306ecf908b1ad5e"
        or CONFIG.get("holdout_used_for_selection") is not False
        or CONFIG.get("training_transports") != ["standard_chatml", "android_runtime"]
        or CONFIG.get("default_micro_batch") != 2
        or CONFIG.get("effective_batch_size") != 16):
    raise ValueError("Unexpected isolated Qwen3-1.7B V12.67 configuration")

DATA = ROOT / CONFIG["dataset"]
WORK = ROOT / CONFIG["work_directory"]
RUN = WORK / CONFIG["model"]
SOURCE_LOCK = ROOT / CONFIG["source_lock"]
SEED = CONFIG["seed"]
NAMES = {CONFIG["model"]: "Qwen3-1.7B"}
PRESERVATION = WORK / "preservation_semantics_start_20261013.json"
PROFILE_DOC = ROOT / "docs/CALENDAR_V12_67_QWEN3_17B_TRAINING.md"
MANUAL_AUDIT = ROOT / "build/calendar_v12_67_manual_semantics_recheck_20261011/audit_complete.json"
PROVIDER = shared.PROVIDER
CONTRACT = shared.CONTRACT
_original_verify_source = shared.verify_source
_original_verify_launch_inputs = shared.verify_launch_inputs
_original_verify_preservation = shared.verify_preservation
_original_frozen_paths = shared.frozen_paths


def verify_source(key):
    if key != CONFIG["model"]:
        raise ValueError("Wrong model for isolated Qwen3-1.7B")
    lock, source = _original_verify_source(key)
    if (lock["repository"] != CONFIG["repository"]
            or lock["revision"] != CONFIG["source_revision"]
            or len(lock["files"]) != 11):
        raise ValueError("Pinned Qwen3-1.7B source inventory changed")
    return lock, source


def verify_manual_review():
    audit = read(MANUAL_AUDIT)
    manifest_sha = digest(DATA / "manifest.json")
    if (audit.get("status") != "PASS" or audit.get("manually_reviewed") != 6616
            or audit.get("missing_positions") or audit.get("duplicate_positions")
            or audit.get("new_confirmed_discrepancies")
            or audit.get("manifest_sha256") != manifest_sha):
        raise ValueError("The manually reviewed V12.67 dataset changed")
    return dict(status="PASS", manually_reviewed=audit["manually_reviewed"],
                audit_sha256=digest(MANUAL_AUDIT), manifest_sha256=manifest_sha)


def verify_launch_inputs():
    result = _original_verify_launch_inputs()
    result["manual_review"] = verify_manual_review()
    result["source_sha256"] = digest(SOURCE_LOCK)
    return result


def verify_preservation():
    return _original_verify_preservation()


def frozen_paths():
    paths = list(_original_frozen_paths())
    paths += list(HERE.glob("*.py")) + list(HERE.glob("*.json"))
    paths += [PROFILE_DOC, MANUAL_AUDIT]
    return sorted(set(paths))


for _name in ("ROOT", "CONFIG_PATH", "CONFIG", "DATA", "WORK", "RUN",
              "SOURCE_LOCK", "SEED", "NAMES", "PRESERVATION",
              "verify_source", "verify_launch_inputs", "verify_preservation",
              "frozen_paths"):
    setattr(shared, _name, globals()[_name])
