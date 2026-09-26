# Test-only Russian model and speech fixtures

License: CC BY 4.0; full text in `LICENSE-CC-BY-4.0.txt`.
Dataset: Multilingual Spoken Words Corpus v1.0, MLCommons, Mark Mazumder et al. (2021).
Source: https://mlcommons.org/datasets/multilingual-spoken-words/
Terms: https://creativecommons.org/licenses/by/4.0/
No endorsement is implied; do not attempt to identify speakers.

The 24 speech fixtures are excerpts of this public Russian dataset. The per-recording
source paths and hashes are in `manifest.json`. Changes: Opus decoding, resampling
48 kHz to 16 kHz, normalized clipping, one-second center crop/pad, float32 PCM/MFCC
serialization. Original audio source: https://mswc.mlcommons-storage.org/audio/ru.tar.gz
SHA-256: 1608eb651462f0f278b6ace0a8967c8e980ec0f44a648312f26c8b385389d631.

`encoder.onnx` is the project's own Russian experiment, trained from random initialization
on the attributed dataset, licensed here under CC BY 4.0. It contains no upstream
Metric-UD-KWS, PLiX, ImageNet or other pretrained weights. Architecture: adapted MIT
ResNet15, Copyright (c) 2018 Castorini and Copyright (c) 2023 Multimodal AI Lab, KAIST;
full MIT notices in `LICENSE-ResNet15.txt`. Training record:
`tools/metric_ud_kws/experiments/ru-mswc-v1/`.

`invalid_shape.onnx` is a locally authored parameter-free negative test graph (mean/slice).
The 17 referenced analytic fixtures are locally authored mathematical signals; they
contain no speech. Model and speech assets are packaged in the instrumentation test
APK only. The application continues to use UnavailableMetricKwsEngine.

Numerical parity does not imply adequate keyword accuracy. This experiment's false
rejection rate is too high for activation; the dev threshold is for evaluation only.
