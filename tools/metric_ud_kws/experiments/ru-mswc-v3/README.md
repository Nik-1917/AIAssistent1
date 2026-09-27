# Russian personal metric encoder v3

ONNX and PyTorch artifacts, fine-tuned from the project's own CC BY 4.0 v2 weights.
Training: 102,400 human recordings, 1,184 Russian words,
700 speakers. Exactly 51,200 recordings were added, preserving all 51,200 v2 clips.
The 384 added word labels and 49 difficult pairs were manually authored. One potentially
acoustically ambiguous pair was excluded from negative training (48 active pairs).
Audio selection/quality checks were automated. Zero clips were individually listened to
by a human in this curation pass; MSWC labels were not manually re-transcribed.

Model/data license: CC BY 4.0; attribution and full terms included. Architecture:
RussianMetricEncoderV2, the project's own residual CNN, not original KAIST ResNet15 weights.
No external pretrained weights were introduced; the v2 ancestor was trained from random.

Use encoder.onnx with unchanged metric-cli-mfcc40-1s-v1 Kotlin MFCC and L2-normalize
the 64-component result. Input normalization is embedded in ONNX. Default dev-only
threshold: 0.5827946662902832. Re-enroll after changing the model SHA.

Same new-source test: 177 owner-word positives and 3,979 same-owner wrong-word pairs.
Both thresholds were calibrated on the same new dev split, targeting <=1% dev pair FAR.
v2: misses 42/177 (23.73%), false accepts
39/3979 (0.98%).
v3: misses 31/177 (17.51%), false accepts
57/3979 (1.43%).
The result is mixed: fewer misses, more false accepts at the selected operating point.
This is not proof of an unconditional quality improvement or a <=1% final-test FAR.

Only 64 of the 256 new test clips cover eight never-previously-evaluated words;
the other 192 are unused source recordings of twelve previously evaluated word labels.
training.json reports these strata separately, plus the old v2 regression set with
separate old-dev calibration. All new test source sentences were absent from every
previous selected recording. Words/speakers/source sentences do not cross splits.
Training-only acoustic negative exclusions never modify test labels or score rules.

Pair FAR is not false activations per hour. No full phrases, live microphone,
far-field or continuous activation acceptance has been established. AppModule still
provides UnavailableMetricKwsEngine. This is an experimental integration artifact.
See docs/METRIC_KWS_RU_V3.md and METRIC_KWS_RU_V3_VALIDATION.json for Android evidence.

The compact ZIP includes weights, config, comparison, manual curation and integrity
reports. The experiment directory additionally contains corpus.json.gz with recording
and speaker provenance, raw scores, host dependency versions and wheel hashes.
The corpus decompressed SHA is pinned in provenance.json. Synthetic augmentation
is not counted among the 102,400 human recordings. Source and environment hashes
support reproduction; the host venv inherits dependencies as environment.json states.

ONNX SHA-256: 5d4828f3a8aea2eff1f677b1ab9c51acef0aa732c55f1c425d8d95da41d009a2
PyTorch SHA-256: e3021c37e96c0d1f56828cbe0143343a993ece7527ba4e6436d23720dd9e41fa
