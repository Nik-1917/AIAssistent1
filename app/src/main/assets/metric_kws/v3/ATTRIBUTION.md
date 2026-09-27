# Russian personal metric encoder v3

Weights/ONNX: CC BY 4.0, full terms in LICENSE-CC-BY-4.0.txt.
Dataset: Multilingual Spoken Words Corpus v1.0, MLCommons,
Mark Mazumder et al. (2021).
Source: https://mlcommons.org/datasets/multilingual-spoken-words/
Terms: https://creativecommons.org/licenses/by/4.0/
No endorsement is implied. Do not attempt to identify corpus speakers.

The project fine-tuned its own v2 residual metric CNN on 102,400 Russian human
recordings. The v2 ancestor was trained from random initialization; no external
pretrained weights are included. This is not the original KAIST checkpoint.
Changes include decoding/resampling, central one-second MFCC, synthetic noise,
echo/gain/time augmentation, training and ONNX export. Source code and application
licenses are unchanged. Full provenance: tools/metric_ud_kws/experiments/ru-mswc-v3/.

Only the ONNX model and metadata are bundled here, without corpus speech.
SHA-256: 5d4828f3a8aea2eff1f677b1ab9c51acef0aa732c55f1c425d8d95da41d009a2
Experimental activation is an explicit user setting; activation_validated remains
false in model.json because numerical parity is not live microphone acceptance.
