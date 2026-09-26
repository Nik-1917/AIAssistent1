"""Offline CPU training from scratch; disjoint Russian words AND speakers.

An initial engineering experiment, not a reproduction of published Metric-UD-KWS
quality or a production wake-word calibration. Never imports a pretrained model.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from prepare_russian import digest
from ru_model import RussianResNet15


def load_corpus(root):
    manifest_path = root / "corpus.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["license"] != "CC-BY-4.0" or manifest["feature_version"] != "metric-cli-mfcc40-1s-v1":
        raise ValueError("Unreviewed corpus")
    values, rows = {}, {}
    for split in ("train", "dev", "test"):
        name = f"{split}_features.npy"
        if digest(root / name) != manifest["files"][name]:
            raise ValueError("Changed features")
        values[split] = torch.from_numpy(np.load(root / name, allow_pickle=False))
        rows[split] = [row for row in manifest["samples"] if row["split"] == split]
        if values[split].shape != (len(rows[split]), 101, 40) or not torch.isfinite(values[split]).all():
            raise ValueError("Invalid features")
    for first, second in (("train", "dev"), ("train", "test"), ("dev", "test")):
        for key in ("word", "speaker", "path", "pcm_sha256"):
            if {r[key] for r in rows[first]} & {r[key] for r in rows[second]}:
                raise ValueError(f"Leakage: {key}, {first}, {second}")
        if {Path(r['path']).stem for r in rows[first]} & {Path(r['path']).stem for r in rows[second]}:
            raise ValueError("Source sentence leakage")
    return manifest, values, rows


def embeddings(model, features):
    model.eval()
    with torch.inference_mode():
        return torch.cat([F.normalize(model(batch), dim=1) for batch in features.split(32)]).numpy()


def one_shot(model, features, rows, threshold=None):
    vectors = embeddings(model, features)
    labels = np.asarray([r["label"] for r in rows])
    classes = sorted(set(labels.tolist()))
    supports = np.asarray([int(np.flatnonzero(labels == label)[0]) for label in classes])
    query_indices = np.asarray([i for i in range(len(rows)) if i not in set(supports.tolist())])
    scores = vectors[query_indices] @ vectors[supports].T
    truth = labels[query_indices, None] == np.asarray(classes)[None, :]
    positives, negatives = scores[truth], scores[~truth]
    if threshold is None:
        # Choose on dev only. Strictly above the selected negative to handle ties.
        threshold = float(np.nextafter(np.quantile(negatives, .99, method="higher").astype(np.float32), np.float32(np.inf)))
    matched = scores >= threshold
    return dict(threshold=threshold, words=len(classes), enrollment_clips=len(supports),
        positive_trials=len(positives), negative_trials=len(negatives),
        false_accepts=int(matched[~truth].sum()), false_rejects=int((~matched[truth]).sum()),
        false_accept_rate=float(matched[~truth].mean()), false_reject_rate=float((~matched[truth]).mean()),
        top1_accuracy=float((scores.argmax(axis=1) == labels[query_indices]).mean()),
        support_policy="First hash-ordered clip per unseen word; distinct queries; no adaptation",
        includes_other_speakers=True, measures_voice_id=False, noise_or_continuous_audio=False)


def train(args):
    if args.output.exists():
        raise ValueError("Use a new output directory")
    if torch.__version__ != "2.8.0+cpu":
        raise ValueError("Use pinned torch 2.8.0+cpu")
    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    rng = random.Random(args.seed)
    corpus, features, rows = load_corpus(args.corpus)
    args.output.mkdir(parents=True)
    classes = sorted({r["label"] for r in rows["train"]})
    indexes = {label: [i for i, r in enumerate(rows["train"]) if r["label"] == label] for label in classes}
    model = RussianResNet15(args.n_maps, args.embedding_size)
    # Auxiliary training-only classifier is discarded on export.
    classifier = nn.Linear(args.embedding_size, len(classes))
    optimizer = torch.optim.AdamW(list(model.parameters()) + list(classifier.parameters()),
                                 lr=args.learning_rate, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, args.steps, eta_min=args.learning_rate / 10.)
    baseline = one_shot(model, features["dev"], rows["dev"])
    print(json.dumps(dict(random_initialization_dev=baseline)), flush=True)
    history = []
    best_accuracy = -1.
    started = time.monotonic()
    for step in range(1, args.steps + 1):
        model.train()
        chosen = rng.sample(classes, args.ways)
        selected = [rng.sample(indexes[label], 2) for label in chosen]
        indices = [row[0] for row in selected] + [row[1] for row in selected]
        inputs = features["train"][indices]
        output = model(inputs)
        z = F.normalize(output, dim=1)
        logits = 10. * (z[:args.ways] @ z[args.ways:].T)
        targets = torch.arange(args.ways)
        metric_loss = (F.cross_entropy(logits, targets) + F.cross_entropy(logits.T, targets)) / 2.
        class_loss = F.cross_entropy(classifier(output), torch.tensor(chosen + chosen))
        loss = metric_loss + .5 * class_loss
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        nn.utils.clip_grad_norm_(list(model.parameters()) + list(classifier.parameters()), 5.)
        optimizer.step()
        scheduler.step()
        if step % args.validate_every == 0 or step == args.steps:
            dev = one_shot(model, features["dev"], rows["dev"])
            entry = dict(step=step, loss=float(loss.detach()), dev=dev, elapsed_s=time.monotonic() - started)
            history.append(entry)
            print(json.dumps(entry), flush=True)
            if dev["top1_accuracy"] > best_accuracy:
                best_accuracy = dev["top1_accuracy"]
                torch.save(model.state_dict(), args.output / "encoder.pt")
                best_step = step
    model.load_state_dict(torch.load(args.output / "encoder.pt", map_location="cpu", weights_only=True), strict=True)
    dev = one_shot(model, features["dev"], rows["dev"])
    # Test evaluated once, after selecting checkpoint and threshold on dev.
    test = one_shot(model, features["test"], rows["test"], threshold=dev["threshold"])
    report = dict(schema_version=1, model_version="ru-mswc-resnet15-experiment-v1",
        activation_validated=False, initialization="random; no pretrained weights",
        model_license="CC-BY-4.0", dataset_license="CC-BY-4.0",
        architecture="ResNet15", n_maps=args.n_maps, embedding_size=args.embedding_size,
        feature_version=corpus["feature_version"], weights_sha256=digest(args.output / "encoder.pt"),
        corpus_sha256=digest(args.corpus / "corpus.json"), corpus_source=corpus["source"],
        recipe=dict(steps=args.steps, best_step=best_step, ways=args.ways, clips_per_word_per_episode=2,
            seed=args.seed, optimizer="AdamW", initial_lr=args.learning_rate, weight_decay=1e-4,
            loss="Symmetric one-support angular prototype CE (scale 10) + 0.5 auxiliary word CE",
            augmentation="none", torch=torch.__version__, threads=args.threads, device="cpu"),
        random_initialization_dev=baseline, history=history, dev=dev, test=test,
        elapsed_s=time.monotonic() - started,
        limitations=["Short isolated MSWC words; no multiword/VAD/phone/environment test",
            "No owner verification or live false-activations-per-hour evidence",
            "No production quality claim; numerical parity is independent of acoustic accuracy"])
    (args.output / "training.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(dict(final_dev=dev, held_out_test=test, sha256=report["weights_sha256"])), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=1500)
    parser.add_argument("--validate-every", type=int, default=250)
    parser.add_argument("--ways", type=int, default=12)
    parser.add_argument("--n-maps", type=int, default=24)
    parser.add_argument("--embedding-size", type=int, default=64)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--seed", type=int, default=260926)
    parser.add_argument("--learning-rate", type=float, default=.001)
    train(parser.parse_args())
