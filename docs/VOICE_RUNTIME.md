# Voice Runtime Baseline

## Sherpa-ONNX runtime

The application uses the official `sherpa-onnx-1.13.4.aar` release artifact.

- Source: `https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4`
- File: `app/libs/sherpa-onnx-1.13.4.aar`
- SHA-256: `03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780`
- Runtime license: Apache License 2.0
- Included ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

Do not replace this artifact without recording its release tag, SHA-256,
included ABIs, and license in this document.

## Models

Model licenses are independent from the Sherpa-ONNX runtime license.

### Approved for commercial distribution

| Role | Model | Source | SHA-256 | License |
| --- | --- | --- | --- | --- |
| ASR | `sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16` | `k2-fsa/sherpa-onnx` release `asr-models` | `20a439491904b839f35eb8efaa3d99cbfaaad0c6dcba22d06ff218bb0056772d` | MIT, GigaChat Team |
| VAD | `silero_vad.int8.onnx` | `k2-fsa/sherpa-onnx` release `asr-models` | `c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20` | MIT, Silero Team |
| TTS | `vits-piper-ru_RU-denis-medium` | Piper voice model card and `NabuCasa/voice-datasets` | `75c9f1117d5e4a620a20cb665408adc0828699f4a7a2eb44a233f8c34096be04` (`.onnx`) | CC0 source dataset |

The ASR package must include its `LICENSE` file. The VAD attribution must
include the MIT license for Silero Team. The Denis model card must accompany
the TTS model because it identifies the CC0 source dataset.

### Excluded from commercial distribution

| Role | Model | Reason |
| --- | --- | --- |
| TTS | `vits-piper-ru_RU-ruslan-medium` | Its model card identifies the source dataset as CC BY-NC-SA 4.0. The non-commercial restriction conflicts with this application. |

Do not add a TTS model until its source, version, SHA-256, commercial
redistribution terms, and required attributions have been recorded here.

The GPLv3 project `glowinthedark/sherpa-ttsEngine` is not a dependency of this
closed-source application.

## Live Transcription Compatibility

The bundled GigaAM RNNT model is supported by the offline `OfflineRecognizer`
configuration used by the app. It is not compatible with the `OnlineRecognizer`
configuration in the bundled Sherpa ONNX `1.13.4` runtime: the native runtime
requires `window_size` metadata that this model does not provide.

Voice-draft capture must therefore continue recording through speech pauses and
append completed VAD segments to the draft. Do not enable `OnlineRecognizer` for
this model until a compatible streaming model has been packaged and verified on
target Android devices.
