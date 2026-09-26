"""Prepare a bounded, reproducible MSWC Russian experiment. No network or tar extraction.

Uses official MSWC v1.0 Russian audio/splits under CC BY 4.0. Speaker IDs are
used only to prevent leakage; no attempt is made to identify any person.
Archive paths are treated as keys, never used as output filesystem paths.
"""
import argparse
import collections
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile

SPLITS_SHA = "f076d6e56a18709814ec859bcbc2fdc1f97a580ea6dd641cd45b80c035ddbf76"
AUDIO_SHA = "1608eb651462f0f278b6ace0a8967c8e980ec0f44a648312f26c8b385389d631"
SEED = "ai-assistant-ru-metric-v1"


def digest(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def rank(value):
    return hashlib.sha256((SEED + ":" + value).encode("utf-8")).hexdigest()


def speaker_split(speaker):
    if not re.fullmatch(r"[a-f0-9]{128}", speaker):
        raise ValueError("Unexpected pseudonymous speaker key")
    bucket = int(rank(speaker)[:8], 16) % 100
    return "train" if bucket < 80 else "dev" if bucket < 90 else "test"


def select(splits_path, train_words=160, eval_words=24, train_clips=64, eval_clips=20,
           reserved_manifest=None, balanced=False):
    if digest(splits_path) != SPLITS_SHA:
        raise ValueError("Unreviewed splits archive")
    pools = {name: collections.defaultdict(list) for name in ("train", "dev", "test")}
    seen = set()
    with tarfile.open(splits_path) as archive:
        for original in ("train", "dev", "test"):
            member = archive.getmember(f"ru_{original}.csv")
            if not member.isfile() or member.size > 100_000_000:
                raise ValueError("Invalid split member")
            with io.TextIOWrapper(archive.extractfile(member), encoding="utf-8") as stream:
                for row in csv.DictReader(stream):
                    if row["VALID"] != "True" or not re.fullmatch(r"[а-яё]{4,16}", row["WORD"]):
                        continue
                    if row["LINK"] in seen:
                        raise ValueError("Duplicate split path")
                    seen.add(row["LINK"])
                    if not row["LINK"].startswith(row["WORD"] + "/common_voice_ru_"):
                        raise ValueError("Unexpected audio path/language")
                    entry = dict(path="ru/clips/" + row["LINK"], word=row["WORD"],
                                 speaker=row["SPEAKER"], original_split=original)
                    pools[speaker_split(row["SPEAKER"])][row["WORD"]].append(entry)
    reserved_all, reserved_evaluation = set(), set()
    if reserved_manifest is not None:
        previous = json.loads(Path(reserved_manifest).read_text(encoding="utf-8"))
        reserved_all = {r["word"] for r in previous["samples"]}
        reserved_evaluation = {r["word"] for r in previous["samples"] if r["split"] != "train"}
    selected = []
    used_words = set()
    # Fix unseen evaluation words first. Training cannot use any recording of these words.
    for split in ("dev", "test", "train"):
        n_words, n_clips = (train_words, train_clips) if split == "train" else (eval_words, eval_clips)
        reserved = reserved_evaluation if split == "train" else reserved_all
        eligible = []
        for word, candidates in pools[split].items():
            counts = collections.Counter(row["speaker"] for row in candidates)
            if word in used_words or word in reserved or len(candidates) < n_clips or len(counts) < 3:
                continue
            if balanced and split != "train" and (
                    sum(n >= 3 for n in counts.values()) < 3 or
                    sum(n for n in counts.values() if n >= 2) < n_clips):
                continue
            eligible.append(word)
        words = sorted(eligible, key=lambda word: rank(split + ":" + word))[:n_words]
        if len(words) != n_words:
            raise ValueError(f"Insufficient {split} words: {len(words)}/{n_words}")
        for label, word in enumerate(words):
            rows = sorted(pools[split][word], key=lambda row: rank(row["path"]))
            if balanced:
                by_speaker = collections.defaultdict(list)
                for row in rows:
                    by_speaker[row["speaker"]].append(row)
                speakers = sorted(by_speaker, key=rank)
                if split != "train":
                    speakers = [s for s in speakers if len(by_speaker[s]) >= 2]
                rows = [by_speaker[s][i] for i in range(max(len(by_speaker[s]) for s in speakers))
                        for s in speakers if i < len(by_speaker[s])]
            # First clip is the one fixed enrollment; all queries are distinct source recordings.
            chosen, source_ids = [], set()
            for row in rows:
                source_id = Path(row["path"]).stem
                if source_id not in source_ids:
                    chosen.append(dict(row, split=split, label=label))
                    source_ids.add(source_id)
                if len(chosen) == n_clips:
                    break
            if len(chosen) != n_clips:
                raise ValueError("Insufficient distinct source recordings")
            selected.extend(chosen)
        used_words.update(words)
    return selected


def prepare(args):
    import numpy as np
    import soundfile as sf
    import torch
    import torchaudio
    from ru_model import mfcc_transform
    from generate_dsp_fixtures import centered
    if args.output.exists():
        raise ValueError("Output already exists; use a new run directory")
    if torch.__version__ != "2.8.0+cpu":
        raise ValueError("Use the pinned reference environment")
    rows = select(args.splits, args.train_words, args.eval_words, args.train_clips, args.eval_clips,
                  args.reserved_manifest, args.balanced)
    audio_sha = digest(args.audio)
    if args.audio_sha256 != AUDIO_SHA or audio_sha != AUDIO_SHA:
        raise ValueError("Audio archive SHA mismatch")
    args.output.mkdir(parents=True)
    torch.set_num_threads(1)
    transform = mfcc_transform()
    # libsndfile decodes Opus at 48 kHz regardless of the original encoder rate.
    resample = torchaudio.transforms.Resample(48000, 16000,
        resampling_method="sinc_interp_hann", lowpass_filter_width=6, rolloff=0.99)
    features, pcm, offsets = {}, {}, {}
    for split in ("train", "dev", "test"):
        subset = [row for row in rows if row["split"] == split]
        for index, row in enumerate(subset):
            offsets[row["path"]] = index
        features[split] = np.lib.format.open_memmap(args.output / f"{split}_features.npy", mode="w+",
                                                  dtype="float32", shape=(len(subset), 101, 40))
        pcm[split] = np.lib.format.open_memmap(args.output / f"{split}_pcm.npy", mode="w+",
                                             dtype="float32", shape=(len(subset), 16000))
    wanted = {row["path"]: row for row in rows}
    remaining = set(wanted)
    pcm_hashes = set()
    with tarfile.open(args.audio, mode="r|gz") as archive:
        for member in archive:
            row = wanted.get(member.name)
            if row is None:
                continue
            if member.name not in remaining or not member.isfile() or not 0 < member.size < 200_000:
                raise ValueError("Duplicate/invalid archive member")
            compressed = archive.extractfile(member).read()
            audio, rate = sf.read(io.BytesIO(compressed), dtype="float32")
            if rate != 48000 or audio.ndim != 1 or not 24000 <= len(audio) <= 384000 or not np.isfinite(audio).all():
                raise ValueError(f"Unexpected MSWC PCM: {member.name}, {rate}, {audio.shape}")
            row["decoded_samples_48k"] = len(audio)
            with torch.inference_mode():
                audio = centered(resample(torch.from_numpy(audio)).clamp(-1., 1.).numpy())
            row["opus_sha256"] = hashlib.sha256(compressed).hexdigest()
            row["pcm_sha256"] = hashlib.sha256(audio.astype("<f4").tobytes()).hexdigest()
            row["rms"] = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
            row["peak"] = float(np.max(np.abs(audio)))
            row["clipped_fraction"] = float(np.mean(np.abs(audio) >= .999))
            if row["rms"] < 1e-7:
                raise ValueError("Empty/silent recording; review selection before training")
            if row["pcm_sha256"] in pcm_hashes:
                raise ValueError("Duplicate decoded recording in selected corpus")
            pcm_hashes.add(row["pcm_sha256"])
            with torch.inference_mode():
                feature = transform(torch.from_numpy(audio)).transpose(0, 1).numpy()
            features[row["split"]][offsets[member.name]] = feature
            pcm[row["split"]][offsets[member.name]] = audio
            remaining.remove(member.name)
            if len(remaining) % 1000 == 0:
                print(f"Remaining selected clips: {len(remaining)}", flush=True)
    if remaining:
        raise ValueError(f"Missing {len(remaining)} selected clips")
    for values in (features, pcm):
        for array in values.values():
            array.flush()
    manifest = dict(schema_version=1, dataset="MLCommons MSWC v1.0 Russian", license="CC-BY-4.0",
        source="https://mlcommons.org/datasets/multilingual-spoken-words/",
        audio_url="https://mswc.mlcommons-storage.org/audio/ru.tar.gz", audio_sha256=audio_sha,
        splits_url="https://mswc.mlcommons-storage.org/splits/ru.tar.gz", splits_sha256=SPLITS_SHA,
        seed=SEED, selection="Disjoint words AND pseudonymous speakers; independent source clips",
        speaker_balanced=args.balanced,
        previous_corpus_sha256=digest(args.reserved_manifest) if args.reserved_manifest else None,
        fresh_evaluation_words=args.reserved_manifest is not None,
        feature_version="metric-cli-mfcc40-1s-v1", soundfile=sf.__version__, libsndfile=sf.__libsndfile_version__,
        decode_policy="Opus at 48k -> torchaudio sinc_interp_hann 16k, width=6, rolloff=.99 -> clamp [-1,1] -> upstream center crop/pad 16000",
        files={p.name: digest(p) for p in sorted(args.output.glob("*.npy"))}, samples=rows)
    (args.output / "corpus.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({split: len(features[split]) for split in features}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--audio-sha256", default=AUDIO_SHA)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--train-words", type=int, default=160)
    parser.add_argument("--eval-words", type=int, default=24)
    parser.add_argument("--train-clips", type=int, default=64)
    parser.add_argument("--eval-clips", type=int, default=20)
    parser.add_argument("--reserved-manifest", type=Path)
    parser.add_argument("--balanced", action="store_true")
    prepare(parser.parse_args())
