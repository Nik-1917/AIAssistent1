"""Offline expanded Russian training. Test features are never inferred during selection."""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import random
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from evaluate_personal import trials, scores, report
from prepare_russian import digest
from ru_model import mfcc_transform
from ru_model_v2 import RussianMetricEncoderV2
from train_russian import embeddings, load_corpus


def train(args):
    if args.output.exists():
        raise ValueError("Use a new run directory")
    if torch.__version__ != "2.8.0+cpu":
        raise ValueError("Use pinned CPU environment")
    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(args.seed)
    rng = random.Random(args.seed)
    corpus, features, rows = load_corpus(args.corpus)
    if not corpus.get("fresh_evaluation_words") or not corpus.get("speaker_balanced"):
        raise ValueError("Expanded, balanced, fresh held-out corpus required")
    pcm_path = args.corpus / "train_pcm.npy"
    if digest(pcm_path) != corpus["files"][pcm_path.name]:
        raise ValueError("Changed training PCM")
    pcm = np.load(pcm_path, mmap_mode="r", allow_pickle=False)
    indices, owner_indices = defaultdict(list), defaultdict(lambda: defaultdict(list))
    for i, row in enumerate(rows["train"]):
        indices[row["label"]].append(i)
        owner_indices[row["speaker"]][row["label"]].append(i)
    labels = sorted(indices)
    owner_indices = {owner: {word: inds for word, inds in words.items() if len(inds) >= 2}
                     for owner, words in owner_indices.items()}
    owners = [owner for owner, words in owner_indices.items() if len(words) >= args.ways]
    if not owners:
        raise ValueError("Need same-owner multiword training episodes")
    model = RussianMetricEncoderV2()
    # Streaming moments avoid an additional full-corpus temporary allocation.
    total, squared, count = torch.zeros(40, dtype=torch.float64), torch.zeros(40, dtype=torch.float64), 0
    for batch in features["train"].split(256):
        values = batch.double()
        total += values.sum((0, 1))
        squared += values.square().sum((0, 1))
        count += values.shape[0] * values.shape[1]
    mean = total / count
    scale = (squared / count - mean.square()).clamp_min(1.).sqrt()
    model.mean.copy_(mean.float().reshape(1, 1, 40))
    model.scale.copy_(scale.float().reshape(1, 1, 40))
    classifier = nn.Parameter(torch.randn(len(labels), 64) * .1)
    log_scale = nn.Parameter(torch.tensor(math.log(15.)))
    parameters = list(model.parameters()) + [classifier, log_scale]
    optimizer = torch.optim.AdamW(parameters, lr=args.learning_rate, weight_decay=1e-4)
    mfcc = mfcc_transform()
    dev_plan = trials(rows["dev"])
    args.output.mkdir(parents=True)
    history, best_key, best_step = [], (float("inf"), float("inf")), 0
    started = time.monotonic()
    losses = []
    for step in range(1, args.steps + 1):
        model.train()
        # Half the episodes contain only one owner: speaker identity cannot solve
        # their word discrimination. The rest also teach cross-speaker matches.
        if rng.random() < .5:
            pool = owner_indices[rng.choice(owners)]
        else:
            pool = indices
        chosen = rng.sample(list(pool), args.ways)
        pairs = [rng.sample(pool[word], 2) for word in chosen]
        selected = [pair[0] for pair in pairs] + [pair[1] for pair in pairs]
        inputs = features["train"][selected].clone()
        with torch.no_grad():
            # Public training recordings only; procedurally generated noise/echo.
            noisy = torch.rand(len(selected)) < .35
            if noisy.any():
                wave = torch.from_numpy(pcm[np.asarray(selected)[noisy.numpy()]].copy())
                rms = wave.square().mean(1, keepdim=True).sqrt()
                snr = torch.empty(len(wave), 1).uniform_(15., 35.)
                noise = torch.randn_like(wave)
                wave += noise * rms * torch.pow(10., -snr / 20.)
                if rng.random() < .5:
                    delay = rng.randint(480, 1920)
                    echo = F.pad(wave[:, :-delay], (delay, 0))
                    wave += echo * rng.uniform(.1, .3)
                wave *= torch.empty(len(wave), 1).uniform_(.4, 1.3)
                # Explicit channel axis keeps torchaudio's top_db maximum per
                # utterance. [batch,time] alone would share the floor across clips.
                inputs[noisy] = mfcc(wave.clamp(-1., 1.).unsqueeze(1)).squeeze(1).transpose(1, 2)
            # Time-only feature warping, no frequency/pitch alteration.
            theta = torch.zeros(len(inputs), 2, 3)
            theta[:, 0, 0] = 1.
            theta[:, 1, 1].uniform_(.9, 1.1)
            theta[:, 1, 2].uniform_(-.10, .10)
            grid = F.affine_grid(theta, (len(inputs), 1, 101, 40), align_corners=False)
            inputs = F.grid_sample(inputs.unsqueeze(1), grid, padding_mode="border",
                                   align_corners=False).squeeze(1)
            inputs += torch.randn_like(inputs) * model.scale * .015
        z = F.normalize(model(inputs), dim=1)
        metric_logits = log_scale.exp().clamp(5., 40.) * (z[:args.ways] @ z[args.ways:].T)
        target = torch.arange(args.ways)
        metric_loss = (F.cross_entropy(metric_logits, target) + F.cross_entropy(metric_logits.T, target)) / 2.
        class_target = torch.tensor(chosen + chosen)
        class_logits = z @ F.normalize(classifier, dim=1).T
        margin = F.one_hot(class_target, len(labels)).float() * .15
        class_loss = F.cross_entropy(20. * (class_logits - margin), class_target)
        loss = metric_loss + class_loss
        progress = max(0., (step - 400) / max(1, args.steps - 400))
        lr = args.learning_rate * min(1., step / 400.) * (.05 + .95 * (1. + math.cos(math.pi * progress)) / 2.)
        for group in optimizer.param_groups:
            group["lr"] = lr
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        nn.utils.clip_grad_norm_(parameters, 5.)
        optimizer.step()
        losses.append(float(loss.detach()))
        if step % 100 == 0:
            print(json.dumps(dict(step=step, loss=sum(losses[-100:]) / min(100, len(losses)),
                                  elapsed_s=round(time.monotonic() - started, 1))), flush=True)
        if step % args.validate_every == 0 or step == args.steps:
            dev = report(scores(embeddings(model, features["dev"]), dev_plan))
            primary = dev["operating_points"]["0.01"]
            key = (primary["false_reject_rate"], primary["cross_speaker_false_reject_rate"])
            entry = dict(step=step, loss=sum(losses) / len(losses), dev=dev,
                         lr=lr, elapsed_s=time.monotonic() - started)
            losses.clear()
            history.append(entry)
            if key < best_key:
                best_key, best_step = key, step
                torch.save(model.state_dict(), args.output / "encoder.pt")
            torch.save(dict(model=model.state_dict(), classifier=classifier.detach(),
                            log_scale=log_scale.detach(), optimizer=optimizer.state_dict(),
                            step=step, random_state=rng.getstate(), torch_rng=torch.get_rng_state()),
                       args.output / "last_training_state.pt")
            result = dict(schema_version=2, model_version="ru-mswc-personal-metric-v2",
                initialization="random; no pretrained weights", activation_validated=False,
                architecture="RussianMetricEncoderV2", embedding_size=64,
                feature_version=corpus["feature_version"], model_license="CC-BY-4.0",
                dataset_license="CC-BY-4.0", weights_sha256=digest(args.output / "encoder.pt"),
                corpus_sha256=digest(args.corpus / "corpus.json"), corpus_source=corpus["source"],
                recipe=dict(steps=args.steps, completed_steps=step, best_step=best_step,
                    seed=args.seed, threads=args.threads, device="cpu", torch=torch.__version__,
                    ways=args.ways, clips_per_word_per_episode=2, training_words=len(labels),
                    training_clips=len(rows["train"]), initial_lr=args.learning_rate,
                    optimizer="AdamW; 400-step warmup, cosine decay to 5%; weight_decay=1e-4",
                    loss="Symmetric angular prototype CE (learned scale) + normalized word CE (scale20 margin.15)",
                    augmentation="35% PCM synthetic white noise 15..35dB SNR; optional 0.03..0.12s echo 0.1..0.3 gain; 0.4..1.3 amplitude; time warp 0.9..1.1 and +/-5 frames; .015 scaled feature jitter",
                    selection="Minimum dev same-owner FRR at dev pair-FAR<=1%; cross-speaker FRR breaks ties",
                    episode_sampling="50% all words from same pseudonymous owner; 50% arbitrary speakers"),
                history=history, dev=next(e["dev"] for e in history if e["step"] == best_step),
                test_status="SEALED: no test inference or threshold selection during training",
                elapsed_s=time.monotonic() - started)
            (args.output / "training.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
            print(json.dumps(dict(validation=entry, best_step=best_step)), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--steps", type=int, default=16000)
    p.add_argument("--validate-every", type=int, default=1000)
    p.add_argument("--ways", type=int, default=24)
    p.add_argument("--threads", type=int, default=8)
    p.add_argument("--seed", type=int, default=260927)
    p.add_argument("--learning-rate", type=float, default=.001)
    train(p.parse_args())
