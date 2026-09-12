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

The archived reviewed register is
[`docs/calendar_sft_data_provenance_v4.json`](../../docs/calendar_sft_data_provenance_v4.json).
It matches only the earlier v4 artifact hashes. It does not approve the current
v5 sources or any newly prepared artifacts; v5 needs a separate reviewed
register with exact new train/validation hashes before training.

Before any dry run, use `verify_source_snapshot.py` to calculate and persist
SHA-256 for every locked source file. It verifies the source revision contract,
Qwen3 architecture, tokenizer chat template and Apache-2.0 licence text
without loading model weights. The source lock also records the official
Hugging Face LFS SHA-256 values for all three weight shards and `tokenizer.json`,
plus byte sizes for every required file; verification fails on any mismatch.

## Local, no-cost preparation

### Current V12.55 half-hour input coverage

`prepare_v12_55.py` appends 144 complete handwritten training conversations
and 24 independent validation conversations to the frozen V12.54 bytes.
Training covers all 24 resolved half-hour times and six input forms:
`полтретьего`, `в полтретьего`, `пол третьего`, `в пол третьего`,
`половина третьего`, and `в половине третьего`, generalized across all twelve
named hours. Each form and each event/day/time order has 24 train examples;
each form/order combination has four.

The separate `half_hour_holdout.jsonl` contains 24 new clock cases and 12
ambiguity, duration and offset controls. It is excluded from fitting data
and must be explicitly included in a later model evaluation. All original
3228 rows, including the three existing holdouts, remain byte-identical.
Current reply rules and the runtime prompt are unchanged. Preparation does
not train weights or demonstrate learned behavior.

```powershell
python -X utf8 -B tools/calendar_sft/prepare_v12_55.py --check-only
```

See [V12.55 manual data and verification](../../docs/CALENDAR_ASSISTANT_V12_55_HALF_HOUR.md).

### Archived V12.54 minutes-to reply wording

`prepare_v12_54.py` prepares a separate copy of V12.53, replacing only 180
complete replies from a handwritten register: 144 train and 36 validation.
For clock minutes 35, 40, 50 and 55, replies use `без двадцати пяти минут`,
`без двадцати минут`, `без десяти минут` and `без пяти минут` followed by
the upcoming cardinal hour. Existing quarter, half and quarter-to forms stay
unchanged. Each new form has 36 training examples; the inherited validation
grid contributes 36 changed replies at minute 55. No examples change splits.

Inputs, parameters, IDs, categories and all three holdout files are preserved.
The assembler validates the full literal replacement against the source and
exact numerical clock; it does not generate or replace sentence fragments.
Unchanged rows are copied byte for byte. V12.53 remains reproducible.

```powershell
python -X utf8 -B tools/calendar_sft/prepare_v12_54.py --check-only
```

See [V12.54 reply rules and preparation](../../docs/CALENDAR_ASSISTANT_V12_54_REPLY_MINUTES_TO.md).
Preparation does not update model weights or run inference.

### Archived V12.53 compact clocks and reply wording

`prepare_v12_53.py` prepares 265 complete handwritten train examples and
73 independent validation examples. The main grid covers 24 hours and every
five-minute value from 5 to 55; validation covers 5, 30 and 55. Each split has
one additional standalone `ноль часов` example. The six event/day/time orders
have 44 train and 12 validation examples each within the main grid.

The source files and numerical extraction remain unchanged. A separate manual
register supplies complete replacement replies for 260 inherited train rows,
74 validation rows and three regression rows. They omit clock dayparts while
retaining words for dates, durations and titles. Holdout prompts and parameters
remain unchanged. The final counts are 2401 train, 720 validation and
36 + 8 + 63 holdout rows; no removed intent is reintroduced.

```powershell
python -X utf8 -B tools/calendar_sft/prepare_v12_53.py --check-only
```

