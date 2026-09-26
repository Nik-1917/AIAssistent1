# Russian personal KWS retraining, 2026-09-27

Status: **retrained, exported and verified on Android; production activation remains unvalidated.**
Deployable ONNX, PyTorch weights, configuration, licenses and checksums are in
`tools/metric_ud_kws/experiments/ru-mswc-v2/ru-mswc-personal-v2.zip`.
The compact archive is 1,883,978 bytes; ONNX alone is 1,004,112 bytes.

## Why v1 missed so many words

The original 78.07% false rejection measurement included matching a word spoken by
another person. That is a useful speaker-invariance stress test, but it is stricter
than accepting a repeat utterance from the enrolled owner. Re-evaluation of the
unchanged v1 model/threshold on the inspected old test gives **136 misses / 256
same-owner positive pairs (53.125%)** and **68 accepts / 7,047 same-owner wrong-word
pairs (0.965%)**. Thus the model itself was insufficient; protocol strictness is
not the sole explanation. Wrong words spoken by the owner must remain negatives:
the independent Voice ID gate cannot repair those mistakes.

V1 had 160 training words / 10,240 recordings and 1,500 steps of 24 examples:
36,000 presented examples, about 3.52 presentations per recording on average,
not a full-dataset epoch count. It had no augmentation, a 24-channel globally
pooled bottleneck and selected checkpoints by 24-way top-1 instead of detection
quality. These are observed recipe limitations, not causal ablation results.

