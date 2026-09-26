# Stage 3: Russian training and Android encoder experiment

Date: 2026-09-26. **Numerical encoder integration completed; acoustic quality blocks activation.**
The application still receives `UnavailableMetricKwsEngine` from AppModule. The new model,
public Russian speech fixtures and calibration threshold are confined to the test APK.
This experiment does not establish usable arbitrary Russian wake-phrase recognition.

## Model choice and provenance

The pinned upstream Metric-UD-KWS repository did not supply a Russian checkpoint with the
required artifact/training-rights evidence. The Russian PLiX candidate has Apache-2.0 model
metadata, but its ImageNet pretraining chain was not cleared under the user's requirements;
its log-mel frontend also differs. Detailed sources and the artifact URL issue are in
`METRIC_KWS_LICENSES.md`. Neither candidate's weights were used.

Instead, the project trained its own **ResNet15 from random initialization** on licensed
MSWC Russian speech. The adapted topology is MIT; the dataset is CC BY 4.0, with commercial
applications explicitly named by MLCommons. The new model is offered under CC BY 4.0 with
attribution. No third-party pretrained initialization, MUSAN or RIR is used.

This is a new small training recipe inspired by the Metric-UD-KWS encoder, not a reproduction
of the paper's checkpoints or results. It uses 24 feature maps, 64 embedding components,
symmetric angular-prototype loss plus an auxiliary training-only word classifier. The latter
is discarded for export. Training ran 1,500 CPU steps with 12 words and two clips per word
per episode, seed 260926. Exact recipe, checkpoint, wheel hashes and selection manifest:
`tools/metric_ud_kws/experiments/ru-mswc-v1/`.

## Data separation and measured Russian quality

The official MSWC train/dev/test lists have overlapping speakers. We pooled the metadata,
assigned pseudonymous speakers deterministically to new groups, and selected disjoint words:

| Group | Words | Clips | Pseudonymous speakers | Purpose |
|---|---:|---:|---:|---|
| Train | 160 | 10,240 | 507 | Gradient updates |
| Dev | 24 | 480 | 45 | Checkpoint and threshold selection |
| Test | 24 | 480 | 53 | One final evaluation after selection |

Words, speaker IDs, source sentences and decoded-PCM hashes do not overlap across groups.
No attempt was made to identify speakers. One hash-ordered enrollment recording is fixed
per evaluation word; each of the other 19 recordings is a distinct positive query. All
other word enrollments supply negatives. No test threshold optimization is performed.

| Final test measurement | Result |
|---|---:|
| Closed-set 24-way top-1, one enrollment per word | 55.04% |
| Accepted positive pairs | 100 / 456 |
| False rejection rate | **78.07%** |
| Accepted negative pairs | 71 / 10,488 |
| False acceptance per evaluated negative pair | **0.677%** |
| Fixed dev threshold | 0.9215666055679321 |

This recall is inadequate for activation. These are isolated public-corpus words, often
spoken by different speakers; this does not measure owner verification. Pairwise false
acceptance is not false activations per hour. No continuous audio, room noise, distance,
multiword phrases, word-plus-command localization or user microphone enrollment was tested.
The next acoustic work must use the dev split; this inspected test set is now a regression
set, so a fresh sealed evaluation is required for new acceptance claims.

## Exact frontend and export

`MetricKwsFeatureExtractor.kt` is unchanged. Its `metric-cli-mfcc40-1s-v1` recipe is trained
into this checkpoint: 16 kHz mono, center crop/pad to one second, FFT/window 480, hop 160,
40 HTK mel filters, utterance-wide 80 dB floor, orthonormal DCT-II, output `[1,101,40]`.
Host Opus decoding yields 48 kHz; a pinned torchaudio sinc-Hann resampler produces 16 kHz
before that recipe. One selected training clip needed duration padding. No decoder runs
on Android and no frontend substitution was made.

Checkpoint: 295,614 bytes, SHA-256
`dbe2422f38bf0206a50c538a8f64b2b5c49c02ca6636c73e1f82bf728aa7a5df`.
ONNX: 293,104 bytes, opset 17, static float32 `[1,101,40] -> [1,64]`, SHA-256
`a2e04582ad137513be95cf3f0e36210b3f0a84e356ac6a7a16a9583b8941b39b`.
Both implementations normalize the output embedding before cosine comparison.

PyTorch versus Windows ONNX Runtime 1.22.1: **960 real Russian clips**, max absolute
normalized-embedding error **1.430511474609375e-6**, minimum cosine **0.999999999993701**.
Limits were fixed at max error `1e-4` and minimum cosine `0.9999`.

Android Kotlin MFCC + JNI + existing Sherpa ORT **1.27.0**, API 34 x86_64 emulator:
**41 PCM fixtures** (24 Russian utterances and 17 analytic signals), max embedding error
**1.0728836e-6**, minimum reported float32 cosine **1.0**. Also checked 144 cross-word/same-word
query/support scores and threshold decisions against Python. This is emulator evidence;
no ARM phone inference was performed. Numeric parity does not repair model recall.

## Integration and safety boundaries

`OnnxMetricKwsEngine` owns no microphone, does not download models, and always reports
`activationValidated=false`. Its factory checks bounded size, frontend/dimensions and SHA-256
on an owned byte copy. Inference and close are serialized; cancellation during construction
releases any allocated native session. Close is idempotent and completes during cancellation.

`metric_kws_jni.cpp` dynamically opens the existing Sherpa `libonnxruntime.so`, requests the
versioned C API 22 and uses one CPU thread per session. The new bridge compiles for all four
ABIs; actual runtime inference was exercised only on x86_64. It verifies tensor counts,
names, ranks, dimensions and float32 types, rejects malformed/nonfinite inputs, and retains
sessions across concurrent close/inference with validated handles and RAII ownership.
There is no additional AAR, `pickFirst`, replaced runtime, or change to AppModule/audio routing.

For all four ABIs, the merged ORT library matches the existing AAR byte-for-byte; the APK
library matches that same input after NDK `llvm-strip --strip-unneeded`. There is exactly
one ORT library per ABI. The application APK contains zero Russian experiment assets;
the test APK contains its model, fixtures, manifest and license notices.

Instrumentation covers real full-pipeline embeddings, SHA corruption, incompatible ONNX
shape, malformed/native nonfinite input, stale handles, concurrent calls, repeated load and
idempotent close. A further integration test keeps the Metric session alive while actual
bundled Sherpa TTS/ASR/Voice ID models run in the same process, then confirms identical
Metric output. The existing ASR transcribed the synthetic regression input successfully;
that check is runtime compatibility evidence, not human KWS accuracy. Five new host tests
reject corpus tampering and data leakage; the six old
upstream admission tests also pass. Exact build/regression results are in
`METRIC_KWS_RU_VALIDATION.json`; stage-2 evidence is retained in `METRIC_KWS_VALIDATION.json`.

## Remaining work

Improve and evaluate Russian one-shot recall before shipping a model or enabling enrollment.
Validate a duration/localization policy for full phrases and combined wake-word/command audio.
Run ARM device parity, actual single-recording enrollment, independent Voice ID cases, shadow
false-activation measurement, TTS/conference/screen-off scenarios and same-phone power tests.
There is no latency, battery-saving, far-field or physical-phone acceptance claim in this stage.