See [V12.53 rules, examples and verification](../../docs/CALENDAR_ASSISTANT_V12_53_COMPACT_CLOCK.md).
The assembler only validates and serializes literal messages or copies a
complete handwritten replacement reply; it does not generate sentences.
Preparation does not train model weights or run inference.

### Archived V12.52 minute-of-hour addition

`prepare_v12_52.py` appends 132 complete handwritten train examples and 36
independent validation examples to the exact V12.51 files. Training covers
every named hour from first to twelfth and every five-minute value from 5 to
55. Validation checks 5, 30 and 55 for each named hour in the opposite half
of the day. Both splits balance times before and after noon.

The resulting dataset contains 2136 train and 647 validation rows. All
inherited rows and three holdouts retain their exact bytes; new records keep
the `v12.5` JSON contract and apply presentation rules only to `reply`.

```powershell
python -B tools/calendar_sft/prepare_v12_52.py --check-only
```

See [V12.52 rules and complete examples](../../docs/CALENDAR_ASSISTANT_V12_52_MINUTES_OF_HOUR.md).
The assembler validates literal input and output messages without generating
sentences or repairing targets. Preparation does not train weights or run
model inference.

### V12.51 whole-hour addition

`prepare_v12_51.py` appends 48 complete handwritten train examples and 24
independent validation examples to the exact V12.5 files. Each clock hour
`00` through `23` has two train forms and one validation form, including
`час ноль ноль`, `двадцать ноль ноль`, and `ноль часов ноль ноль минут`.
The inherited JSON contract is still `v12.5`; reply rules apply only to `reply`.
The V12.5 data and all three holdouts remain byte-for-byte identical.

```powershell
python -B tools/calendar_sft/prepare_v12_51.py --check-only
```

See [V12.51 rules and complete examples](../../docs/CALENDAR_ASSISTANT_V12_51_ON_HOUR.md).
The validator reads literal assertions; it does not generate training language
or repair answers. Preparation does not start training or overwrite a GGUF.

### V12.5 relative-clock addition

`prepare_v12_5.py` appends twelve fully handwritten examples to the exact V12.4
artifacts. Each of `четверть`, `пол...` and `половина...` receives two train and
two validation rows, matching the count of `без четверти` requests in the V14
manual source files. V14 supplies only the count: no V14 data are imported.
The three V12.4 holdouts remain unchanged.

The new `v12.5` contract applies reply presentation rules to **`reply` only**.
It accepts literal titles and user input independently of reply style, retains
numeric JSON parameters, and permits known spoken time in creation replies.
Inherited `v12.1` rows keep their historical validation.

```powershell
python -B tools/calendar_sft/prepare_v12_5.py --check-only
```

See [the current rules and complete examples](../../docs/CALENDAR_ASSISTANT_V12_5_RELATIVE_CLOCK.md).
Preparation does not start training or overwrite a GGUF file.

### Historical V14 release

V14 permits exactly `chat`, `note_add`, `calendar_add`, `calendar_search`, and
`calendar_sum`. `prepare_dataset.py` excludes generated candidate files and
filters every historical row that contains a removed event-mutation intent.
The 300-row train layer, 90-row validation layer, and 60-row sealed holdout are
manually authored. `value` and `duration_min` are accepted only in
`calendar_add` when the user explicitly supplies them. The application, not the
model, calculates a requested sum.

Do not run `tools/generate_calendar_training_dataset.py`. Validate and stage
the checked-in manual data directly:

```powershell
python -m unittest discover -s tools/calendar_sft -p "test_*.py"
python tools/calendar_sft/prepare_dataset.py --check-only
python tools/calendar_sft/prepare_dataset.py `
  --output-dir build/calendar_sft_dataset_v14 `
  --overwrite
```

The V14 holdout uses stable identifiers `V14H001` through `V14H060` and is
never used for fitting or model selection. A V14 adapter must start from the
locked clean base checkpoint, not from an earlier adapter that learned removed
intents.

