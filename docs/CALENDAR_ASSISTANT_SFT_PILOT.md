# Calendar assistant SFT pilot: archived RefalMachine path

## Result of source verification

The originally evaluated source was
[`RefalMachine/ruadapt_qwen2.5_3B_ext_u48_instruct_v4`](https://huggingface.co/RefalMachine/ruadapt_qwen2.5_3B_ext_u48_instruct_v4),
pinned in the repository to commit
`4b2122a50af59fcd878839bd77f63c104cfe5ab7`.

The source repository supplies a full BF16 Safetensors model rather than only a
GGUF quantisation. Its published configuration identifies `Qwen2ForCausalLM`,
36 layers, vocabulary size 147097 and a Qwen2 tokenizer with a chat template.
That makes a QLoRA pilot technically possible without training the Android
quantised file itself.

Public licence-chain review was completed on 2026-08-25. The model-card
metadata and listed root files did not declare a licence or show a `LICENSE`
file; the listed parent model had no model card, the adapter declared an unknown
dataset and the listed datasets did not establish a complete rights chain. The
card identifies a Qwen2.5 lineage, whose official 3B-Instruct source is
research-only unless Alibaba Cloud grants a separate commercial licence.

The source lock is therefore `NOT_CLEARED`. Do not use this path for download,
training, merging, conversion, redistribution or a production release. This
document is retained only as a historical technical record. The active path is
[`CALENDAR_ASSISTANT_CLEAN_ROOM.md`](CALENDAR_ASSISTANT_CLEAN_ROOM.md).

## Training input contract

The preparer combines only these fictional, checked-in sources:

| Split | Source | Purpose |
| --- | --- | --- |
| Train | train seed + 1,200 generated candidates | SFT loss |
| Validation | eval seed + 300 generated candidates | choose/check a run |
| Holdout | 43 independent authored cases | final comparison only |

It rejects duplicate system-and-user prompts within a split and rejects any
overlap across splits. It normalises legacy candidate system messages to the
exact compact string emitted by Android:

```text
Сегодня дата и время:$currentDateTime $timeZone ответ JSON
```

The Android prompt renderer no longer prepends an empty system turn. This keeps
the production ChatML sequence aligned with the checkpoint tokenizer and the
SFT records.

## Pilot decision gates

1. Run the dataset validation and archive its generated `manifest.json`.
2. Stage the locked source snapshot and calculate SHA-256 values for every
   expected file.
3. This RefalMachine path is closed. Do not run a QLoRA pilot from it.
4. For the independent path, use the checkpoint tokenizer's `chat_template`; no
   manually written prompt template is permitted.
5. Compare the base and adapted model on the same independent holdout at the
   same generation settings. Required measures are strict JSON validity, intent
   equality and exact `params` equality. Reply text is validated for contract
   rules but is not exact-match scored.
6. Merge only the selected adapter, convert it to GGUF, and test the output in
   the existing Android import path. The app does not load a LoRA adapter
   separately.

## Google Cloud plan, after the gates

Use a Vertex AI CustomJob rather than managed Gemini tuning only for a later,
separately approved clean-room checkpoint: the application needs a locally
importable GGUF result.
The custom job must use a private staging bucket and a least-privilege service
account. It must receive only the locked model snapshot and the fictional SFT
artifacts. It must not receive `calendar_core.db`, Room exports, user chats,
real names, API keys or Android signing material.

No Google Cloud resource, bucket, service account, container registry, billing
account or GPU job is created by this repository change. The exact region, GPU,
cost ceiling and retention policy remain an explicit approval decision.
