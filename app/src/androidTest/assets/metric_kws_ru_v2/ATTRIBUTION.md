# Russian metric encoder v2 and test speech

Model and speech fixtures: CC BY 4.0; full license in LICENSE-CC-BY-4.0.txt.
Dataset: Multilingual Spoken Words Corpus v1.0, MLCommons, Mark Mazumder et al. (2021).
Source: https://mlcommons.org/datasets/multilingual-spoken-words/
Terms: https://creativecommons.org/licenses/by/4.0/
No endorsement is implied; do not attempt to identify speakers.

The 24 Russian speech fixtures are from the public corpus. manifest.json records
each source path and recording hash. Changes: Opus decoding, 48-to-16 kHz resampling,
clipping, one-second centering, PCM/MFCC serialization and embedding computation.

encoder.onnx is the project's own model trained from random initialization on
51,200 Russian recordings. No third-party pretrained weights are included.
Architecture: RussianMetricEncoderV2 (own MIT code), not the published ResNet15.
Synthetic noise/echo, gain and time variations were used during training.
Model artifacts are offered under CC BY 4.0 with the attribution above. This
choice does not change the application's license. Training and source record:
tools/metric_ud_kws/experiments/ru-mswc-v2/.

invalid_shape.onnx is a locally authored parameter-free negative test graph.
The 17 referenced analytic fixtures are locally authored mathematical signals.
All these assets belong only to the instrumentation APK. AppModule continues
to provide UnavailableMetricKwsEngine. No live microphone, phrase or activation
quality is certified by these numerical fixtures.
