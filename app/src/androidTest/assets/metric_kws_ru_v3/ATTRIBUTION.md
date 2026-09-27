# Russian metric encoder v3 and test speech

Model and speech fixtures: CC BY 4.0; full terms in LICENSE-CC-BY-4.0.txt.
Dataset: Multilingual Spoken Words Corpus v1.0, MLCommons, Mark Mazumder et al. (2021).
Source: https://mlcommons.org/datasets/multilingual-spoken-words/
Terms: https://creativecommons.org/licenses/by/4.0/
No endorsement is implied. Do not attempt to identify speakers.

The 24 Russian speech fixtures are public-corpus recordings. manifest.json records
each source path and recording hash. Changes: Opus decoding, 48-to-16 kHz resampling,
clipping, one-second centering, PCM/MFCC serialization and embedding computation.

encoder.onnx is the project's own RussianMetricEncoderV2, fine-tuned on 102,400
human recordings from the project's v2 checkpoint. That ancestor was trained from
random initialization; no external pretrained weights are included. Architecture
code is MIT; this is not the published KAIST ResNet15 checkpoint. The 384 additional
word labels and 49 difficult pairs were selected manually (48 active pairs after
an acoustic ambiguity exclusion). Recording selection/checks were automated;
individual human listening or transcription was not performed in this pass.
Synthetic noise, echo, gain and time variation were used during training.

The generated weights/ONNX are offered under CC BY 4.0 with the attribution above.
This choice does not change the application or source-code licenses. Training,
initial-checkpoint SHA and corpus record: tools/metric_ud_kws/experiments/ru-mswc-v3/.

invalid_shape.onnx is a locally authored parameter-free negative test graph.
The 17 referenced analytic fixtures are locally authored mathematical signals.
These assets belong only to the instrumentation APK. Numerical parity is not
live microphone, full phrase or end-to-end activation quality acceptance.
