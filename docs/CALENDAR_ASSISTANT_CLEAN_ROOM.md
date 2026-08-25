# Clean-room calendar-assistant model

## Decision

The RefalMachine source path is closed because its public licence and data
provenance chain is incomplete. The replacement is an independent narrow model
adaptation: it must reproduce the application's calendar JSON contract, not
copy any RefalMachine intellectual property or claim general Russian-language
superiority.

The candidate base lock is
[`Qwen/Qwen3-4B-Instruct-2507`](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507)
at `cdbee75f17c01a7cc42f958dc650907174af0554`. The official model card and
bundled licence declare Apache-2.0. The full lock is
[`clean_room_qwen3_source_lock.json`](../tools/calendar_sft/clean_room_qwen3_source_lock.json).

Apache-2.0 verification applies only to this base checkpoint. It does not grant
rights to use third-party training material, user messages, calendar data or
outputs from another model as a teacher.

## Clean-room boundary

Do not use any RefalMachine model weights, quantisations, adapters, tokenizer
files, training data, code or generated responses. Do not use model outputs
created by querying that model as SFT labels. Do not include Room databases,
calendar exports, user chats, contacts, APK signing material, API keys or
production telemetry.

Allowed data is limited to internally authored calendar examples or separately
licensed material with documented permission for model training and derivative
weight distribution. A dataset register based on
[`dataset_provenance.template.json`](../tools/calendar_sft/dataset_provenance.template.json)
must be completed and reviewed before a real training run.

The current pilot register is
[`calendar_sft_data_provenance_v1.json`](calendar_sft_data_provenance_v1.json).
It is bound to the staged training artifacts by SHA-256; changing either JSONL
file requires a new review and register version.

## Product target

The model's scope is the local calendar protocol already implemented by the
application:

```json
{"intent":"chat | calendar_search | calendar_add | calendar_update | calendar_delete","reply":"","params":{}}
```

Training messages use the application system prompt exactly as rendered at
runtime:

```text
Сегодня дата и время:<DATE> (<WEEKDAY>) <TIME> <IANA_ZONE> ответ JSON
```

The dataset must cover creation, search, partial fields, relative dates,
multi-turn corrections, updates to the last event, named-event updates, and
immediate deletion of one resolved local event. It
must include colloquial Russian forms and occupation contexts without recording
real users' personal data.

## What “better” means

No model may be described as better before an identical, frozen holdout is run
against the base and adapted checkpoints with the same decoding settings. The
adapted checkpoint must not reduce strict JSON validity or intent accuracy and
must improve the exact-parameter score on the calendar holdout. Report each
intent separately, including `calendar_update` and `calendar_delete`; do not substitute subjective
chat quality for these measures.

The independent holdout remains excluded from SFT and run selection. Reply text
is schema-checked for concise Russian wording; `intent` and `params` are scored
semantically. The existing evaluator and manual holdout are the starting point,
not proof of Qwen3 quality.

## Execution gates

1. Approve the final data register and retain its rights evidence.
   The register must be `VERIFIED`, bind SHA-256 values of both staged SFT
   artifacts, identify a reviewer decision, and state for every source that it
   permits model training and distribution of derivative weights without
   personal data.
2. Explicitly approve downloading the locked base snapshot; calculate SHA-256
   for every file named in the source lock. The verifier must match the
   official LFS SHA-256 values for the three Safetensors shards and
   `tokenizer.json`, and the recorded byte size for every locked file.
3. Update the training environment to
   [`requirements-train-qwen3.txt`](../tools/calendar_sft/requirements-train-qwen3.txt)
   in an approved CUDA image.
4. Run a local dry run using the source lock and the checkpoint tokenizer. It
   verifies configuration, tokenizer rendering, every dataset row and QLoRA
   target-module configuration, but deliberately does not load weights or start
   training.
5. Approve one bounded QLoRA pilot only after the dry run and source/data
   integrity checks pass.
6. Compare base and adapter on the frozen holdout, then approve merging.
7. Pin the GGUF converter, retain its version and output SHA-256, and validate
   the resulting Qwen3 GGUF on a target Android device before changing the app's
   selected model.

No model snapshot, cloud resource, GPU job, adapter, GGUF file or Android model
setting is created by this document and source-lock stage.
