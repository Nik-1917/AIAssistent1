# Calendar SFT pilot tools

These scripts prepare and score a QLoRA pilot for the application's local
calendar assistant. They do not create a Google Cloud project, bucket, service
account, GPU VM, or training job.

## Source locks

`model_manifest.json` is an archived audit lock for the supplied
RefalMachine model. Its status is `NOT_CLEARED`; it must not be used for
training, merging, conversion, redistribution or a production release.

`clean_room_qwen3_source_lock.json` pins the independent candidate base
`Qwen/Qwen3-4B-Instruct-2507` to an official Apache-2.0 source revision. It
expects the original BF16 Safetensors snapshot and its tokenizer, not an
Android GGUF file. The current app imports GGUF only, so an adapter cannot be
deployed directly. Qwen3 GGUF compatibility with the current Android runtime is
not yet validated.

The source lock verifies only the base-checkpoint licence. Every SFT data
source must separately be recorded and approved from
`dataset_provenance.template.json`; user data, the blocked RefalMachine source
and its outputs are prohibited inputs. See
[`docs/CALENDAR_ASSISTANT_CLEAN_ROOM.md`](../../docs/CALENDAR_ASSISTANT_CLEAN_ROOM.md).

`verify_dataset_provenance.py` accepts only a `VERIFIED` register. It requires
rights evidence for each data source, an explicit no-personal-data declaration,
rights for training and derivative-weight distribution, a recorded reviewer
decision, and SHA-256 values matching the precise train/validation JSONL files.
`train_qlora.py` requires this register for every non-dry-run invocation.

The reviewed register for the current clean-room pilot is
[`docs/calendar_sft_data_provenance_v4.json`](../../docs/calendar_sft_data_provenance_v4.json).

Before any dry run, use `verify_source_snapshot.py` to calculate and persist
SHA-256 for every locked source file. It verifies the source revision contract,
Qwen3 architecture, tokenizer chat template and Apache-2.0 licence text
without loading model weights. The source lock also records the official
Hugging Face LFS SHA-256 values for all three weight shards and `tokenizer.json`,
plus byte sizes for every required file; verification fails on any mismatch.

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

After the clean-room source is explicitly approved and lawfully staged locally
in a directory containing the locked Safetensors snapshot and a CUDA-compatible
Python environment is ready:

```powershell
python tools/calendar_sft/train_qlora.py `
  --model-manifest tools\calendar_sft\clean_room_qwen3_source_lock.json `
  --model-dir D:\models\Qwen3-4B-Instruct-2507 `
  --train-file build\calendar_sft_dataset_v4\train.jsonl `
  --validation-file build\calendar_sft_dataset_v4\validation.jsonl `
  --output-dir build\calendar_sft_run `
  --dry-run
```

Install `requirements-dry-run-qwen3.txt` in an isolated CPU environment first.
The dry run uses the model's own `chat_template`, tokenizes every example,
constructs the QLoRA configuration and rejects silent truncation. It does not
load model weights, start epochs or require a GPU.

## Local GTX 1080 Ti pilot

The local path is free: it uses the installed NVIDIA driver, not Google Cloud.
The GTX 1080 Ti is Pascal and the training script selects native FP16 for its
Compute Capability 6.1; it does not accept emulated BF16. Install the
CUDA 11.8 PyTorch wheel before the pinned requirements because the current
Windows bitsandbytes CUDA 11.8-12.6 binary includes the Pascal `sm60` target:

```powershell
python -m venv build\calendar_sft_local_gpu_venv
build\calendar_sft_local_gpu_venv\Scripts\python.exe -m pip install --upgrade pip
build\calendar_sft_local_gpu_venv\Scripts\python.exe -m pip install torch==2.7.1+cu118 --index-url https://download.pytorch.org/whl/cu118
build\calendar_sft_local_gpu_venv\Scripts\python.exe -m pip install -r tools\calendar_sft\requirements-local-windows-gtx1080ti.txt
```

Perform a real 4-bit load and one no-gradient forward pass before training:

```powershell
build\calendar_sft_local_gpu_venv\Scripts\python.exe tools\calendar_sft\train_qlora.py `
  --model-manifest tools\calendar_sft\clean_room_qwen3_source_lock.json `
  --model-dir build\calendar_sft_models\Qwen3-4B-Instruct-2507\cdbee75f17c01a7cc42f958dc650907174af0554 `
  --train-file build\calendar_sft_dataset_v4\train.jsonl `
  --validation-file build\calendar_sft_dataset_v4\validation.jsonl `
  --dataset-provenance docs\calendar_sft_data_provenance_v4.json `
  --output-dir build\calendar_sft_local_pilot `
  --max-seq-length 256 --smoke-test
```

Only after that passes, use the same command without `--smoke-test` and with
`--epochs 0.25` for the bounded first pilot. This is about 20 optimizer steps
with batch size 1 and gradient accumulation 16.

## Real training and release

Only after both the source lock and a complete data-provenance register are
approved:

1. Verify the provenance register against the staged artifacts:

```powershell
python tools/calendar_sft/verify_dataset_provenance.py `
  --register docs\calendar_sft_data_provenance_v4.json `
  --train-file build\calendar_sft_dataset_v4\train.jsonl `
  --validation-file build\calendar_sft_dataset_v4\validation.jsonl
```

2. Run `train_qlora.py --dataset-provenance docs\calendar_sft_data_provenance_v4.json`
   on one CUDA GPU and retain its `run_manifest.json`. The training preflight
   re-hashes the full source snapshot and repeats the provenance check.
3. Score generated outputs with `evaluate_predictions.py`; semantic scoring
   compares only `intent` and `params`, while reply wording is separately
   schema-checked.
4. Merge the chosen adapter with `merge_adapter.py`.
5. Convert the merged Safetensors checkpoint using a separately pinned GGUF
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
location. Upload only a provenance-approved training corpus and the locked model
snapshot—never Room databases, real calendar exports, API keys or an APK with
credentials.
