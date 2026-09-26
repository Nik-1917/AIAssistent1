# Russian ResNet15 experiment v1

This is a project-trained engineering checkpoint, **not** the published Metric-UD-KWS
weights and **not** a production-ready wake-word model. It was trained from random
initialization, with no pretrained encoder, on an attributed MSWC Russian subset.
The code topology is adapted from the MIT ResNet15 used by Metric-UD-KWS. The
training recipe is a new small CPU experiment, not a reproduction of the paper.

`encoder.pt`: 295,614 bytes, SHA-256
`dbe2422f38bf0206a50c538a8f64b2b5c49c02ca6636c73e1f82bf728aa7a5df`.
The exported, test-only ONNX is under `app/src/androidTest/assets/metric_kws_ru/`:
293,104 bytes, SHA-256
`a2e04582ad137513be95cf3f0e36210b3f0a84e356ac6a7a16a9583b8941b39b`.

The new checkpoint is provided under **CC BY 4.0** with the dataset attribution below.
This choice applies to this experiment's model artifacts; it does not change the
application's license or assert that dataset licenses automatically attach to weights.
The license permits commercial reuse subject to its attribution and other conditions.
Full text: `../../licenses/CC-BY-4.0.txt`.

Dataset: **Multilingual Spoken Words Corpus v1.0**, MLCommons, Mark Mazumder et al.,
2021, Russian subset. Source and terms:
https://mlcommons.org/datasets/multilingual-spoken-words/
License: https://creativecommons.org/licenses/by/4.0/
No endorsement by the dataset authors is implied. Do not attempt to identify speakers.

Changes: subset selection, Opus decoding, 48-to-16 kHz resampling, clipping to
normalized PCM, one-second center padding/cropping, MFCC extraction, training,
checkpoint selection and ONNX conversion. No other training/augmentation corpus is used.

`corpus.json.gz` contains the full deterministic selection and source paths, compressed
only for source-control size. Its decompressed SHA-256 is in `training.json`. It records
public pseudonymous speaker IDs only to verify split isolation, not identities. Training,
dev and test have disjoint words, speakers, source sentences and decoded-audio hashes.
The original MSWC splits themselves have speaker overlap; they are not described as
speaker-disjoint. The full downloaded corpus remains in ignored `build/` only.

`training.json` records all six dev checks, the selected step and a single final test
evaluation. The test threshold was fixed on dev. See `dataset_summary.json`,
`environment.json` and SHA-pinned `host_wheels.json` for input and tool provenance.
The CPU environment inherits existing host packages; unused global ExecuTorch's torch
constraint conflicts with this pinned torch version. It is not imported by this recipe.

Result on 24 unseen test words: 55.04% closed-set top-1 with one enrollment per word,
100/456 positive pairs accepted and 71/10,488 negative pairs accepted at the dev threshold.
That is 78.07% false rejection and 0.677% false acceptance **per evaluated pair**.
This failed useful wake-word recall; activation must remain disabled. These are short
isolated words, with no continuous-audio, phrase, owner, distance, noise or phone test.

The inherited code notices are in `../../ru_model.py`; the model's test assets have
their own attribution. Host decoders and training packages are not Android dependencies.
