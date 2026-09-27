"""Add exactly 51,200 human recordings under a manually authored word/pair plan.

Signal checks are automatic, not a claim of human listening or correct forced
alignment. Previously evaluated words never enter training. Every new evaluation
source sentence is absent from all v1/v2 training AND evaluation samples.
"""
import argparse
from collections import Counter, defaultdict
import csv
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import re
import tarfile

from prepare_russian import AUDIO_SHA, SPLITS_SHA, digest, rank, speaker_split


def balanced(rows, require_repeat=False, pair_first=False):
    groups = defaultdict(list)
    for row in sorted(rows, key=lambda r: rank(r["path"])):
        groups[row["speaker"]].append(row)
    owners = sorted(groups, key=rank)
    if require_repeat:
        owners = [owner for owner in owners if len(groups[owner]) >= 2]
    if not owners:
        return []
    if pair_first:
        return [groups[owner][i] for start in range(0, max(len(groups[o]) for o in owners), 2)
                for owner in owners for i in (start, start + 1) if i < len(groups[owner])]
    return [groups[owner][i] for i in range(max(len(groups[o]) for o in owners))
            for owner in owners if i < len(groups[owner])]


def eligible(rows, count):
    co = Counter(r["speaker"] for r in rows)
    return sum(n for n in co.values() if n >= 2) >= count and sum(n >= 2 for n in co.values()) >= 3


def prior_manifests():
    values = []
    for version in ("v1", "v2"):
        root = Path("tools/metric_ud_kws/experiments/ru-mswc-" + version)
        raw = gzip.decompress((root / "corpus.json.gz").read_bytes())
        report = json.loads((root / "training.json").read_text(encoding="utf-8"))
        if hashlib.sha256(raw).hexdigest() != report["corpus_sha256"]:
            raise ValueError("Changed prior selection")
        values.append(json.loads(raw))
    return values


def candidates(args, plan, priors):
    if digest(args.splits) != SPLITS_SHA:
        raise ValueError("Changed splits archive")
    all_prior = [r for c in priors for r in c["samples"]]
    used = {r["path"] for r in all_prior}
    sources = {Path(r["path"]).stem for r in all_prior}
    old_words = {r["word"] for r in all_prior}
    old_eval = {r["word"] for r in all_prior if r["split"] != "train"}
    new_words = [w for group in plan["groups"].values() for w in group]
    if len(new_words) != 384 or len(set(new_words)) != 384 or set(new_words) & old_words:
        raise ValueError("Manual plan must contain 384 distinct genuinely new training words")
    fresh = set(plan["fresh_dev_words"] + plan["fresh_test_words"])
    if len(fresh) != 16 or fresh & (old_words | set(new_words)):
        raise ValueError("Fresh-word split overlap")
    for pair in plan["contrast_pairs"]:
        if len(pair) != 2 or pair[0] == pair[1] or not set(pair) <= set(new_words):
            raise ValueError("Invalid hand-authored contrast pair")
    old_training = {r["word"] for r in priors[-1]["samples"] if r["split"] == "train"}
    old_split_words = {s: {r["word"] for r in all_prior if r["split"] == s} for s in ("dev", "test")}
    wanted_words = old_training | set(new_words) | old_eval | fresh
    pools = {s: defaultdict(list) for s in ("train", "dev", "test")}
    with tarfile.open(args.splits) as archive:
        for original in ("train", "dev", "test"):
            with io.TextIOWrapper(archive.extractfile(f"ru_{original}.csv"), encoding="utf-8") as stream:
                for row in csv.DictReader(stream):
                    word = row["WORD"]
                    if row["VALID"] != "True" or word not in wanted_words or not re.fullmatch(r"[а-яё]{4,16}", word):
                        continue
                    path = "ru/clips/" + row["LINK"]
                    side = speaker_split(row["SPEAKER"])
                    if path in used or (side != "train" and Path(path).stem in sources):
                        continue
                    if side == "train" and word in (old_eval | fresh):
                        continue
                    pools[side][word].append(dict(path=path, word=word, speaker=row["SPEAKER"], original_split=original, split=side))
    result = []
    for word in sorted(old_training):
        result.extend(dict(r, stratum="additional_existing_word") for r in balanced(pools["train"][word])[:128])
    for word in new_words:
        pool = pools["train"][word]
        if len(pool) < plan["new_word_clips"] or len({r["speaker"] for r in pool}) < 3:
            raise ValueError(f"Insufficient clips/speakers for manually chosen word {word}")
        result.extend(dict(r, stratum="additional_new_word") for r in balanced(pool)[:80])
    for side in ("dev", "test"):
        for word in plan[f"fresh_{side}_words"]:
            if not eligible(pools[side][word], plan["fresh_word_clips"]):
                raise ValueError(f"Insufficient fresh evaluation word {side}:{word}")
            result.extend(dict(r, stratum="new_word_new_source") for r in balanced(pools[side][word], True))
        old_eligible = [word for word in old_split_words[side]
                        if eligible(pools[side][word], plan["new_recording_clips_per_word"])]
        if len(old_eligible) < plan["new_recording_words_per_split"]:
            raise ValueError(f"Insufficient independent new sources in {side}")
        for word in sorted(old_eligible, key=lambda w: rank("v3-eval:" + side + ":" + w)):
            result.extend(dict(r, stratum="previously_evaluated_word_new_source") for r in balanced(pools[side][word], True)[:64])
    if len({r["path"] for r in result}) != len(result):
        raise ValueError("Duplicate candidate path")
    return result


