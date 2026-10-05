"""Verify prepared training inputs independently of later Android-only edits.

The original whole-project snapshot check remains unchanged. This launch gate
requires every prepared artifact and every protected non-Android file to match
the original hashes. Android drift is reported, never called a snapshot pass.
"""
from pathlib import Path
from small_models_common import ROOT, read, digest

PROTECTED_LOCK = Path("docs/calendar_v12_66_manual/source_lock.json")
PREPARATION_LOCK = Path("build/calendar_v12_66_preparation/integrity.json")


def verified_path(root, relative):
    path = root / relative
    if not path.resolve().is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError("Invalid or missing prepared input: " + relative)
    return path


def verify_launch_inputs(root=ROOT):
    root = Path(root)
    protected = read(root / PROTECTED_LOCK)["files"]
    preparation = read(root / PREPARATION_LOCK)
    if preparation["status"] != "PASS" or not protected or not preparation["files"]:
        raise ValueError("Missing successful preparation evidence")
    app_drift, training_checked = [], 0
    for relative, expected in protected.items():
        actual = digest(verified_path(root, relative))
        if not relative.startswith("app/"):
            if actual != expected:
                raise ValueError("Protected training source changed: " + relative)
            training_checked += 1
        elif actual != expected:
            app_drift.append(dict(path=relative, prepared_sha256=expected, launch_sha256=actual))
    for relative, expected in preparation["files"].items():
        if digest(verified_path(root, relative)) != expected:
            raise ValueError("Prepared V12.66 input changed: " + relative)
    return dict(status="PASS", protected_training_files_checked=training_checked,
                prepared_files_checked=len(preparation["files"]),
                historical_whole_project_snapshot="DIFFERS" if app_drift else "MATCHES",
                android_only_drift=app_drift,
                protected_lock_sha256=digest(root / PROTECTED_LOCK),
                preparation_lock_sha256=digest(root / PREPARATION_LOCK))
