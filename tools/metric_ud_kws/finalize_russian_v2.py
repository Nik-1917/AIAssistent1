"""One final held-out evaluation after selection, with v1 on identical trials.

Old inspected test is explicitly a regression set. No test-driven recalibration.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import torch

from evaluate_personal import trials, scores, report, thresholds_from
from prepare_russian import digest
from ru_model import RussianResNet15
from ru_model_v2 import RussianMetricEncoderV2
from train_russian import embeddings, load_corpus, one_shot


def evaluate(args):
    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    path = args.run / "training.json"
    training = json.loads(path.read_text(encoding="utf-8"))
    if training["test_status"] != "SEALED: no test inference or threshold selection during training":
        raise ValueError("Final test already evaluated; do not repeatedly tune on it")
    if training["weights_sha256"] != digest(args.run / "encoder.pt") or training["corpus_sha256"] != digest(args.corpus / "corpus.json"):
        raise ValueError("Artifact provenance mismatch")
    if training["recipe"]["completed_steps"] != training["recipe"]["steps"]:
        raise ValueError("Training incomplete")
    _, features, rows = load_corpus(args.corpus)
    old_report = json.loads((args.baseline / "training.json").read_text(encoding="utf-8"))
    if old_report["weights_sha256"] != digest(args.baseline / "encoder.pt"):
        raise ValueError("Changed baseline")
    old = RussianResNet15(old_report["n_maps"], old_report["embedding_size"])
    old.load_state_dict(torch.load(args.baseline / "encoder.pt", map_location="cpu", weights_only=True))
    new = RussianMetricEncoderV2()
    new.load_state_dict(torch.load(args.run / "encoder.pt", map_location="cpu", weights_only=True))
    plans = {split: trials(rows[split]) for split in ("dev", "test")}
    comparisons = {}
    for name, model in (("v1", old), ("v2", new)):
        vectors = {split: embeddings(model, features[split]) for split in ("dev", "test")}
        dev = report(scores(vectors["dev"], plans["dev"]))
        test_values = scores(vectors["test"], plans["test"])
        test = report(test_values, thresholds_from(dev))
        # Retain all scores for independent audit, no identities or PCM in this file.
        np.savez(args.run / f"{name}_fresh_test_scores.npz", **test_values)
        cross_dev = one_shot(model, features["dev"], rows["dev"])
        cross_test = one_shot(model, features["test"], rows["test"], threshold=cross_dev["threshold"])
        comparisons[name] = dict(dev=dev, test=test, cross_speaker_closed_set_dev=cross_dev,
                                  cross_speaker_closed_set_test=cross_test)
    # Regression remains word-disjoint from new training, but was inspected earlier.
    _, regression, old_rows = load_corpus(args.regression)
    old_protocol_v2 = one_shot(new, regression["dev"], old_rows["dev"])
    regression_test = one_shot(new, regression["test"], old_rows["test"], threshold=old_protocol_v2["threshold"])
    training.update(test_status="FINAL_EVALUATED", comparison=comparisons,
                    dev=comparisons["v2"]["dev"], test=comparisons["v2"]["test"],
                    regression=dict(status="Previously inspected v1 test; not sealed",
                        v1=old_report["test"], v2=regression_test, v2_dev=old_protocol_v2),
                    limitations=["Isolated public-corpus words, not full wake phrases",
                        "FAR is per selected negative pair, not false activations per hour",
                        "No physical phone, far-field, live VAD, owner-verifier or battery evidence",
                        "Synthetic training noise is not a real environmental-noise acceptance test",
                        "New encoder architecture; not a reproduction of published Metric-UD-KWS"])
    path.write_text(json.dumps(training, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(dict(comparison=comparisons, regression=training["regression"])), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--baseline", type=Path, default=Path("tools/metric_ud_kws/experiments/ru-mswc-v1"))
    p.add_argument("--regression", type=Path, default=Path("build/metric_kws_ru/corpus-v2"))
    p.add_argument("--threads", type=int, default=8)
    evaluate(p.parse_args())
