# Russian personal metric encoder v2

Ready ONNX and PyTorch artifacts, trained from random on 800 Russian words /
51,200 MSWC clips. Model license: CC BY 4.0; attribution and full text included.
Architecture: our residual metric CNN, not the original Metric-UD-KWS ResNet15.

Use encoder.onnx with the exact `metric-cli-mfcc40-1s-v1` Kotlin frontend and
L2-normalize its 64-component output. Input normalization is embedded in ONNX.
Default dev-calibrated cosine threshold: 0.598879337310791. `model.json`
records shapes, hashes, additional operating points and their measured errors.
Create a fresh enrollment after changing the encoder; embeddings are versioned.

Fresh test, 563 same-owner positives / 17,774 same-owner wrong-word negatives:
v1 missed 59.50% and falsely accepted 1.51%; v2 missed 19.36% and falsely accepted
0.72%. Both models used their own dev threshold targeting <=1% negative-pair FAR.
At the separately dev-calibrated 2.5% operating point, v2 missed 8.88% and falsely
accepted 2.35%. It is an explicit sensitivity tradeoff, not the default gate.

These figures are isolated-word corpus measurements, not false activations per
hour. Full phrases, noisy rooms, actual microphone enrollment and end-to-end
Voice ID activation are not validated. AppModule activation remains disabled.
This artifact is ready for integration/shadow evaluation, not certified for
unattended production activation. See docs/METRIC_KWS_RU_V2.md for Android evidence.

No upstream, PLiX, ImageNet or other pretrained weights were used. Corpus sources,
speaker separation and recording hashes are in corpus.json.gz outside the compact
deployment ZIP. Its decompressed SHA is in provenance.json. Training history,
fixed test errors and baseline comparison are in training.json/error_analysis.json.
The CPU environment inherits host packages, as stated in environment.json.

ONNX SHA-256: 21eda065e55135bac590edd9dace1bb6752dc450125a4baf07603d3baf5f514e
PyTorch SHA-256: 81661fab73b3473b5a3ae0c5b41df35b9689c180d26441fc01b3ccadc7311650
