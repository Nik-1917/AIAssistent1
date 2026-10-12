"""Bind the unchanged V12.67 trainer to one isolated, pinned 0.6B profile."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import v12_67_training as shared

ROOT = shared.ROOT
read, write, digest, now = shared.read, shared.write, shared.digest, shared.now
CONFIG_PATH = HERE / "config_batch2.json"
CONFIG = read(CONFIG_PATH)
if (CONFIG["version"] != "v12.67" or CONFIG["grader_version"] != shared.VERSION
        or CONFIG["model"] != "qwen3_0_6b" or CONFIG["repository"] != "Qwen/Qwen3-0.6B"
        or CONFIG["source_revision"] != "c1899de289a04d12100db370d81485cdf75e47ca"
        or CONFIG["holdout_used_for_selection"]
        or CONFIG["training_transports"] != ["standard_chatml", "android_runtime"]):
    raise ValueError("Unexpected isolated Qwen3-0.6B V12.67 profile")
DATA = ROOT / CONFIG["dataset"]
WORK = ROOT / CONFIG["work_directory"]
RUN = WORK / CONFIG["model"]
SOURCE_LOCK = ROOT / CONFIG["source_lock"]
PRESERVATION = WORK / "preservation_semantics_20261011.json"
NAMES = {CONFIG["model"]: "Qwen3-0.6B"}
SEED = CONFIG["seed"]
PROFILE_DOC = ROOT / "docs/CALENDAR_V12_67_QWEN3_06B_TRAINING.md"
HISTORICAL_FREEZE = ROOT / "build/calendar_v12_67_qwen3_17b/current_semantics_launch_freeze_20261011.json"
MANUAL_AUDIT = ROOT / "build/calendar_v12_67_manual_semantics_recheck_20261011/audit_complete.json"
_original_launch = shared.verify_launch_inputs
_original_frozen_paths = shared.frozen_paths


def verify_source(key):
    if key != CONFIG["model"]:
        raise ValueError("Wrong model for the isolated 0.6B run")
    lock = read(SOURCE_LOCK)
    if lock["repository"] != CONFIG["repository"] or lock["revision"] != CONFIG["source_revision"]:
        raise ValueError("Wrong pinned Qwen3-0.6B revision")
    folder = Path(lock["directory"]).resolve()
    expected = (ROOT / "build/calendar_sft_models/Qwen3-0.6B" / CONFIG["source_revision"]).resolve()
    if folder != expected:
        raise ValueError("Model source is outside its pinned workspace directory")
    required = {"LICENSE", "README.md", "config.json", "generation_config.json",
                "merges.txt", "tokenizer.json", "tokenizer_config.json",
                "vocab.json", "model.safetensors"}
    if set(lock["files"]) != required:
        raise ValueError("Source-file inventory changed")
    for name, item in lock["files"].items():
        path = (folder / name).resolve()
        if (not path.is_relative_to(folder) or not path.is_file()
                or path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]):
            raise ValueError("Pinned source changed: " + name)
    architecture = read(folder / "config.json")
    expected_architecture = dict(model_type="qwen3", architectures=["Qwen3ForCausalLM"],
                                 num_hidden_layers=28, hidden_size=1024, intermediate_size=3072,
                                 num_attention_heads=16, num_key_value_heads=8, head_dim=128,
                                 vocab_size=151936, tie_word_embeddings=True)
    if any(architecture.get(name) != value or lock["config"].get(name) != value
           for name, value in expected_architecture.items()):
        raise ValueError("Unexpected 0.6B architecture")
    return lock, folder


def verify_launch_inputs():
    evidence = _original_launch()
    freeze = read(HISTORICAL_FREEZE)
    for name, expected in freeze.items():
        if digest(ROOT / name) != expected:
            raise ValueError("Approved V12.67 input changed: " + name)
    audit = read(MANUAL_AUDIT)
    if (audit["status"] != "PASS" or audit["manually_reviewed"] != 6616
            or audit["missing_positions"] or audit["duplicate_positions"]
            or audit["new_confirmed_discrepancies"]
            or audit["manifest_sha256"] != digest(DATA / "manifest.json")):
        raise ValueError("The run does not match the completed manual review")
    evidence.update(model=CONFIG["model"], historical_freeze_files=len(freeze),
                    historical_freeze_sha256=digest(HISTORICAL_FREEZE),
                    manual_audit_sha256=digest(MANUAL_AUDIT))
    return evidence


def frozen_paths():
    paths = _original_frozen_paths() + list(HERE.glob("*.py")) + list(HERE.glob("*.json"))
    paths += [CONFIG_PATH, PROFILE_DOC, HISTORICAL_FREEZE, MANUAL_AUDIT,
              ROOT / "tools/calendar_sft/build_v12_2_gguf.py"]
    return sorted(set(paths))


# Every helper keeps its reviewed implementation and reads this process's profile.
# No file or global configuration of the 1.7B run is rewritten.
for name in ("CONFIG_PATH", "CONFIG", "DATA", "WORK", "SOURCE_LOCK", "SEED",
             "NAMES", "PRESERVATION", "verify_source", "verify_launch_inputs", "frozen_paths"):
    setattr(shared, name, globals()[name])
verify_preservation = shared.verify_preservation
prompt = shared.prompt
