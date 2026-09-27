# Metric UD-KWS: reference tools and separate Russian experiment

Status: **upstream weights remain blocked; our separate Russian v3 has explicit experimental app opt-in**.
Current integration and phone test scenario: `docs/INDEPENDENT_VOICE_MODES.md`.
Word enrollment/activation and Voice ID are now independent optional functions;
the model weights, frontend and threshold remain unchanged.
`prepare_russian.py`, `train_russian.py`, `export_russian.py` and `ru_model.py` implement
the from-scratch Russian path. Records and checkpoint: `experiments/ru-mswc-v1/`.
The unchanged upstream harness is not a reproduced published-model result.
No tool downloads weights, recordings, dependencies or uploads user data.

The completed **v2** model is in `experiments/ru-mswc-v2/ru-mswc-personal-v2.zip`.
It keeps the MFCC interface but uses an own residual encoder, expanded Russian
data and owner-focused metric episodes. See `docs/METRIC_KWS_RU_V2.md` for the
fresh comparison, physical ARM parity and explicit activation limitations.

The **v3** package is `experiments/ru-mswc-v3/ru-mswc-personal-v3.zip`.
It fine-tunes v2 on exactly 102,400 human recordings. The 384 additional training
words and 49 difficult pairs are manually authored; audio selection/quality checks
are automatic, with zero individually human-listened recordings. Training uses
48 pairs after one acoustic ambiguity exclusion. The same new-source test shows
fewer misses but more false accepts at the selected dev-calibrated operating point.
See `docs/METRIC_KWS_RU_V3.md` for both metrics, strata and Android evidence.

## Reproduce v3

Keep the v1/v2 manifests and the original v2 training corpus for reservations and
lineage checks. These commands use fresh output directories and existing archives;
they perform no network operations. `rebalance_russian_v3_eval.py` finalizes owner
pairs before training, never after looking at model scores. Dev chooses the model
and threshold; `finalize_russian_v3.py` evaluates final test once and refuses repeats.
The already published test is now inspected; replay does not create fresh evidence.

```powershell
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/prepare_russian_v3.py --output build/metric_kws_ru/corpus-v3-replay
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/rebalance_russian_v3_eval.py --output build/metric_kws_ru/corpus-v3-replay
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/train_russian_v3.py --corpus build/metric_kws_ru/corpus-v3-replay --output build/metric_kws_ru/run-v3-replay --threads 8
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/finalize_russian_v3.py --run build/metric_kws_ru/run-v3-replay --corpus build/metric_kws_ru/corpus-v3-replay
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/export_russian_v3.py --run build/metric_kws_ru/run-v3-replay --corpus build/metric_kws_ru/corpus-v3-replay --output build/metric_kws_ru/export-v3-replay
```

The saved v3 run stopped at 15,000 of at most 24,000 steps; best checkpoint 7,000.
This follows the predeclared eight-validation plateau rule with minimum 12,000
steps. Input normalization and the Kotlin MFCC interface are preserved from v2.

## Reproduce v2

Use fresh output directories. The original v1 corpus is retained as a reservation
manifest; never include its inspected dev/test words in new training. These
commands use the same already-audited archives and pinned CPU environment.

```powershell
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/prepare_russian.py --splits build/metric_kws_ru/data/ru-splits.tar.gz --audio build/metric_kws_ru/data/ru-audio.tar.gz --output build/metric_kws_ru/corpus-expanded-new --train-words 800 --eval-words 32 --train-clips 64 --eval-clips 24 --reserved-manifest build/metric_kws_ru/corpus-v2/corpus.json --balanced
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/train_russian_v2.py --corpus build/metric_kws_ru/corpus-expanded-new --output build/metric_kws_ru/run-expanded-new --steps 16000 --validate-every 1000 --threads 8
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/finalize_russian_v2.py --run build/metric_kws_ru/run-expanded-new --corpus build/metric_kws_ru/corpus-expanded-new
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/export_russian.py --run build/metric_kws_ru/run-expanded-new --corpus build/metric_kws_ru/corpus-expanded-new --output build/metric_kws_ru/export-expanded-new --asset-prefix metric_kws_ru_v2
```

