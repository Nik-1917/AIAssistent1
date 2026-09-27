"""Finalize paired enroll/query ordering from the already checked decode cache.

Only dev/test arrays are rematerialized. All 102400 training records must remain
identical. No encoder inference, threshold or outcome is used for this selection.
Also verifies each selected cached PCM hash before using it.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import numpy as np

from evaluate_personal import trials
from prepare_russian import digest
from prepare_russian_v3 import candidates, finalize_selection, prior_manifests


def run(args):
    path = args.output / "corpus.json"
    corpus = json.loads(path.read_text(encoding="utf-8"))
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    if corpus["curation_sha256"] != digest(args.plan):
        raise ValueError("Changed manual plan")
    prior = prior_manifests()
    original_candidates = candidates(args, plan, prior)
    positions = {r["path"]: i for i, r in enumerate(original_candidates)}
    good = json.loads((args.output / "candidates/accepted.json").read_text(encoding="utf-8"))
    final = finalize_selection(plan, good, prior[-1])
    old_train = [r for r in corpus["samples"] if r["split"] == "train"]
    new_train = [r for r in final if r["split"] == "train"]
    if old_train != new_train:
        raise ValueError("Rebalancing must not change training records")
    pcm = np.load(args.output / "candidates/pcm.npy", mmap_mode="r", allow_pickle=False)
    features = np.load(args.output / "candidates/features.npy", mmap_mode="r", allow_pickle=False)
    summary = json.loads((args.output / "dataset_summary.json").read_text(encoding="utf-8"))
    for side in ("dev", "test"):
        subset = [r for r in final if r["split"] == side]
        new_pcm, new_features = [], []
        for row in subset:
            index = positions[row["path"]]
            wave, feature = pcm[index], features[index]
            if hashlib.sha256(wave.astype("<f4").tobytes()).hexdigest() != row["pcm_sha256"] or not np.isfinite(feature).all():
                raise ValueError("Changed decode cache")
            new_pcm.append(wave)
            new_features.append(feature)
        for kind, values in (("pcm", new_pcm), ("features", new_features)):
            target = args.output / f"{side}_{kind}.npy"
            if digest(target) != corpus["files"][target.name]:
                raise ValueError("Changed existing evaluation arrays")
            temporary = target.with_name(target.stem + "_paired.npy")
            np.save(temporary, np.stack(values))
            temporary.replace(target)
            corpus["files"][target.name] = digest(target)
        check = trials(subset)
        represented = {subset[a]["word"] for a, b in check["positive"]}
        if represented != {r["word"] for r in subset}:
            raise ValueError("Every evaluated word must have distinct same-owner positive queries")
        summary[side] = dict(clips=len(subset), words=len(represented), speakers=len({r["speaker"] for r in subset}),
            source_sentences=len({Path(r["path"]).stem for r in subset}),
            strata=dict(Counter(r["stratum"] for r in subset)), trials={k: len(v) for k, v in check.items()})
    corpus["samples"] = final
    corpus["evaluation_pairing"] = "Select pairs per owner before adding more owners; each word has positive enrollment/query trials"
    corpus["evaluation_reselection_source_sha256"] = digest(Path(__file__))
    path.write_text(json.dumps(corpus, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "dataset_summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--plan", type=Path, default=Path("tools/metric_ud_kws/curation/ru_v3_words.json"))
    p.add_argument("--splits", type=Path, default=Path("build/metric_kws_ru/data/ru-splits.tar.gz"))
    run(p.parse_args())
