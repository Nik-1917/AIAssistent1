# Personal Metric UD-KWS: staged implementation, activation blocked

Date: 2026-09-27. **This is a gated experiment, not a completed KWS product.**
The v2 retraining and ready model package are described in `METRIC_KWS_RU_V2.md`:
fresh owner-word FRR 19.36% at 0.720% pair FAR, with emulator and physical ARM
numerical parity. This does not enable production activation or full phrases.
Stage 3 adds a project-trained Russian ResNet15 encoder, ONNX export and an experimental
Android JNI engine reusing the ORT already in Sherpa. The unchanged stage-2 Kotlin MFCC
is its frontend. Model/speech fixtures are test-only; no new ONNX runtime is packaged.
See `METRIC_KWS_RU_EXPERIMENT.md` for provenance, evaluation and remaining quality limits.
The settings screen explains why recording is unavailable; it does not collect
a voice sample that cannot be enrolled. The existing activation algorithm remains in control.

## Current pipeline and ownership

```text
existing AudioRecord (MicrophoneCoordinator)
  -> existing AEC/NS -> existing VAD -> completed utterance
       -> existing keyword/ASR path -> existing Voice ID -> feedback -> ASR -> LLM
       -> bounded optional Metric shadow observer -> diagnostics ONLY
```

`AppModule` supplies `UnavailableMetricKwsEngine`; the shadow queue is not created for it.
Thus no extra inference or AudioRecord is running. A future available encoder can observe
the same processed utterances through a conflated one-item channel, independent of legacy
recognition. Dropped diagnostic work must not backpressure recognition. Shadow never calls
speaker enrollment/verification, activation sound, ASR, TTS or navigation.

`PersonalKeywordActivation.evaluate` implements/test-checks the future ordered dual gate:
KWS mismatch stops before speaker computation; a successful KWS requires independent owner
verification. Scores are never averaged. This decision is **not connected to live activation**.
The intended validated pipeline is AudioRecord -> VAD -> Metric KWS -> Voice ID -> activation
-> command ASR -> LLM. Opt-in activation wiring must follow model/parity/shadow/device evidence.

DataStore stores only `wake_word_engine`, default `LEGACY_ASR`; unknown values also mean legacy.
The other enum values are reserved for staged rollout. No setting is silently migrated to Metric.
Selecting/storing `METRIC_KWS` cannot activate an unavailable/unvalidated engine. The current
capture still uses legacy even if that reserved value is present. Existing text wake-word,
voice revision, barge-in, conference settings, TTS and backup rules are retained.

## One-shot and preservation of the owner profile

`InputProvider.captureEnrollmentSegment` on the existing Sherpa provider reuses its capture
method, AudioRecord, coordinator, AEC/NS and VAD. It returns the first completed utterance,
does not run command recognition or activation feedback for enrollment, refuses an already
running voice input, and uses a 12-second timeout. An independent microphone foreground
session is released on completion/error/cancellation on Main. Cancellation joins capture
cleanup; nested finally blocks ensure coordinator release even if a cleanup step fails.
Conference acquisition remains governed by the existing coordinator; no concurrent recorder
is allowed. This hardware path is compiled, **not device-validated**.

`enrollFrom` requests exactly one segment, runs one keyword embedding extraction, verifies the
same segment with the existing owner verifier, validates the revision and atomically saves the
keyword profile. It clears the returned PCM array on success/error/cancellation. The prerequisite
in this stage is an **already configured Voice ID**. Disabled Voice ID is not enabled silently.
No new first-time joint KWS/speaker enrollment UX is claimed. The existing speaker profile is
never overwritten, so failed keyword enrollment cannot destroy a working owner profile.
No recording is persisted or transmitted. Additional JVM/native copies cannot be guaranteed
securely erased; this is not a promise of forensic memory erasure.

## Profile and runtime contracts