`finalize_russian_v2.py` refuses incomplete training and repeated finalization.
Train/threshold/checkpoint selection uses dev only. The already reported v2 test
is now inspected: a subsequent new model acceptance claim needs a newly reserved
test set. Replay of the same recipe is a reproducibility check, not fresh evidence.

## Reproducibility and prerequisites

Audited upstream: https://github.com/kaistmm/Metric-UD-KWS/tree/b26b3dffa3ab19255963329eab9e733e441d7486

The original requirements specify Python 3.8, PyTorch 1.10.1, torchaudio 0.10.1,
and additional unpinned scientific packages. Stage 2 created a separate project-local venv
with CPU torch/torchaudio 2.8.0+cpu for numeric DSP tests only. It inherits existing host
dependencies; `dsp_environment.json` records the active reference dependency versions.
Stage 3 added pinned host export/decoder packages recorded in
`experiments/ru-mswc-v1/environment.json` and `host_wheels.json`. This is **not** a
reproduced original training/evaluation environment. Never install upstream requirements blindly.

`model_manifest.json` intentionally contains null artifact-specific fields. Complete them only
from verified checkpoint provenance/configuration. License evidence strings must reference
reviewed primary documents; setting a flag is not itself evidence. The harness refuses missing
evidence, wrong SHA, a changed upstream commit, dirty upstream code and incomplete DSP settings.

Upstream `loadWAV` pads or centrally crops to **one second**. This harness calls that exact
function rather than guessing a different duration policy. It therefore does not establish
recognition of arbitrary multiword phrases. A fixed, experimental Android MFCC implementation
now has numeric parity tests against the 2.8.0 DSP reference (see `docs/METRIC_KWS_DSP.md`).
The separate Russian encoder uses that exact frontend. Activation and multiword support
remain unvalidated; one-second numeric parity does not establish phrase recognition.

## Reproduce the Russian experiment

The official MSWC source/terms and exact archive SHA-256 are in `prepare_russian.py` and
`experiments/ru-mswc-v1/corpus.json.gz`. Obtain those two archives explicitly from MLCommons;
the scripts have no network/download side effects. The selected words/speakers/source clips
must remain disjoint. A failed preparation leaves an incomplete directory; use a new output.

```powershell
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/prepare_russian.py --splits build/metric_kws_ru/data/ru-splits.tar.gz --audio build/metric_kws_ru/data/ru-audio.tar.gz --output build/metric_kws_ru/corpus-new
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/train_russian.py --corpus build/metric_kws_ru/corpus-new --output build/metric_kws_ru/run-new --steps 1500 --threads 8
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/export_russian.py --run build/metric_kws_ru/run-new --corpus build/metric_kws_ru/corpus-new --output build/metric_kws_ru/export-new
& build/metric_kws_reference/venv/Scripts/python.exe -B -m unittest discover -s tools/metric_ud_kws -p 'test_*contract.py'
```

The committed ONNX and licensed speech fixtures are under `app/src/androidTest/assets/metric_kws_ru`.
They are excluded from the application APK by the Android source set, with narrow gitignore
exceptions for these two test ONNX files. This v1 artifact remains test-only;
AppModule now supplies the separately pinned v3 bundle with lazy initialization.
`OnnxMetricKwsEngine.createForExperiment` admits only the fixed frontend/dimensions and matching
SHA, serializes inference/close, normalizes embeddings, and always reports activation unvalidated.
Full evaluation, test/phone distinctions and license audit: `docs/METRIC_KWS_RU_EXPERIMENT.md`.

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
for an Android feature extractor. These upstream tools remain unexecuted against uncleared
weights; the separate Russian path has its own trained-model and parity evidence.

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