def finalize_selection(plan, good, base):
    pools = {s: defaultdict(list) for s in ("train", "dev", "test")}
    for row in good:
        pools[row["split"]][row["word"]].append(row)
    new_words = [w for group in plan["groups"].values() for w in group]
    base_rows = [dict(r, stratum="preserved_v2_training") for r in base["samples"] if r["split"] == "train"]
    labels = {r["word"]: r["label"] for r in base_rows}
    if len(base_rows) != 51200 or len(labels) != 800:
        raise ValueError("Unexpected v2 baseline size")
    added = []
    for word in new_words:
        rows = balanced(pools["train"][word])[:plan["new_word_clips"]]
        if len(rows) != plan["new_word_clips"] or len({r["speaker"] for r in rows}) < 3:
            raise ValueError(f"Signal checks left too few recordings for {word}; review plan explicitly")
        labels[word] = len(labels)
        added.extend(dict(row, label=labels[word]) for row in rows)
    needed = plan["additional_clips"] - len(added)
    per_word = {word: balanced(pools["train"][word]) for word in labels if word not in new_words}
    # Round-robin words, then speakers within each word; no repeated recording.
    extras = []
    for i in range(128):
        for word in sorted(per_word, key=lambda w: rank("v3-add:" + w)):
            if i < len(per_word[word]) and len(extras) < needed:
                extras.append(dict(per_word[word][i], label=labels[word]))
    if len(extras) != needed:
        raise ValueError(f"Need {needed} distinct existing-word additions, found {len(extras)}")
    final = base_rows + extras + added
    for side in ("dev", "test"):
        words = plan[f"fresh_{side}_words"]
        for label, word in enumerate(words):
            rows = balanced(pools[side][word], True, pair_first=True)[:plan["fresh_word_clips"]]
            if len(rows) != plan["fresh_word_clips"] or len({r["speaker"] for r in rows}) < 3:
                raise ValueError(f"Signal checks left too few fresh-word evaluation clips for {side}:{word}")
            final.extend(dict(r, label=label) for r in rows)
        options = [word for word, rows in pools[side].items()
                   if word not in words and eligible(rows, plan["new_recording_clips_per_word"])]
        options.sort(key=lambda w: rank("v3-eval:" + side + ":" + w))
        chosen = options[:plan["new_recording_words_per_split"]]
        if len(chosen) != plan["new_recording_words_per_split"]:
            raise ValueError("Signal checks left too few evaluation words")
        for label, word in enumerate(chosen, start=len(words)):
            rows = balanced(pools[side][word], True, pair_first=True)[:plan["new_recording_clips_per_word"]]
            final.extend(dict(r, label=label) for r in rows)
    return final


