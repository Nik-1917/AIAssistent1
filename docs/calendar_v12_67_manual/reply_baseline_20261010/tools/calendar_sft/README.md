# Calendar assistant V12.67

The active protocol has four actions: `chat`, `calendar_add`, `calendar_search`,
`calendar_sum`. The dataset lives in `docs/calendar_sft_v12_67`. It retains
literal V12.66 user messages and assistant JSON under an exact exclusion
register. Historical datasets, runs, source locks and model artifacts remain
preserved. The old tool inventory is available in the
[archived README](../../docs/calendar_training_archive/V12_66_CALENDAR_SFT_README_BEFORE_V12_67.md).

## Current entry points

- [Training contract](../../docs/CALENDAR_ASSISTANT_TRAINING_SPEC.md)
- [Russian rules](../../docs/CALENDAR_ASSISTANT_V12_67_RULES.md)
- [Android behavior](../../docs/CALENDAR_ASSISTANT_ANDROID_MECHANISMS.md)
- [Manual review](../../docs/CALENDAR_ASSISTANT_DATASET_REVIEW.md)
- [Dataset manifest](../../docs/calendar_sft_v12_67/manifest.json)
- [Exclusion register](../../docs/calendar_v12_67_manual/exclusions.json)

From the repository root, use the existing local Python environment:

```powershell
.\build\calendar_sft_qwen35_venv\Scripts\python.exe -X utf8 -B tools/calendar_sft/prepare_v12_67.py --check-only
.\build\calendar_sft_qwen35_venv\Scripts\python.exe -X utf8 -B tools/calendar_sft/test_v12_67.py
.\build\calendar_sft_qwen35_venv\Scripts\python.exe -X utf8 -B tools/calendar_sft/audit_v12_67_tokens.py
.\build\calendar_sft_qwen35_venv\Scripts\python.exe -X utf8 -B tools/calendar_sft/train_v12_67.py --mode preflight
.\build\calendar_sft_qwen35_venv\Scripts\python.exe -X utf8 -B tools/calendar_sft/evaluate_v12_67.py
```

These commands validate, tokenize or prepare inputs. They load no model
weights and do not run training or inference. `evaluate_v12_67.py --cached`
can score later saved answers only when their exact cases, provider, grader,
transport and model hash are bound to the current evaluation pack.
Its default independent pack excludes training and validation.

`render_v12_67_review.py` renders retained literals for review into a fresh
folder. It never writes SFT examples. `prepare_v12_67.py --refresh-metadata`
updates generated metadata only after every JSONL matches its reconstruction.
It cannot rewrite literal conversations.

## Prepared training configuration

`v12_67_training_config.json` stages a separate Qwen3-1.7B run using an existing
pinned local source. This tokenizer is used for the offline capacity audit.
The staged run has its own output folder; existing checkpoints are not reused.
Training supervision covers assistant JSON and the end-of-message token.
Both transports include the current Android four-action contract.

`train_v12_67.py` defaults to preflight. GPU smoke and training are separate
modes and have not been run for V12.67. They still require the approved source,
reviewed provenance, verified data and a measured GPU smoke before a full run.
The GPU power guard reads telemetry and never changes the power limit.
Preparation does not establish model quality or Android inference behavior.

The existing clean-room exclusions and data-rights requirements remain in
[the source policy](../../docs/CALENDAR_ASSISTANT_CLEAN_ROOM.md).
