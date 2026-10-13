# V12.67 — Qwen3-1.7B training

This isolated profile applies the manually reviewed V12.67 four-action calendar data to the already pinned Qwen3-1.7B source. It records its run under `build/calendar_v12_67_qwen3_17b_20261013/qwen3_1_7b` and leaves the earlier 1.7B, 0.6B, and historical run records in place.

The checked data contains 5,405 training rows and 689 validation rows. The matching manual audit covers 6,616 conversations with no missing or duplicate positions and no new confirmed discrepancies. The verified tokenizer profile reports a maximum of 766 tokens for the full training transports and no truncation.

The planned run uses QLoRA NF4 and assistant-completion loss: three epochs, 1,014 optimizer steps, effective batch 16, micro-batch 2, LoRA rank 32/alpha 64, and learning rate 0.0001. A GPU forward/backward smoke is required before training. Checkpoint selection uses development examples only; independent holdouts stay outside selection. GPU power is capped at 150 W.

The selected adapter is merged once, then exported as F16, Q4_K_M, and Q8_0. Release evaluation uses 412 independent questions in both standard and Android ChatML, 824 responses per format, the full V12.67 system contract, and a 1,024-token context. Factual temporal replies in `chat` are reviewed separately because the current automatic grader does not check their date/time meaning.

Phone delivery uses a unique staging file, then verifies remote byte count and SHA-256 before `mv -n` and verifies the final file. A verified copy shows delivery only; on-device inference is recorded separately.
