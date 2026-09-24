# Metric KWS stage 2: numeric feature reference

This stage adds a Kotlin MFCC extractor and analytic parity fixtures. **It does not add a
trained encoder or enable Metric activation.** `AppModule` still supplies the unavailable
engine. The extractor is internal and has no production caller; it opens no microphone,
starts no coroutine/service and loads no native runtime. See `METRIC_KWS_VALIDATION.json`
for measured results and `METRIC_KWS_LICENSES.md` for the separate weights gate.

## Explicit experimental recipe

`MetricKwsFeatureExtractor.FEATURE_VERSION = metric-cli-mfcc40-1s-v1` identifies this fixed
recipe, not the configuration of an admitted checkpoint. Its source is the CLI defaults and
`KeywordNet.py` at [Metric-UD-KWS commit b26b3df](https://github.com/kaistmm/Metric-UD-KWS/tree/b26b3dffa3ab19255963329eab9e733e441d7486).
`n_mels=40` is a CLI default; the checkpoint manifest deliberately still has null preprocessing.

| Step | Contract |
|---|---|
| Input | Mono float32 PCM, 16,000 Hz, finite values in [-1, 1], 8,000–128,000 samples |
| Duration | Center-pad with zeros or center-crop to 16,000 samples, matching upstream `loadWAV`; odd shortage has one extra sample on the left |
| Window | Periodic Hann, 480 samples; no preemphasis or waveform amplitude normalization |
| STFT | FFT 480, hop 160, centered reflect padding of 240; 241 one-sided bins, no spectrum normalization |
| Spectrum | Squared magnitude (power), then 40 HTK triangular mel bands, 0–8,000 Hz, no Slaney normalization |
| dB | 10 log10(max(power, 1e-10)); reference 1; top_db=80 using one maximum over the entire utterance |
| DCT | Type II, orthonormal, 40 coefficients; no deltas or cepstral normalization |
| Output | Flat row-major [101 frames, 40 coefficients], corresponding to encoder input [1, 101, 40] |

The Kotlin FFT is a complete mixed-radix transform for 480=2^5*3*5. Intermediate accumulation
uses doubles, with float32 window/filter/DCT coefficients and output. Per-call scratch arrays
are not shared; the input array is not modified. Scratch arrays are cleared on return, without
claiming forensic erasure of runtime copies. Silence is allowed in the DSP itself; enrollment
audio-quality rejection remains a separate responsibility of `MetricKwsAudio`.

One-second center cropping can discard words or commands. This experimental policy must not
be connected to arbitrary multiword activation before duration/localization validation.

## Reference, fixtures and tolerance

Reference: CPU PyTorch **2.8.0+cpu** and torchaudio **2.8.0+cpu**, Python 3.13.7, numpy 2.5.1,
Windows 10.0.19045, float32, one Torch thread, deterministic algorithms. This is an explicitly
pinned DSP reference, **not a reproduction of upstream's Python 3.8 / torch 1.10.1 /
torchaudio 0.10.1 environment**. The earlier MFCC source was inspected, but no old-runtime
or trained-model equivalence is claimed.

The task installed only Torch and torchaudio into `build/metric_kws_reference/venv` using
`--no-deps`; that venv inherits the host's existing packages. The active reference dependency
versions are recorded separately in `tools/metric_ud_kws/dsp_environment.json`. The application
has no new Gradle, AAR or native dependency.

`generate_dsp_fixtures.py` authors 17 deterministic non-speech cases: silence, edge impulses,
tone, chirp, mixed tones, noise, extremely quiet noise, DC, Nyquist, an amplitude transition
that distinguishes global from per-frame dB clipping, and seven crop/pad boundary lengths.
No personal recordings or training dataset were used. Each binary has an MKF1 header,
sample count, feature count, little-endian float32 PCM and the complete reference MFCC matrix.
The manifest records SHA-256, environment, shape, recipe and the **0.002 absolute-error limit**.
This gate was set before the first parity run; it is not a keyword-score threshold.

Both JVM and Android use the same test helper to check hashes, dimensions, every output value,
maximum absolute error, and input preservation. There are 68,680 compared features per platform.
Fixtures are resources of unit tests and assets of the instrumentation APK only. The application
APK includes the Kotlin implementation and relevant MIT/BSD notices, with no analytic PCM assets.

These checks establish numeric agreement only for the supplied fixtures and the stated recipe.
They do not prove Russian accuracy, owner verification, ONNX embedding parity, microphone
enrollment, inference performance or battery savings. ARM device parity remains a separate gate.

## Reproduction

Use the pinned reference environment to regenerate fixtures explicitly (this overwrites only
named fixture files and their manifest in the given directory):

```powershell
& build/metric_kws_reference/venv/Scripts/python.exe -B tools/metric_ud_kws/generate_dsp_fixtures.py --output app/src/test/resources/metric_kws
.\gradlew.bat :app:testDebugUnitTest --tests '*MetricKwsFeature*' --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

For an explicitly selected test device, install both APKs and run
`com.example.aiassistent1.audio.MetricKwsFeatureExtractorTest` and `MetricKwsStorageTest`
with `androidx.test.runner.AndroidJUnitRunner`. Never select an arbitrary physical device
when an isolated emulator is intended. Read per-case numeric output with the `MetricKwsParity`
logcat tag. The stage-2 run also includes the existing `VoiceEnrollmentTest` regression.

Test source directories use the built-in Kotlin source set API, documented by
[Android Developers](https://developer.android.com/build/migrate-to-built-in-kotlin).
Shared assertions are test sources only and cannot enter the application's classes.
