# Calendar assistant SFT pilot

## Result of source verification

The approved source is
[`RefalMachine/ruadapt_qwen2.5_3B_ext_u48_instruct_v4`](https://huggingface.co/RefalMachine/ruadapt_qwen2.5_3B_ext_u48_instruct_v4),
pinned in the repository to commit
`4b2122a50af59fcd878839bd77f63c104cfe5ab7`.

The source repository supplies a full BF16 Safetensors model rather than only a
GGUF quantisation. Its published configuration identifies `Qwen2ForCausalLM`,
36 layers, vocabulary size 147097 and a Qwen2 tokenizer with a chat template.
That makes a QLoRA pilot technically possible without training the Android
quantised file itself.

The licence status is not confirmed. At the time of verification, the model
card metadata and listed root files did not declare a licence or show a
`LICENSE` file; the listed parent model had no model card. The source lock is
therefore intentionally `UNVERIFIED`. This is a hard gate for a paid or
production training job. It is not a claim that use is prohibited; it means the
repository evidence is insufficient to establish the applicable rights.

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
3. Obtain and record the source model's complete licence chain. Do not change
   the lock status based on an assumption or the model name alone.
4. Run one QLoRA pilot. Use the checkpoint tokenizer's `chat_template`; no
   manually written prompt template is permitted.
5. Compare the base and adapted model on the same independent holdout at the
   same generation settings. Required measures are strict JSON validity, intent
   equality and exact `params` equality. Reply text is validated for contract
   rules but is not exact-match scored.
6. Merge only the selected adapter, convert it to GGUF, and test the output in
   the existing Android import path. The app does not load a LoRA adapter
   separately.

## Google Cloud plan, after the gates

Use a Vertex AI CustomJob rather than managed Gemini tuning: the application
needs a locally importable GGUF result from this specific Qwen-derived model.
The custom job must use a private staging bucket and a least-privilege service
account. It must receive only the locked model snapshot and the fictional SFT
artifacts. It must not receive `calendar_core.db`, Room exports, user chats,
real names, API keys or Android signing material.

No Google Cloud resource, bucket, service account, container registry, billing
account or GPU job is created by this repository change. The exact region, GPU,
cost ceiling and retention policy remain an explicit approval decision.
