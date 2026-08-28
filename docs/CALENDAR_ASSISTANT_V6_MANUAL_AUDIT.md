# Calendar Assistant v6 manual audit

## Scope

This audit is based on the primary v5 artifacts:

- `build/calendar_sft_v5_holdout_20260827/adapter_report.json`
- `build/calendar_sft_v5_holdout_20260827/adapter_predictions.jsonl`
- `build/calendar_sft_dataset_v5/holdout.jsonl`

The narrative file `error_analysis.md` is not used as a case-level source because
some of its manually written descriptions do not match the corresponding
primary case IDs. The aggregate v5 metrics remain unchanged.

No holdout prompt is copied into v6 train or validation data. The v6 additions
use different temporal contexts, titles, wording, and replies.

## Baseline facts

- Train rows: 943.
- Validation rows: 250.
- Holdout rows: 57.
- Manually authored v5 train rows: 35.
- Exact v5 holdout passes: 21 of 57.
- Exact passes in the new v5 holdout block: 2 of 18.
- Existing source JSONL files pass all current contract tests.
- The seven pre-v6 source JSONL files are retained byte-for-byte.

The dominant problem is coverage imbalance. The candidate train source contains
888 rows, while critical v5 rules are commonly represented by one or two rows.
This audit found no reason to delete the retained candidate source as a whole.
The correction is an isolated, manually reviewed v6 addition plus explicit
coverage checks.

## Approved deterministic decisions

1. A spoken hour from one through eleven without a named daypart is interpreted
   literally as a morning clock hour. For example, `в шесть сорок` is `06:40`.
2. `в три часа` is an exact clock expression. `через три часа` is a relative
   offset. They must never be treated as synonyms.
3. A search or target `query` removes command words, temporal wording, and
   generic calendar nouns. Under the current full-query contract it keeps the
   complete semantic event phrase, including meaningful qualifiers and
   relations. This rule supersedes the earlier short-key decision used during
   the historical v6 run.
4. New event titles use sentence case in supervised parameters. Current
   evaluation reports exact params separately and accepts only manually reviewed
   semantic title or query variants bound to the frozen holdout.
5. `value` is a signed integer in abstract units. Zero is valid. A fractional
   value is not rounded or truncated and is omitted because the schema cannot
   represent it exactly.
6. No missing field is represented by `null`, an empty string, zero, or an
   invented default. The only model-owned default is the mandatory implicit
   date for `calendar_add`.
7. The model never asks a question and never requests a missing field.

## Primary v5 failure matrix

| Case | Class | Manual v6 correction |
| --- | --- | --- |
| H001 | Daypart ambiguity | Reinforce literal morning interpretation for daypartless hours one through eleven. |
| H005 | Duration | Contrast one hour twenty minutes with two hours. |
| H008 | Implicit date and reply | Earlier exact time means tomorrow; complete reply keeps the required comma. |
| H010 | Duration | Preserve long durations such as twelve hours as 720 minutes. |
| H011 | Duration | Keep a named twenty-minute duration as 20 minutes. |
| H017 | Full query semantics | Preserve the complete phrase `визиты к подопечным` without temporal or command wording. |
| H018 | Query omission | A named event class is not the all-events wildcard. |
| H019 | Week boundary | Current week ends at the next Monday, not seven days from now. |
| H021 | Previous month | Month ago means the complete previous calendar month. |
| H022 | Query omission | Preserve a named title filter in a full-month search. |
| H024 | Month range and query | Through two months uses the complete target month for search and sum. |
| H029 | Update target | A named target takes priority over `use_last_created`. |
| H031 | Intent | A duration change for a named existing event is `calendar_update`, not `calendar_add`. |
| H033 | Title case | Train sentence-case replacement titles and report title-case-tolerant semantics. |
| H037 | Query scope | Remove generic and temporal words while retaining a useful event key. |
| H038 | Query scope | Do not copy the whole command into `target.query`. |
| H039 | Query specificity | Retain enough of the named event phrase to identify it. |
| H040 | Delete target | Extract the event key without the delete command or source period. |
| H042 | Intent | A how-to question is `chat`; a direct request is executable. |
| H045 | Source day range | Delete and update source ranges for today begin at local midnight. |
| H046 | Omitted fields | Title-only add emits title and today's date, with no time or duration. |
| H047 | Omitted duration | Exact date and time do not license a default duration. |
| H048 | Omitted time | Date and duration do not license an invented time. |
| H049 | Omitted title | Time and duration do not license an invented title. |
| H051 | Value-only add | Emit today's date and integer value, without an invented title. |
| H052 | Value update | Use `changes.value` as an integer; never rename it to priority. |
| H053 | Clear value | Use `clear_value:true`; never emit `value:null`. |
| H055 | Previous week | Use the previous Monday-to-Monday calendar range. |
| H056 | One-month offset | Use the target day with a strictly later exclusive end. |
| H057 | Sum schema | `calendar_sum.params.query` is top-level inside params, never inside target. |
| H058 | Empty changes | A named update without replacement values keeps `changes` empty. |
| H059 | Empty delete target | A delete request without a target keeps `target` empty. |
| H060 | Exact versus relative time | `в три часа дня` is 15:00, not an offset, and no duration is invented. |
| H061 | Equal-time boundary | Equal exact time means tomorrow and no duration is invented. |
| H062 | Earlier-time boundary | Earlier exact time means tomorrow and no duration is invented. |
| H063 | Explicit date precedence | Explicit today wins even when the named time has already passed. |

## Manual v6 quotas

The new train source contains exactly 288 rows:

| Block | Rows |
| --- | ---: |
| Implicit add timing and partial fields | 72 |
| Spoken duration and daypart rules | 40 |
| Integer value and clear-value rules | 36 |
| Calendar sum periods and filters | 48 |
| Search, update, and delete target extraction | 36 |
| No-invention incomplete commands | 24 |
| Chat versus executable command contrasts | 20 |
| Reply and schema reinforcement | 12 |

The new validation source contains exactly 72 rows with distinct temporal
contexts, titles, wording, and system-user signatures. It is never included in
training and is used before the frozen holdout.

## Acceptance gates

- All source rows pass the strict dataset contract.
- Full system+user inputs are disjoint across train, validation, and holdout.
- Normalized user wording from holdout is excluded from staged train and
  validation even when the temporal system context differs.
- The manually authored v6 train and validation user prompts are mutually
  disjoint.
- No forbidden punctuation, clarification request, `null`, unknown field, or
  invalid range is present.
- Every v6 quota is checked independently of the dataset generator.
- The dataset generator is not run.
- One training run is permitted only after provenance binds the exact staged
  train and validation hashes.
- The frozen holdout is evaluated once after validation scoring.
