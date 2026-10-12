# Clean-room calendar-assistant policy: V12.67

The current protocol is defined by the four-action V12.67 training contract.
The previous policy snapshot is retained in
[the archive](calendar_training_archive/V12_66_CALENDAR_ASSISTANT_CLEAN_ROOM_BEFORE_V12_67.md).

## Decision

The RefalMachine source path is closed because its public licence and data
provenance chain is incomplete. The replacement is an independent narrow model
adaptation: it must reproduce the application's calendar JSON contract, not
copy any RefalMachine intellectual property or claim general Russian-language
superiority.

The historical candidate base lock is
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

The archived v4 register is
[`calendar_sft_data_provenance_v4.json`](calendar_sft_data_provenance_v4.json).
It approves only its exact artifact hashes. The historical v1, v2 and v3
registers also remain immutable. Each register is bound to staged training
artifacts by SHA-256; changing either JSONL file requires a reviewed register
with the new hashes. V12.67 has a separate
[provenance register](calendar_sft_v12_67/provenance.json) retaining the reviewed
internally authored sources and binding the retained train/validation files.

## Product target

The model's scope is the current local four-action protocol:

```json
{"intent":"chat | calendar_search | calendar_add | calendar_sum","reply":"","params":{}}
```

The retained dataset stores its original local timestamp and Europe/Samara
anchor. Training transports append the actual four-action provider contract.
The Android transport also repeats the provider header and application ChatML.

For every calendar intent, the model emits fields known from the user's request
and resolvable relative expressions. The only model-owned default is the
mandatory `calendar_add` date. An explicit date wins; without one, a strictly
later exact time means today, an earlier or equal exact time means tomorrow,
and no exact time means today's `date`. The model omits every other unknown
field, never writes `null`, and never asks the user a question. User-enabled
defaults, including a possible 60-minute duration, belong only to Android.

`value` is a signed whole number of abstract units without currency or decimal
notation. `calendar_sum` carries an optional title filter and an exact local
half-open period when those values are known; the model never calculates or
prints the aggregate result. The complete field and period rules are defined in
[`CALENDAR_ASSISTANT_TRAINING_SPEC.md`](CALENDAR_ASSISTANT_TRAINING_SPEC.md), and
the current client behavior is described in
[`CALENDAR_ASSISTANT_ANDROID_MECHANISMS.md`](CALENDAR_ASSISTANT_ANDROID_MECHANISMS.md).

The dataset covers creation, search, partial fields, relative dates,
integer values, aggregate requests and factual chat. It
must include colloquial Russian forms and occupation contexts without recording
real users' personal data.

## What “better” means

No model may be described as better before an identical, frozen holdout is run
against the base and adapted checkpoints with the same decoding settings. The
adapted checkpoint must not reduce strict JSON validity or intent accuracy and
must improve the exact-parameter score on the calendar holdout. Report each
intent separately: `chat`, `calendar_add`, `calendar_search`, `calendar_sum`.
Do not substitute subjective chat quality for these measures.

The independent holdout remains excluded from SFT and run selection. Reply text
is schema-checked for concise Russian wording; `intent` and `params` are scored
semantically, with independent spoken-clock and arithmetic checks.
The V12.67 evaluator and retained manual holdouts are preparation tools;
their existence is not proof of Qwen3 quality.

## Execution gates

1. Approve the final data register and retain its rights evidence.
   The register must be `VERIFIED`, bind SHA-256 values of both staged SFT
   artifacts, identify a reviewer decision, and state for every source that it
   permits model training and distribution of derivative weights without
   personal data.
2. Explicitly approve downloading the locked base snapshot; calculate SHA-256
   for every file named in the source lock. The verifier must match the
   selected source lock, revision, SHA-256 values and recorded byte size
   for every required file.
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
