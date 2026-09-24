# Metric UD-KWS: blocked reference and validation tools

Status: **no approved checkpoint, no model in APK, no Russian quality or ONNX parity claim**.
The tools are an offline harness for the next evidence-gated stage, not a reproduced model result.
No tool downloads weights, recordings, dependencies or uploads user data.

## Reproducibility and prerequisites

Audited upstream: https://github.com/kaistmm/Metric-UD-KWS/tree/b26b3dffa3ab19255963329eab9e733e441d7486

The original requirements specify Python 3.8, PyTorch 1.10.1, torchaudio 0.10.1,
and additional unpinned scientific packages. Stage 2 created a separate project-local venv
with CPU torch/torchaudio 2.8.0+cpu for numeric DSP tests only. It inherits existing host
dependencies; `dsp_environment.json` records the active reference dependency versions.
ONNX and ONNX Runtime were not installed. This is **not** a reproduced original
training/evaluation environment. Never install the upstream requirements blindly.

`model_manifest.json` intentionally contains null artifact-specific fields. Complete them only
from verified checkpoint provenance/configuration. License evidence strings must reference
reviewed primary documents; setting a flag is not itself evidence. The harness refuses missing
evidence, wrong SHA, a changed upstream commit, dirty upstream code and incomplete DSP settings.

Upstream `loadWAV` pads or centrally crops to **one second**. This harness calls that exact
function rather than guessing a different duration policy. It therefore does not establish
recognition of arbitrary multiword phrases. A fixed, experimental Android MFCC implementation
now has numeric parity tests against the 2.8.0 DSP reference (see `docs/METRIC_KWS_DSP.md`).
It is not connected to a checkpoint or to activation. Encoder/runtime integration still
requires a suitable licensed checkpoint, phrase-duration policy and model reference.

## Numeric DSP stage (no weights required)

```powershell
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/generate_dsp_fixtures.py --output app/src/test/resources/metric_kws
.\gradlew.bat :app:testDebugUnitTest --tests '*MetricKwsFeature*' --console=plain
```

The generator uses only locally authored analytic signals and pinned CPU torch/torchaudio.
It writes complete float32 PCM/MFCC fixtures, SHA-256 and an explicit recipe. No recordings,
models or datasets are downloaded. Fixtures are test-only; keep personal speech out of this
directory. These results cannot admit the blocked model manifest or validate Russian KWS.

## Commands after prerequisites are met

Use a clean, reviewed local checkout at the pinned commit and an approved local checkpoint.
Every command is offline. Outputs containing features/embeddings are evaluation artifacts:
keep them out of public source control when derived from personal recordings.

```powershell
python tools/metric_ud_kws/verify_reference.py --manifest approved.json --weights approved.model --upstream C:/local/Metric-UD-KWS --wav fixture.wav --output reference.json
python tools/metric_ud_kws/export_onnx.py --manifest approved.json --weights approved.model --upstream C:/local/Metric-UD-KWS --wav fixture.wav --output encoder.onnx
python tools/metric_ud_kws/compare_onnx.py --model encoder.onnx --model-sha256 ACTUAL_SHA256 --fixture reference.json --output parity.json
python tools/metric_ud_kws/test_russian.py --manifest approved.json --weights approved.model --upstream C:/local/Metric-UD-KWS --cases local_cases.json --threshold CALIBRATED_THRESHOLD --output russian.json
```

Export uses CPU encoder only, opset 17; compatibility still requires testing with the selected
runtime. Strict state-dict loading prevents silently running random/unmatched parameters.
Parity gate: maximum absolute error of normalized embeddings <= 1e-4 AND cosine >= 0.9999.
Run multiple fixtures, durations and amplitudes; one passed fixture does not establish parity
for an Android feature extractor. The present tools have not performed any model inference.

## Russian cases

Cases are a JSON array, each with `enrollment`, `candidate` (local WAV paths), `phrase_id`,
`owner_id`, `speaker_id`, `rights_evidence`, `language: "ru"`, `source: "human" | "synthetic"`,
and boolean `same_keyword`. Use exactly one enrollment recording per phrase/owner pair;
evaluate separate utterances. Never evaluate a copy of the enrollment audio as success.

Include Ассистент, Привет помощник, Слушай меня, Компьютер, Доброе утро, Включайся,
Помощник проснись; normal/faster/slower/louder/quieter/distance/noise, confusable phrases,
unrelated speech and different speakers. Calibrate on a separate split; keep final evaluation
fixed. The KWS report is speaker-invariant; evaluate the four combinations of keyword/owner
with the actual speaker verifier separately. Synthetic cases alone cannot validate Russian.

## Safe tests available now

```powershell
python -B -m unittest discover -s tools/metric_ud_kws -p test_reference_contract.py
```

These standard-library tests cover admission/embedding safety only. They do not need or
simulate pretrained weights. See `docs/METRIC_KWS.md` and `docs/METRIC_KWS_LICENSES.md`.