The [Metric-UD-KWS paper](https://arxiv.org/pdf/2211.00439) uses a substantially
larger pretraining task and a different evaluation protocol. Its published
numbers cannot be directly compared with this small Russian experiment.

## Implemented changes

* Keep the exact raw Kotlin MFCC contract and 64-component embedding interface.
* Use only the same SHA-pinned, CC BY 4.0 MSWC Russian source; random initialization.
* Reserve all previously inspected evaluation words from new training; fresh dev
  and test words also exclude every old training word. Preserve speaker buckets.
* Expand training to 800 words / 51,200 clips. Choose 32 fresh dev and 32 fresh
  test words, with repeated owner recordings and deterministic speaker balancing.
* Use an own residual CNN with training-only fitted input statistics, C0 energy
  centering and coarse time/cepstral position pooling. This is a new encoder,
  not the original paper's ResNet15 or a reproduction of its trained weights.
* Train longer with synthetic noise/echo, gain and time variations. Half the
  training episodes contain multiple words from the same owner, so speaker
  identity cannot solve their word classification.
* Choose the checkpoint by dev same-owner FRR at pair-FAR <=1%; report additional
  dev-calibrated 2.5%, 5% and 10% operating points separately. Test never sets a
  threshold. Compare v1 and v2 on identical fresh enrollment/query pairs.

The old benchmark remains a regression set. No test recording is reused as its
own positive enrollment. No change is made to Voice ID, runtime activation,
microphone ownership or unrelated application rules.

## Completed training and fresh comparison

Training completed 16,000 CPU steps in 1,690.6 seconds; selection chose step 13,000
using dev only. Total presented examples: 768,000, approximately 15 per corpus
recording on average (sampling is not a sequential epoch traversal). Architecture
has 249,352 trainable parameters, 64 output components. All weights started random.
The exact seed, learning schedule, augmentation and history are in `training.json`.

| Split | Words | Clips | Speakers | Source sentences |
|---|---:|---:|---:|---:|
| Train | 800 | 51,200 | 682 | 30,766 |
| Dev | 32 | 768 | 28 | 708 |
| Fresh test | 32 | 768 | 31 | 672 |

The primary test contains 563 distinct same-owner positive query/enrollment pairs
and 17,774 same-owner wrong-word negative pairs. There are 197 usable word/owner
enrollments, each using exactly one recording. These correlated corpus pairs are
descriptive measurements, not independent binomial observations or live event rates.

| Model / dev calibration target | Misses on fresh test | Wrong-word accepts on fresh test |
|---|---:|---:|
| v1, pair FAR <=1% | 335/563 = **59.50%** | 268/17,774 = **1.508%** |
| v2, pair FAR <=1% (default) | 109/563 = **19.36%** | 128/17,774 = **0.720%** |
| v2, pair FAR <=2.5% | 50/563 = **8.88%** | 418/17,774 = **2.352%** |
| v2, pair FAR <=5% | 29/563 = **5.15%** | 923/17,774 = **5.193%** |
| v2, pair FAR <=10% | 15/563 = **2.66%** | 1,890/17,774 = **10.634%** |

Each model has its own dev-calibrated threshold; no threshold was fitted on test.
The default v2 cosine threshold is **0.598879337310791**. The 2.5% operating point
uses 0.47782671451568604 and is an explicit recall/false-match tradeoff, not the
default. The primary improvement is therefore not produced by loosening the gate.

The fresh cross-speaker/mixed-speaker 32-way benchmark also improves: top-1
44.84% -> 77.17%, FRR 77.99% -> 27.99%, pair FAR 1.595% -> 0.942%. This uses one
support per word and its own dev threshold; do not mix it with the owner table.
On the old inspected 24-word regression set, top-1 is 91.23%, FRR 12.50%, FAR
1.535%. The larger 91.23% figure is **not** the fresh-test claim.

Remaining errors are recorded in `error_analysis.json`. For example, the distinct
labels `страны` and `стран` account for 25 of the 128 default false accepts. The
word `этим` has 12 misses in 16 positive trials. These errors were inspected after
final evaluation, with no subsequent threshold/checkpoint tuning. Relabeling close
words or dropping difficult examples would conceal mistakes and was not done.

## Export and Android verification

* ONNX SHA-256: `21eda065e55135bac590edd9dace1bb6752dc450125a4baf07603d3baf5f514e`.
* PyTorch SHA-256: `81661fab73b3473b5a3ae0c5b41df35b9689c180d26441fc01b3ccadc7311650`.
* PyTorch vs Windows ORT: 1,536 human Russian clips, max normalized error
  **4.6193599700927734e-7**, minimum cosine **0.99999999999887**.
* Android 14 x86_64 emulator: four real instrumentation tests passed, covering
  41 full PCM/MFCC/ONNX fixtures, 144 comparison/threshold decisions, corrupt
  inputs/models, lifecycle/concurrency and coexistence with real Sherpa models.
  Maximum normalized error **2.935296e-6**, reported float32 minimum cosine 1.0.
* Physical Android 13 ARM64 phone: **41 fixtures** through the exact compiled app
  Kotlin MFCC, JNI bridge, existing Sherpa ORT 1.27.0 and Kotlin normalization.
  Max normalized error **2.9390212148427963e-6**, minimum cosine
  **0.9999999999525839**. Run in ART via `app_process` from a separate temporary
  bundle; the installed application was not updated and its APK hash was unchanged.
  This is physical-device numerical evidence, not microphone/enrollment/UI evidence.
* Host protocol/DSP/admission tests: **16 passed**. App assembly/lint and existing
  JVM suite succeeded; unchanged JVM tasks were up-to-date. Exact counts and logs
  are recorded in `METRIC_KWS_RU_V2_VALIDATION.json`.

The ARM one-pass debug harness reported median 15.32 ms and max 55.83 ms for the
41 full feature/inference operations. This mixed cold/warm fixture timing is not
a sustained app latency, microphone power or battery benchmark.

## Artifact use and remaining scope

Use the raw Kotlin MFCC output unchanged. Learned feature statistics and C0
centering are already inside ONNX; do not repeat them externally. L2-normalize
the output before cosine comparison. Changing the model hash requires a fresh
enrollment: v1 and v2 embeddings must not be mixed. Preserve the independent
keyword AND Voice ID decision.

The delivered model is ready for integration/shadow evaluation of isolated words.
19.36% misses at the default threshold do not establish reliable always-on wakeup.
Multiword phrases, word-plus-command localization, live microphone enrollment,
environmental noise, continuous false activations, actual owner verification and
power still need their own evidence. AppModule remains on the unavailable Metric
engine and the existing activation path is retained. No inference is made about
those product behaviors from corpus accuracy or numerical parity.

Reproducible training/export commands are in `tools/metric_ud_kws/README.md`.
Full corpus selection, package hashes, baseline errors and training code hashes
are retained with the experiment. The original v1 artifacts remain unchanged.
