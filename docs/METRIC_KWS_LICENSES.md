# Metric KWS: artifact admission and third-party licenses

Audit updated: 2026-09-26. **No third-party pretrained model or additional ORT runtime is
added to the application APK.** Stage 3 adds our JNI bridge and an MIT ORT C API header;
the separately trained Russian model and attributed speech fixtures are test-APK-only.
Stage 2 includes an experimental Kotlin DSP implementation and its MIT/BSD notices. Existing Sherpa/ASR/TTS assets are
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
remain unverified. None of those upstream checkpoints or their referenced augmentation inputs
were downloaded, trained on, converted or shipped. There is no admitted upstream model SHA-256;
`tools/metric_ud_kws/model_manifest.json` keeps that field null. The separate stage-3 model
and its exact SHA-256 are documented below. Git object hashes are not model SHA-256 values.

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
material. No SpeechBrain checkpoint or its training/augmentation dataset was downloaded or used.

## Stage 3: Russian candidates and chosen from-scratch path

The Metric repository was rechecked at the same commit: two en/en_kr artifacts, no releases
and no Russian artifact/license clarification found. This is a bounded audit, not proof that
no Russian model exists anywhere. Neither upstream checkpoint was downloaded or used.

**PLiX** [model card](https://huggingface.co/aaqibsaeed/plixkws/tree/80e18934275fc14ced9cde742949c00c5928be26)
explicitly declares Apache-2.0 and includes Russian `small_ru_model.pt` (3,323,203 bytes;
LFS SHA-256 `3a89d0223e2b07f1b6346eb071f155a9d4748fcb5a453d61b766ff7eeda584c7`).
The [paper](https://arxiv.org/pdf/2305.03058) section 2.2.3 states ImageNet-pretrained
initialization. [ImageNet terms](https://image-net.org/download.php) restrict dataset use
to non-commercial research; [timm's weight guidance](https://github.com/huggingface/pytorch-image-models/tree/v0.6.13#pretrained-weights)
does not resolve downstream commercial rights. Thus this audit does not clear that training
chain under the user's stricter requirement; it does **not** declare the model unlawful.
PLiX also uses 64 log-mel bands, not this project's MFCC40. Its pinned config's `small_ru`
URL points to `small_rw_model.pt`, an additional artifact-selection hazard. No weights were fetched.
Only the Apache-2.0 PyPI `plixkws==1.0` wheel was inspected without executing/installing it;
SHA-256 `958f42ca5975627cc955f52e39d96d58a2d2b53de83a77b7fd45640cc5d65738`.

EfficientWord-Net at `adfd4119aabe793b435e522ed7e0e70a768e9edb` has Apache-2.0 code,
but uses a different 64-log-filterbank frontend and did not supply sufficient Russian
artifact-specific one-shot evidence for this audit. It was not substituted.

Chosen experiment: train ResNet15 **from random initialization** on a bounded MSWC Russian
subset. No ImageNet, upstream Metric, PLiX, MUSAN, RIR or other pretrained/training inputs
are used. This preserves the Kotlin MFCC exactly. It is a new training recipe, not a
reproduction of published Metric-UD-KWS quality. Its poor measured recall blocks activation.

| New component | Version / evidence | Commercial conditions / notices | Distribution |
|---|---|---|---|
| MSWC Russian audio/splits | v1.0, [MLCommons](https://mlcommons.org/datasets/multilingual-spoken-words/) | CC BY 4.0; source/creator attribution, changes, license link; no identification attempts | 24 attributed converted speech fixtures in test APK only; full corpus stays in ignored build directory |
| Own Russian checkpoint / ONNX | `experiments/ru-mswc-v1`, exact hashes below | Newly offered under CC BY 4.0 with dataset attribution; application license unchanged | Checkpoint in tools; ONNX in test APK only |
| ResNet15 topology adaptation | pinned KAIST source above | Castorini and KAIST MIT notices retained in Python and test assets | Host training code only |
| ORT C API header | v1.22.1, [MIT](https://github.com/microsoft/onnxruntime/blob/v1.22.1/LICENSE) | Header and complete MIT notice retained | Header compiled into new bridge; existing runtime reused |
| SoundFile | 0.13.1, [BSD-3-Clause](https://github.com/bastibe/python-soundfile/blob/0.13.1/LICENSE) | Notice retained in host wheel and tools/licenses | Host-only decoder |
| libsndfile | 1.2.2, [LGPL-2.1-or-later](https://github.com/libsndfile/libsndfile/blob/1.2.2/COPYING), [commercial FAQ](https://libsndfile.github.io/libsndfile/FAQ.html#q21) | Used through the unmodified SoundFile DLL; license retained, no Android linking/redistribution. LGPL is distinct from prohibited GPL/AGPL; no decoder code is copied into model | Host only |
| Opus / Ogg codec family | [Opus license](https://github.com/xiph/opus/blob/v1.5.2/COPYING), [Ogg](https://github.com/xiph/ogg/blob/master/COPYING) | BSD notices; host decoder bundle, no app codec added | Host only |
| ONNX | 1.19.0, Apache-2.0 source above | License retained by installed wheel | Host export only |
| ONNX Runtime | 1.22.1 Windows CPU wheel, MIT source above | Wheel includes its notices; Android uses the existing Sherpa library instead | Host comparison only |
| ml_dtypes | 0.5.3, [Apache-2.0](https://github.com/jax-ml/ml_dtypes/blob/v0.5.3/LICENSE) | Wheel license retained | Host only |
| coloredlogs / humanfriendly | 15.0.1 / 10.0, [MIT](https://github.com/xolox/python-coloredlogs/blob/15.0.1/LICENSE.txt), [MIT](https://github.com/xolox/python-humanfriendly/blob/10.0/LICENSE.txt) | Wheel notices retained | Host only |
| pyreadline3 | 3.5.4, [BSD](https://github.com/pyreadline3/pyreadline3/blob/master/LICENSE.md) | Wheel notices retained; console helper | Host only |

All seven downloaded host wheel hashes and installed dependency versions are recorded in
`tools/metric_ud_kws/experiments/ru-mswc-v1`. Core torch/torchaudio and the inherited host
packages were already present; they are not included in Android. This venv inherits global
packages and is not described as a clean isolated lockfile environment.

Own checkpoint SHA-256: `dbe2422f38bf0206a50c538a8f64b2b5c49c02ca6636c73e1f82bf728aa7a5df`.
Test ONNX SHA-256: `a2e04582ad137513be95cf3f0e36210b3f0a84e356ac6a7a16a9583b8941b39b`.
Russian audio archive SHA-256: `1608eb651462f0f278b6ace0a8967c8e980ec0f44a648312f26c8b385389d631`.
Russian splits SHA-256: `f076d6e56a18709814ec859bcbc2fdc1f97a580ea6dd641cd45b80c035ddbf76`.
The official page's Russian Cloudflare audio/splits links currently point to Polish `pl`
archives. The correct official-mirror `ru` paths were downloaded and their Russian members
checked. Exact URLs and member hashes are recorded in the corpus manifest.

## V2 retraining artifacts

`experiments/ru-mswc-v2` uses the same SHA-pinned CC BY 4.0 Russian audio/splits and
already audited host dependencies. No new external weights, training corpus or
SDK were added. `ru_model_v2.py` is an own MIT residual metric CNN, trained from
random initialization. It is not the KAIST ResNet15 checkpoint. Its generated
noise and echo have no external source recordings. The new checkpoint and ONNX
are offered under CC BY 4.0 with MSWC attribution, full license and change notices
included in both the model ZIP and test assets. Code/application licenses remain
unchanged. Exact artifact/source hashes and installed versions are in the v2
experiment directory; measured scope is in `METRIC_KWS_RU_V2.md`.

## V3 manually curated expansion

`experiments/ru-mswc-v3` fine-tunes the project's own v2 checkpoint, whose SHA and
random-initialization lineage are checked by the exporter. It uses 51,200 more
human recordings from the same audited Russian archives: 102,400 total. No new
external weights, corpus, SDK or dependency was downloaded. The manual word/pair
plan, automatic quality report, full data provenance and initial checkpoint SHA
are retained. No individual human listening is claimed. Generated weights and
speech fixtures retain CC BY 4.0 attribution and change notices, bundled in both
the ZIP and instrumentation assets; app/source licenses are unchanged. See
`METRIC_KWS_RU_V3.md` for the measured result and limits. Training-only acoustic
exclusions are documented with their linguistic sources in
`tools/metric_ud_kws/curation/ru_v3_acoustic_exclusions.json`.

The subsequent approved opt-in integration bundles this exact unchanged v3 ONNX
in `app/src/main/assets/metric_kws/v3/`, alongside its full CC BY 4.0 terms,
attribution/change notice and model metadata. Public speech fixtures remain in
the instrumentation assets; they are not added to the application. No new runtime
or pretrained weights are introduced. `activation_validated=false` is retained;
experimental opt-in is an application policy, not a new quality/licensing claim.

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
