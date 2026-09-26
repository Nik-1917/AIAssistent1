"""Fixed one-recording enrollment; same-owner wrong words remain hard negatives.

Thresholds must come from dev, never the reported final test. Pair FAR is not
false activations per hour and this evaluator does not measure speaker ID.
"""
from collections import defaultdict

import numpy as np


TARGETS = (.01, .025, .05, .10)


def trials(rows):
    groups = defaultdict(list)
    for i, row in enumerate(rows):
        groups[(row["word"], row["speaker"])].append(i)
    positive, negative, cross_speaker, supports = [], [], [], []
    for (word, owner), indices in groups.items():
        if len(indices) < 2:
            continue
        support = indices[0]
        supports.append(support)
        positive.extend((support, i) for i in indices[1:])
        negative.extend((support, i) for i, r in enumerate(rows)
                        if r["speaker"] == owner and r["word"] != word)
        cross_speaker.extend((support, i) for i, r in enumerate(rows)
                             if r["speaker"] != owner and r["word"] == word)
    if not positive or not negative:
        raise ValueError("Need independent owner positives and owner wrong-word negatives")
    return dict(positive=np.asarray(positive), negative=np.asarray(negative),
                cross_speaker=np.asarray(cross_speaker).reshape(-1, 2), supports=supports)


def scores(vectors, plan):
    return {key: (vectors[pairs[:, 0]] * vectors[pairs[:, 1]]).sum(axis=1)
            for key, pairs in plan.items() if key != "supports"}


def calibrate(negatives, target):
    if not 0 < target < 1 or len(negatives) == 0 or not np.isfinite(negatives).all():
        raise ValueError("Invalid calibration")
    value = np.quantile(negatives, 1. - target, method="higher").astype(np.float32)
    return float(np.nextafter(value, np.float32(np.inf)))


def report(values, thresholds=None):
    if thresholds is None:
        thresholds = {str(target): calibrate(values["negative"], target) for target in TARGETS}
    result = dict(positive_trials=len(values["positive"]), negative_trials=len(values["negative"]),
                  cross_speaker_trials=len(values["cross_speaker"]), operating_points={})
    for target in TARGETS:
        threshold = thresholds[str(target)]
        misses = int((values["positive"] < threshold).sum())
        false = int((values["negative"] >= threshold).sum())
        cross = values["cross_speaker"]
        result["operating_points"][str(target)] = dict(threshold=threshold,
            false_rejects=misses, false_accepts=false,
            false_reject_rate=misses / len(values["positive"]),
            false_accept_rate=false / len(values["negative"]),
            cross_speaker_false_reject_rate=float((cross < threshold).mean()) if len(cross) else None)
    result["threshold"] = thresholds["0.01"]
    return result


def thresholds_from(dev):
    return {key: value["threshold"] for key, value in dev["operating_points"].items()}
