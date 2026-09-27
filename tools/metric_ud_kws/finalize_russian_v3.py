"""Finalize v3 once; compare with frozen v2 on identical, source-disjoint trials.

Report genuinely new words separately from new recordings of previously inspected
evaluation words. Do not replace the unchanged v2 regression results with this
smaller new set, and never fit a threshold to final-test scores.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import torch

from evaluate_personal import trials, scores, report, thresholds_from
from prepare_russian import digest
from ru_model_v2 import RussianMetricEncoderV2
from train_russian import embeddings, load_corpus, one_shot

INITIAL_SHA = "81661fab73b3473b5a3ae0c5b41df35b9689c180d26441fc01b3ccadc7311650"


def breakdown(values, plan, rows, thresholds):
    result = {}
    for stratum in sorted({r["stratum"] for r in rows}):
        selected = {key: np.asarray([rows[int(a)]["stratum"] == stratum for a, b in plan[key]])
                    for key in values}
        part = {key: values[key][selected[key]] for key in values}
        if len(part["positive"]) == 0 or len(part["negative"]) == 0:
            raise ValueError("Every stratum needs usable owner and wrong-word trials")
        result[stratum] = report(part, thresholds)
    return result


def errors(values, plan, rows, threshold):
    from collections import Counter
    misses = Counter(rows[plan["positive"][i, 0]]["word"] for i, value in enumerate(values["positive"]) if value < threshold)
    counts = Counter(rows[a]["word"] for a, b in plan["positive"])
    wrong = Counter((rows[plan["negative"][i, 0]]["word"], rows[plan["negative"][i, 1]]["word"])
                    for i, value in enumerate(values["negative"]) if value >= threshold)
    return dict(threshold=threshold,
        misses=[dict(word=w, misses=n, positives=counts[w]) for w, n in misses.most_common()],
        wrong_words=[dict(enrollment_word=a, query_word=b, false_accepts=n) for (a, b), n in wrong.most_common()],
        scope="Descriptive analysis after final evaluation; no threshold/checkpoint retuning")


def evaluate(args):
    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    path = args.run / "training.json"
    training = json.loads(path.read_text(encoding="utf-8"))
    if training["test_status"] != "SEALED: no test inference or threshold selection during training":
        raise ValueError("Final test was already evaluated")
    if not training.get("training_complete"):
        raise ValueError("Training incomplete")
    if training["weights_sha256"] != digest(args.run / "encoder.pt") or training["corpus_sha256"] != digest(args.corpus / "corpus.json"):
        raise ValueError("Changed model/corpus")
    if digest(args.baseline / "encoder.pt") != INITIAL_SHA or training["initial_weights_sha256"] != INITIAL_SHA:
        raise ValueError("Changed v2 baseline")
    old = RussianMetricEncoderV2()
    new = RussianMetricEncoderV2()
    old.load_state_dict(torch.load(args.baseline / "encoder.pt", map_location="cpu", weights_only=True), strict=True)
    new.load_state_dict(torch.load(args.run / "encoder.pt", map_location="cpu", weights_only=True), strict=True)
    corpus, features, rows = load_corpus(args.corpus)
    if not corpus.get("evaluation_pairing"):
        raise ValueError("Independent owner query/enrollment pairing must be finalized before training")
    plans = {side: trials(rows[side]) for side in ("dev", "test")}
    comparison = {}
    for name, model in (("v2", old), ("v3", new)):
        vectors = {side: embeddings(model, features[side]) for side in ("dev", "test")}
        dev = report(scores(vectors["dev"], plans["dev"]))
        fixed = thresholds_from(dev)
        test_values = scores(vectors["test"], plans["test"])
        final = report(test_values, fixed)
        np.savez(args.run / f"{name}_new_test_scores.npz", **test_values)
        cross_dev = one_shot(model, features["dev"], rows["dev"])
        cross_test = one_shot(model, features["test"], rows["test"], threshold=cross_dev["threshold"])
        comparison[name] = dict(dev=dev, test=final, test_strata=breakdown(test_values, plans["test"], rows["test"], fixed),
            mixed_speaker_closed_set_dev=cross_dev, mixed_speaker_closed_set_test=cross_test)
        if name == "v3":
            error_report = errors(test_values, plans["test"], rows["test"], dev["threshold"])
            (args.run / "error_analysis.json").write_text(json.dumps(error_report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    _, previous, previous_rows = load_corpus(args.regression)
    regression = {}
    old_report = json.loads((args.baseline / "training.json").read_text(encoding="utf-8"))
    for name, model in (("v2", old), ("v3", new)):
        # Both independently recalibrated on the same OLD DEV, never old TEST.
        # This is a regression operating point, not the new default deployment threshold.
        dev = report(scores(embeddings(model, previous["dev"]), trials(previous_rows["dev"])))
        test = report(scores(embeddings(model, previous["test"]), trials(previous_rows["test"])), thresholds_from(dev))
        regression[name] = dict(dev=dev, test=test)
    training.update(test_status="FINAL_EVALUATED", comparison=comparison,
        dev=comparison["v3"]["dev"], test=comparison["v3"]["test"],
        regression=dict(status="Previously inspected v2 benchmark; separate old-dev calibration", results=regression),
        manual_curation=corpus["curation"],
        limitations=["Only 177 positive queries in the new test; small, correlated corpus sample",
            "Genuinely new words are a small separate stratum; do not claim the entire new test has uninspected labels",
            "Recording labels inherited from MSWC; no individual human listening or transcript-completeness audit",
            "51,200 new real recordings; training augmentations are not counted as additional human examples",
            "Isolated words, no full-phrase/localization/live-microphone/continuous-false-activation/power acceptance",
            "Pair false accepts are not false activations per hour; Voice ID remains independent"])
    path.write_text(json.dumps(training, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(dict(new_test=comparison, previous_test=training["regression"])), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--baseline", type=Path, default=Path("tools/metric_ud_kws/experiments/ru-mswc-v2"))
    p.add_argument("--regression", type=Path, default=Path("build/metric_kws_ru/corpus-expanded-v2"))
    p.add_argument("--threads", type=int, default=8)
    evaluate(p.parse_args())