def prepare(args):
    import numpy as np
    import soundfile as sf
    import torch
    import torchaudio
    from generate_dsp_fixtures import centered
    from ru_model import mfcc_transform
    if args.output.exists():
        raise ValueError("Use a new output directory")
    if torch.__version__ != "2.8.0+cpu":
        raise ValueError("Use pinned CPU environment")
    priors = prior_manifests()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    base = priors[-1]
    if digest(args.base / "corpus.json") != hashlib.sha256(gzip.decompress(Path("tools/metric_ud_kws/experiments/ru-mswc-v2/corpus.json.gz").read_bytes())).hexdigest():
        raise ValueError("Changed baseline manifest")
    for name in ("train_features.npy", "train_pcm.npy"):
        if digest(args.base / name) != base["files"][name]:
            raise ValueError("Changed baseline training arrays")
    rows = candidates(args, plan, priors)
    if digest(args.audio) != AUDIO_SHA:
        raise ValueError("Changed audio archive")
    args.output.mkdir(parents=True)
    staging = args.output / "candidates"
    staging.mkdir()
    torch.set_num_threads(1)
    transform = mfcc_transform()
    resample = torchaudio.transforms.Resample(48000, 16000, resampling_method="sinc_interp_hann", lowpass_filter_width=6, rolloff=.99)
    pcm = np.lib.format.open_memmap(staging / "pcm.npy", mode="w+", dtype="float32", shape=(len(rows), 16000))
    features = np.lib.format.open_memmap(staging / "features.npy", mode="w+", dtype="float32", shape=(len(rows), 101, 40))
    positions = {r["path"]: i for i, r in enumerate(rows)}
    wanted = {r["path"]: r for r in rows}
    remaining = set(wanted)
    previous_hashes = {r["pcm_sha256"] for c in priors for r in c["samples"]}
    hashes = set(previous_hashes)
    good, rejected = [], []
    print(json.dumps(dict(candidate_recordings=len(rows), manually_selected_words=384,
                          manually_selected_pairs=len(plan["contrast_pairs"]))), flush=True)
    with tarfile.open(args.audio, mode="r|gz") as archive:
        for member in archive:
            if member.name not in wanted:
                continue
            if member.name not in remaining or not member.isfile() or not 0 < member.size < 200000:
                raise ValueError("Duplicate or invalid archive member")
            row = dict(wanted[member.name])
            compressed = archive.extractfile(member).read()
            audio, rate = sf.read(io.BytesIO(compressed), dtype="float32")
            if rate != 48000 or audio.ndim != 1 or not 24000 <= len(audio) <= 384000 or not np.isfinite(audio).all():
                raise ValueError("Malformed source recording")
            row["decoded_samples_48k"] = len(audio)
            with torch.inference_mode():
                audio = centered(resample(torch.from_numpy(audio)).clamp(-1., 1.).numpy())
            row.update(opus_sha256=hashlib.sha256(compressed).hexdigest(),
                       pcm_sha256=hashlib.sha256(audio.astype("<f4").tobytes()).hexdigest(),
                       rms=float(np.sqrt(np.mean(audio.astype(np.float64) ** 2))),
                       peak=float(np.max(np.abs(audio))), clipped_fraction=float(np.mean(np.abs(audio) >= .999)))
            reason = "too_quiet_for_existing_enrollment_guard" if row["rms"] < math.sqrt(1e-5) else None
            if row["clipped_fraction"] > .01:
                reason = "clipped_over_1_percent"
            if row["pcm_sha256"] in hashes:
                reason = "duplicate_decoded_pcm"
            if reason:
                rejected.append(dict(path=row["path"], split=row["split"], word=row["word"], reason=reason))
            else:
                with torch.inference_mode():
                    feature = transform(torch.from_numpy(audio)).transpose(0, 1).numpy()
                index = positions[row["path"]]
                pcm[index], features[index] = audio, feature
                good.append(row)
                hashes.add(row["pcm_sha256"])
            remaining.remove(member.name)
            if len(remaining) % 2000 == 0:
                print(json.dumps(dict(remaining=len(remaining), accepted=len(good), rejected=len(rejected))), flush=True)
    if remaining:
        raise ValueError("Missing audio candidates")
    pcm.flush()
    features.flush()
    (staging / "accepted.json").write_text(json.dumps(good, ensure_ascii=False), encoding="utf-8")
    quality = dict(automatic_checks_only=True, human_listened_recordings=0,
        source_labels_semantically_verified=False, candidates=len(rows), accepted=len(good),
        rejected_count=len(rejected), reasons=dict(Counter(r["reason"] for r in rejected)),
        policy="16k mono .5..8s source; finite; canonical RMS>=sqrt(1e-5), clipped fraction<=.01; no repeated decoded PCM",
        baseline_rows_preserved=51200, rejected=rejected)
    (args.output / "quality_report.json").write_text(json.dumps(quality, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    final = finalize_selection(plan, good, base)
    base_rows = [r for r in base["samples"] if r["split"] == "train"]
    base_positions = {r["path"]: i for i, r in enumerate(base_rows)}
    base_pcm = np.load(args.base / "train_pcm.npy", mmap_mode="r", allow_pickle=False)
    base_features = np.load(args.base / "train_features.npy", mmap_mode="r", allow_pickle=False)
    summary = {}
    for side in ("train", "dev", "test"):
        subset = [r for r in final if r["split"] == side]
        dest_pcm = np.lib.format.open_memmap(args.output / f"{side}_pcm.npy", mode="w+", dtype="float32", shape=(len(subset), 16000))
        dest_features = np.lib.format.open_memmap(args.output / f"{side}_features.npy", mode="w+", dtype="float32", shape=(len(subset), 101, 40))
        for i, row in enumerate(subset):
            if row["stratum"] == "preserved_v2_training":
                index = base_positions[row["path"]]
                dest_pcm[i], dest_features[i] = base_pcm[index], base_features[index]
            else:
                index = positions[row["path"]]
                dest_pcm[i], dest_features[i] = pcm[index], features[index]
        dest_pcm.flush()
        dest_features.flush()
        summary[side] = dict(clips=len(subset), words=len({r["word"] for r in subset}),
            speakers=len({r["speaker"] for r in subset}), source_sentences=len({Path(r["path"]).stem for r in subset}),
            strata=dict(Counter(r["stratum"] for r in subset)))
    for a, b in (("train", "dev"), ("train", "test"), ("dev", "test")):
        first, second = [r for r in final if r["split"] == a], [r for r in final if r["split"] == b]
        for key in ("word", "speaker", "path", "pcm_sha256"):
            if {r[key] for r in first} & {r[key] for r in second}:
                raise ValueError(f"Leakage: {key}")
        if {Path(r["path"]).stem for r in first} & {Path(r["path"]).stem for r in second}:
            raise ValueError("Source sentence leakage")
    if summary["train"]["clips"] != 102400:
        raise ValueError("Did not exactly double training recordings")
    manifest = dict(schema_version=3, dataset=base["dataset"], license="CC-BY-4.0", source=base["source"],
        audio_url=base["audio_url"], audio_sha256=AUDIO_SHA, splits_url=base["splits_url"], splits_sha256=SPLITS_SHA,
        feature_version=base["feature_version"], decode_policy=base["decode_policy"],
        soundfile=sf.__version__, libsndfile=sf.__libsndfile_version__,
        selection="Preserved v2 train + 51200 unused human recordings; manually authored word/pair list; disjoint words/speakers/sources",
        curation_sha256=digest(args.plan), curation=plan, speaker_balanced=True,
        evaluation_policy="New source sentences only; separate fresh-word and previously-evaluated-word strata; all previous eval words excluded from train",
        baseline_corpus_sha256=digest(args.base / "corpus.json"),
        files={p.name: digest(p) for p in sorted(args.output.glob("*.npy"))}, samples=final)
    (args.output / "corpus.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "dataset_summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(final=summary, quality_rejections=quality["reasons"])), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--base", type=Path, default=Path("build/metric_kws_ru/corpus-expanded-v2"))
    p.add_argument("--plan", type=Path, default=Path("tools/metric_ud_kws/curation/ru_v3_words.json"))
    p.add_argument("--splits", type=Path, default=Path("build/metric_kws_ru/data/ru-splits.tar.gz"))
    p.add_argument("--audio", type=Path, default=Path("build/metric_kws_ru/data/ru-audio.tar.gz"))
    p.add_argument("--output", type=Path, required=True)
    prepare(p.parse_args())