`metric_kws_profile.bin` lives in `noBackupFilesDir`, separately from `voice_profile.bin`.
The binary codec stores magic/version, model version/SHA-256, feature version, sample rate,
embedding dimensions, audio bounds, keyword threshold, owner revision, timestamp and normalized
keyword embedding, followed by SHA-256. It rejects trailing bytes, excessive file/dimension
sizes, invalid headers, nonfinite/zero/unnormalized vectors and corruption. The checksum detects
damage, not an attacker with access to private storage. AtomicFile replaces the previous profile
only after a fully validated encoding; no pre-enrollment delete occurs. The file is authoritative;
DataStore contains no competing enrolled flag or embedding to become inconsistent with it.

Only an exact config and current voice revision can be used. Missing/corrupt/stale profiles,
missing model, runtime exceptions and linkage errors yield legacy decisions; cancellation
propagates. UI-facing profile status requests re-enrollment on mismatch. Raw PCM guards reject
empty/short/long, nonfinite/out-of-range, near-silent and heavily clipped audio. These conservative
guards and test thresholds are not model quality calibration. A real model config must specify
its own validated limits and threshold. No arbitrary production threshold is shipped.

The service serializes engine operations with a Mutex off Main, exposes only the latest diagnostic
decision (no embedding/phrase logging), and closes its engine once. Each provider owns its encoder;
atomic storage is shared in AppModule. SpeakerIdentifier and legacy KeywordSpotter contracts
are preserved. Deleting a keyword profile does not delete speaker data; absent keyword profile
forces fallback. Enrollment record/delete/ready UI and active opt-in selection remain gated.

## Exact blockers and completion criteria

1. Upstream checkpoints remain uncleared. A separately trained Russian experiment now has
   recorded data/tool/model provenance; its measured recall is insufficient for activation.
   See `METRIC_KWS_LICENSES.md` and `METRIC_KWS_RU_EXPERIMENT.md`.
2. Upstream `loadWAV` centrally crops/pads to one second. Arbitrary multiword Russian wake phrases
   and wake-word-plus-command need a validated duration/localization policy, not guessed MFCC.
3. Public licensed Russian MSWC speech now supplies initial isolated-word evidence. It does
   not cover user-recorded multiword phrases, distance, environmental noise or continuous audio.
4. The original upstream reference harness still refuses its incomplete model manifest.
   The separate Russian experiment is trained/exported, with its own records and no inherited
   published quality claim. Only this ResNet15 configuration was evaluated.
5. Existing Sherpa AAR packages libonnxruntime.so for four ABIs. The new bridge requests C API
   22 from that library and checks fixed names, shapes and float32 types. No Maven runtime,
   binary replacement or pickFirst workaround is added.
6. Stage 2 ran DSP/storage/Voice ID regression tests on a separately created Android 14 x86_64
   emulator. There is no physical-phone evidence: one-shot recording, false activations,
   foreground/screen-off, TTS/barge-in/conferences and battery measurements remain unverified.
   Stage-3 encoder evidence is recorded separately in `METRIC_KWS_RU_VALIDATION.json`.

To complete: admit a legal checkpoint (or train outside Android using audited data), reproduce
reference, validate real Russian one-shot cases, export and establish parity, confirm/adapt
the experimental DSP to the actual checkpoint and add Android inference, run ARM device parity,
validate shadow, then enable opt-in dual-gate activation.
Preserve combined wake-word/command behavior with an explicitly tested policy before activation.
Benchmark silence/background speech/periodic wake scenarios on the same physical phone against
legacy. Record load/warm latency/p95, RAM, CPU time, inference count, wake locks and battery;
no battery-saving claim follows from the current infrastructure.

## Validation

Baseline before edits: `:app:testDebugUnitTest :app:assembleDebug` passed; 271 tests, zero
failures/errors/skips. Debug APK: 720,598,327 bytes. Actual post-change results are recorded in
`METRIC_KWS_VALIDATION.json`. Unit fakes prove routing/storage contracts, not acoustic accuracy.
`MetricKwsStorageTest` exercises Android AtomicFile and separate speaker storage; it now passed
on the stage-2 emulator together with numeric MFCC parity and the existing Voice ID enrollment
regression. No instrumented encoder inference test has run without a model.

Run checks:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
python -B -m unittest discover -s tools/metric_ud_kws -p test_reference_contract.py
```