### Historical layers

The reviewed source history is retained in the manually authored v5 and v6
JSONL files. The v7 clock and Gregorian calendar additions are manually
authored in `docs/calendar_assistant_manual_train_v7.jsonl` and
`docs/calendar_assistant_manual_eval_v7.jsonl`. They teach the fixed `00`
through `23` clock vocabulary, daypart equivalents, explicit-date priority,
implicit today-or-tomorrow selection, whole-hour durations, and 24-hour or
48-hour day offsets. They also contain balanced leap and non-leap boundary
examples for add, search, sum, update, and delete operations. The retained
candidate and holdout files are unchanged. The generator source is synchronized
with the same rules but must not be run for v7.

The manually authored v8 correction layer is stored in
`docs/calendar_assistant_manual_train_v8.jsonl` and
`docs/calendar_assistant_manual_eval_v8.jsonl`. It teaches complete semantic
event names for `calendar_add.title` and every named `query` or target field.
Natural title reformulation is allowed when the full event meaning is retained;
named search targets keep meaningful qualifiers instead of being reduced to a
single generic word. The v7 files and frozen holdout remain byte-for-byte
unchanged. The synchronized generator source must not be run for v8.

V8 has not been staged, approved by a new artifact-bound provenance register,
or used for training. `prepare_dataset.py --check-only` validates its checked-in
sources without creating train, validation, or holdout artifacts.

From the repository root, validate all current sources without writing
artifacts:

```powershell
python -B tools/calendar_sft/test_dataset_contract.py
python -B tools/calendar_sft/test_search_periods.py
python -B tools/calendar_sft/test_manual_v6_dataset.py
python -B tools/calendar_sft/test_manual_v7_dataset.py
python -B tools/calendar_sft/test_manual_v8_dataset.py
python -B tools/calendar_sft/prepare_dataset.py --check-only
```

Create the ignored artifacts only after reviewing the source rows:

```powershell
python tools/calendar_sft/prepare_dataset.py
```

The result is `build/calendar_sft_dataset/` with three disjoint files:

- `train.jsonl` is the only SFT input.
- `validation.jsonl` is used during training selection.
- `holdout.jsonl` is never used to tune a model. V14 contains independently
  authored add, search, sum, mutation-refusal, identity, and note cases.

Every row is normalised to the exact Android temporal system prompt:

```text
Сегодня дата и время:<DATE> (<WEEKDAY>) <TIME> <IANA_ZONE> ответ JSON
```

The v5 contract adds partial-field extraction without clarification questions,
integer `value`, explicit `clear_value: true`, and the separate `calendar_sum`
intent. Every `calendar_add` also carries a model-resolved date: a missing date
uses today for a later exact time, tomorrow for an earlier or equal exact time,
and today when no exact time is known. Other defaults and actual Room query
results remain Android-owned and are not part of SFT responses.

The historical contracts excluded Unicode U+2014, U+00AB and U+00BB in user
text, replies and string parameters. In the current `v12.5` contract, these
presentation restrictions apply only to `reply`.

## Staged model dry run

The literal dataset paths in this section are an archived v4 command example.
Do not run it for the current v5 sources. First create separately reviewed v5
artifacts; their paths must replace both v4 paths below.

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

The smoke-test command below is also an archived v4 example. Its v4 provenance
register does not authorize a smoke test or training run with current v5 data.

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

For v5, the sequence below is procedural reference only. Do not use any literal
v4 register or dataset path; substitute them only after the new v5 artifacts
have been manually reviewed, hashed, and bound to a `VERIFIED` register.

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
   compares `intent` and `params`, applies the manually reviewed aliases in
   `docs/calendar_assistant_holdout_semantic_acceptance.json`, and separately
   reports exact parameter differences. Reply wording is schema-checked but is
   not compared with one fixed sentence.
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
snapshot never Room databases, real calendar exports, API keys or an APK with
credentials.
