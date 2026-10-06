"""Download an official public Qwen snapshot, pin its commit and verify every file."""
import os
from pathlib import Path

from run_context import ROOT, WORK, CONFIG, SOURCE_LOCK, digest, read, write, now, verify_source, snapshot_protected


def main():
    snapshot_protected()
    if SOURCE_LOCK.exists():
        verify_source(CONFIG["model"])
        print("Qwen3-0.6B: existing source verified", flush=True)
        return
    os.environ.update(HF_HUB_DISABLE_TELEMETRY="1", HF_HUB_DISABLE_IMPLICIT_TOKEN="1",
                      HF_HUB_DISABLE_XET="1", HF_HOME=str(WORK / "hf_cache"),
                      HF_XET_CACHE=str(WORK / "hf_cache/xet"))
    from huggingface_hub import HfApi, hf_hub_download
    repository = CONFIG["repository"]
    revision_path = WORK / "source_revision.json"
    if revision_path.exists():
        revision = read(revision_path)["revision"]
    else:
        revision = HfApi(token=False).model_info(repository).sha
        write(revision_path, {"repository": repository, "revision": revision, "selected_at": now()})
    info = HfApi(token=False).model_info(repository, revision=revision, files_metadata=True)
    if info.sha != revision:
        raise ValueError("Upstream commit differs")
    target = ROOT / "build/calendar_sft_models/Qwen3-0.6B" / revision
    files = {}
    siblings = [s for s in info.siblings if s.rfilename != ".gitattributes"]
    print(f"Qwen3-0.6B: pinned {revision}; {len(siblings)} files", flush=True)
    for sibling in sorted(siblings, key=lambda s: (s.rfilename.endswith(".safetensors"), s.rfilename)):
        if Path(sibling.rfilename).is_absolute() or ".." in Path(sibling.rfilename).parts:
            raise ValueError("Unsafe upstream file name")
        path = Path(hf_hub_download(repository, sibling.rfilename, revision=revision,
                                  local_dir=target, cache_dir=WORK / "hf_cache", token=False))
        actual = digest(path)
        if path.stat().st_size != sibling.size or (sibling.lfs and actual != sibling.lfs.sha256):
            raise ValueError("Upstream size or SHA-256 differs: " + sibling.rfilename)
        files[sibling.rfilename] = {"bytes": sibling.size, "sha256": actual,
            "upstream_lfs_sha256": sibling.lfs.sha256 if sibling.lfs else None}
        print(f"Verified {sibling.rfilename}: {sibling.size} bytes", flush=True)
    license_text = (target / "LICENSE").read_text(encoding="utf-8")
    if "Apache License" not in license_text or "Version 2.0" not in license_text:
        raise ValueError("Unexpected source license")
    write(SOURCE_LOCK, {"repository": repository, "revision": revision, "directory": str(target),
        "retrieved_at_utc": now(), "files": files, "config": read(target / "config.json"),
        "license": "Apache-2.0", "remote_code_executed": False})
    verify_source(CONFIG["model"])
    print("Qwen3-0.6B: all source files verified", flush=True)


if __name__ == "__main__":
    main()
