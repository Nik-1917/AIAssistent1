# Metric KWS: artifact admission and third-party licenses

Audit date: 2026-09-24. **No new third-party runtime, native library, pretrained model or audio
fixture has been included in the Android APK by this change.** Stage 2 includes an experimental
Kotlin DSP implementation and its MIT/BSD notices. Existing Sherpa/ASR/TTS assets are
unchanged; this document is not a new commercial clearance of the entire existing application.

| Component | Version / commit | Primary license source | Commercial use assessment | Included in APK by this change |
|---|---|---|---|---|
| Metric-UD-KWS code | `b26b3dffa3ab19255963329eab9e733e441d7486` | [MIT](https://github.com/kaistmm/Metric-UD-KWS/blob/b26b3dffa3ab19255963329eab9e733e441d7486/LICENSE) | MIT permits commercial software distribution with copyright/license notice retained | Adapted duration policy and notice; no upstream runtime |
| Upstream ResNet15 code (Honk-derived) | same commit | [Source with Castorini MIT notice](https://github.com/kaistmm/Metric-UD-KWS/blob/b26b3dffa3ab19255963329eab9e733e441d7486/models/ResNet15.py) | Retain the separate Castorini notice if redistributed | No |
| Upstream data/reference helpers | same commit | [NAVER MIT notice in source](https://github.com/kaistmm/Metric-UD-KWS/blob/b26b3dffa3ab19255963329eab9e733e441d7486/DatasetLoader.py) | Applicable notice retained | Adapted duration policy and notice |
| `PTAP_FTAP_en.model` | repository file, 5,354,247 bytes | [Artifact location](https://github.com/kaistmm/Metric-UD-KWS/tree/b26b3dffa3ab19255963329eab9e733e441d7486/PT_models) | Not cleared: no artifact-specific provenance/license confirmation established by this audit | No |
| `PTAP_FTAP_en_kr.model` | repository file, 9,666,247 bytes | same location | Not cleared for the same reason; filename is not language-quality evidence | No |
| ONNX Runtime, investigated candidate | 1.22.1 | [Microsoft MIT](https://github.com/microsoft/onnxruntime/blob/v1.22.1/LICENSE) | Core license permits commercial use; selected Android package and bundled notices still require inspection before addition | No |
| ONNX exporter format library, investigated candidate | 1.19.0 | [Apache-2.0](https://github.com/onnx/onnx/blob/v1.19.0/LICENSE) | License permits commercial use subject to conditions and applicable notices | No |
| torchaudio DSP reference | 2.8.0+cpu | [BSD-2-Clause](https://github.com/pytorch/audio/blob/v2.8.0/LICENSE) | Installed in a separate host venv; copyright, conditions and disclaimer retained with the Kotlin adaptation; not the upstream reference version | Kotlin formula adaptation and notice; no torchaudio runtime |
| PyTorch DSP reference | 2.8.0+cpu | [License / component notices](https://github.com/pytorch/pytorch/blob/v2.8.0/LICENSE) | Installed official CPU wheel in a separate host venv with its bundled notices; not redistributed with the app | No |
| SpeechBrain Google Speech Commands x-vector, investigated candidate | HF `b0cec0fb42423936ca0da2724ce52d82eb807e20` | [Apache-2.0 model card](https://huggingface.co/speechbrain/google_speech_command_xvector/blob/b0cec0fb42423936ca0da2724ce52d82eb807e20/README.md) | Explicit license is positive evidence; exact checkpoint training/augmentation provenance remains unresolved | No |

The packaged file `app/src/main/assets/metric_kws/THIRD_PARTY_NOTICES.txt` retains the torchaudio
BSD-2-Clause notice, Metric-UD-KWS MIT notice and NAVER helper MIT notice. The new analytic
DSP fixtures are locally authored mathematical signals, not speech, licensed datasets or
training inputs. They are included in tests only. No weights were fetched to generate them.

The repository tree was inspected through GitHub API at the pinned commit. It contains a root
LICENSE and the two checkpoints, but no checkpoint-specific model card in `PT_models`.
The root MIT file is positive evidence for the software; it is not recorded here as a
completed audit of each checkpoint's training sources, augmentation and redistribution rights.
No author was contacted and no permission was inferred from silence.

The README names LibriSpeech Keywords and Google Speech Commands. Training code also references
MUSAN and `rir.npy`. Checkpoint-to-training-recipe correspondence and all provenance details
remain unverified. No such dataset or weights were downloaded, trained on, converted or shipped.
Therefore **new included model SHA-256: not applicable (zero models)**. Git object hashes are
not mislabeled as model SHA-256. `tools/metric_ud_kws/model_manifest.json` keeps this field null.

Alternative investigated: [MM-KWS official source](https://github.com/aizhiqi-work/MM-KWS).
Its documented speech feature pipeline uses XLS-R 300M and several additional text/speech
components. This audit did not establish a lightweight, commercially cleared Russian checkpoint
for that pipeline; it was not substituted into the application. This does not assert that
every possible alternative is unsuitable.

Stage-2 alternative audit: the SpeechBrain model card explicitly names Apache-2.0, English
Google Speech Commands and 12-way command classification. It does not establish Russian
one-shot performance. The cited training commit `b7ff9dc4` could not be retrieved during
this audit. The current [official x-vector recipe](https://github.com/speechbrain/speechbrain/blob/develop/recipes/Google-speech-commands/hparams/xvect.yaml)
contains separate noise/RIR archive URLs whose complete provenance was not established.
That current recipe is not proof of the exact historical checkpoint recipe. The primary
[Google dataset announcement](https://research.google/blog/launching-the-speech-commands-dataset/)
states CC BY 4.0 for Speech Commands; that statement alone does not clear additional training
material. No SpeechBrain checkpoint or training/augmentation dataset was downloaded or used.

## Before admitting a real artifact

Record checkpoint source, SHA-256, exact training recipe and applicable data/augmentation rights;
obtain unambiguous licensing evidence covering those weights. Pin the source and environment.
Record export tools and their complete third-party notices. Verify Russian human speech and
duration policy, export parity, native compatibility, Android inference and shadow results.
Only then add model/license/config assets and select a production encoder in AppModule.

**Native compatibility finding:** the current `sherpa-onnx-1.13.4.aar` already contains
`libonnxruntime.so` for arm64-v8a, armeabi-v7a, x86 and x86_64. Adding a separate ONNX Runtime
AAR can collide with these names. Do not solve this with an arbitrary `pickFirst`: inspect
versions/exports and select a proven compatible packaging/build strategy, then test all ABIs.
The version 1.22.1 above is an inspected candidate, not a chosen or tested Android runtime.
