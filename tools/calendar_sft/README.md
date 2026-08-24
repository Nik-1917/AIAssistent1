# Calendar SFT pilot tools

These scripts prepare and score a QLoRA pilot for the application's local
calendar assistant. They do not create a Google Cloud project, bucket, service
account, GPU VM, or training job.

## Fixed model source

`model_manifest.json` pins the supplied Hugging Face repository to commit
`4b2122a50af59fcd878839bd77f63c104cfe5ab7`. It expects the original BF16
Safetensors snapshot and its tokenizer, not the Android `Q4_K_M.gguf` file.
The current app imports GGUF only, so an adapter cannot be deployed directly.

The source licence is deliberately marked `UNVERIFIED`. Both `train_qlora.py`
and `merge_adapter.py` block real work while that status remains. A dry run is
allowed because it does not modify model weights.

## Local, no-cost preparation

From the repository root, regenerate the candidate rows after changing the
generator and validate all data without writing anything:

```powershell
python tools/generate_calendar_training_dataset.py
python tools/calendar_sft/prepare_dataset.py --check-only
```

Create the ignored artifacts only after reviewing the source rows:

```powershell
python tools/calendar_sft/prepare_dataset.py
```

The result is `build/calendar_sft_dataset/` with three disjoint files:

- `train.jsonl` is the only SFT input.
- `validation.jsonl` is used during training selection.
- `holdout.jsonl` is never used to tune a model. It contains independently
  authored cases for dates, searches, updates, partial commands and refusals.

Every row is normalised to the exact Android temporal system prompt:

```text
Сегодня дата и время:<DATE> (<WEEKDAY>) <TIME> <IANA_ZONE> ответ JSON
```

## Staged model dry run

After the model source is lawfully staged locally in a directory containing the
locked Safetensors snapshot and a CUDA-compatible Python environment is ready:

```powershell
python tools/calendar_sft/train_qlora.py `
  --model-dir D:\models\ruadapt_qwen2.5_3B_ext_u48_instruct_v4 `
  --train-file build\calendar_sft_dataset\train.jsonl `
  --validation-file build\calendar_sft_dataset\validation.jsonl `
  --output-dir build\calendar_sft_run `
  --dry-run
```

The dry run uses the model's own `chat_template`, tokenizes every example and
rejects silent truncation. It does not load model weights or require a GPU.

## Real training and release

Only after the licence gate is changed to `VERIFIED` with authoritative evidence:

1. Run `train_qlora.py` on one CUDA GPU and retain its `run_manifest.json`.
2. Score generated outputs with `evaluate_predictions.py`; semantic scoring
   compares only `intent` and `params`, while reply wording is separately
   schema-checked.
3. Merge the chosen adapter with `merge_adapter.py`.
4. Convert the merged Safetensors checkpoint using a separately pinned GGUF
   converter, calculate its SHA-256 and test that GGUF on the Android device.

Example scoring command:

```powershell
python tools/calendar_sft/evaluate_predictions.py `
  --predictions D:\results\calendar_holdout_predictions.jsonl `
  --report build\calendar_sft_report.json
```

The prediction file must have one object per holdout case:

```json
{"case_id":"H001","output":"{\"intent\":\"calendar_add\",\"reply\":\"...\",\"params\":{...}}"}
```

## Google Cloud boundary

The later cloud target is a Vertex AI CustomJob that runs this package inside a
reproducible GPU container. Before creating it, explicitly choose a Google Cloud
project, region, GPU type, budget limit, container image and private storage
location. Upload only this fictional training corpus and the legally verified
model snapshot—never Room databases, real calendar exports, API keys or an APK
with credentials.
