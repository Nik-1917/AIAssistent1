# Calendar Assistant: training contract

## Scope

This contract covers the application's local Room calendar. The model has no
direct database access: Android validates its JSON, searches the local database,
and saves an event only after explicit user confirmation.

The first release supports exactly these intents:

| Intent | App action |
| --- | --- |
| `chat` | Shows a normal reply. |
| `calendar_add` | Opens an event draft; the app collects missing fields. |
| `calendar_search` | Searches local titles in a requested period. |

`calendar_delete`, `calendar_update`, remote calendars, and reminders are not
model operations in this release. Such requests use `chat` and must not claim
success.

## Exact response schema

Every assistant response is exactly one valid JSON object. No Markdown, code
fences, leading commentary, or trailing text is allowed.

```json
{"intent":"chat","reply":"...","params":{}}
```

```json
{"intent":"calendar_add","reply":"...","params":{"title":"...","starts_at":"YYYY-MM-DDTHH:MM","duration_min":60}}
```

```json
{"intent":"calendar_search","reply":"...","params":{"query":"...","range_start":"YYYY-MM-DDTHH:MM","range_end":"YYYY-MM-DDTHH:MM"}}
```

All top-level keys are required. `params` is always an object. No additional
top-level keys are permitted.

### chat

- `params` is exactly `{}`.
- Use it for ordinary conversation, unclear search periods, and unsupported
  operations.

### calendar_add

- Allowed parameters are only `title`, `starts_at`, and `duration_min`.
- `starts_at` is a local timestamp in `YYYY-MM-DDTHH:MM`.
- `duration_min` is a positive integer number of minutes.
- Omit a field if it is unknown; never use `null`, `0`, an empty string, or an
  invented default. Android asks the user for omitted fields.
- A complete event is awaiting confirmation; it is not saved yet.

### calendar_search

- Allowed parameters are only `query`, `range_start`, and `range_end`.
- `query` is a short title keyword/name, or `""` for all events.
- Both boundaries are required local timestamps in `YYYY-MM-DDTHH:MM`.
- `range_start` is inclusive; `range_end` is exclusive.
- If a period cannot be determined exactly, use `chat` and ask for the period.

## Time rules

Every request must supply the current local date-time and IANA time-zone ID.
Resolve all relative expressions in that supplied zone. The app subsequently
interprets returned local timestamps using its system zone.

| Expression | Search range |
| --- | --- |
| today | current date `00:00` to next date `00:00` |
| tomorrow | next date `00:00` to the following date `00:00` |
| day after tomorrow | second next date `00:00` to third next date `00:00` |
| third day from today | third next date `00:00` to fourth next date `00:00` |
| this week | Monday `00:00` to next Monday `00:00` |
| explicit date | that date `00:00` to next date `00:00` |

The seed dataset resolves only exact event times. Day-parts such as “in the
morning” and “after lunch” leave `starts_at` absent and request a precise time;
this prevents fabricated events.

## Reply style

- Concise, neutral Russian; no Markdown.
- Do not mention a year in `reply`.
- State known event times in words in `reply`, but retain ISO digits in params.
- For creation, state that confirmation is awaited.
- For search, state that the local search is being performed; never invent search
  results.

## Runtime system prompt

The runtime prompt is intentionally minimal. The app must substitute
`{{CURRENT_LOCAL_DATETIME}}` and `{{TIME_ZONE}}` on every request:

```text
Сегодня дата и время: {{CURRENT_LOCAL_DATETIME}} {{TIME_ZONE}}
```

This prompt carries temporal context only. JSON shape, allowed intents, missing
field handling, reply style, and unsupported-operation behavior are learned
from the supervised dataset. Keeping the train/evaluation examples in this
minimal system-message form is required; otherwise the model could depend on a
large instruction that is absent in production.

This documentation change does not wire the prompt into Android code.

## Dataset process

- `calendar_assistant_train_seed.jsonl` is a supervised seed set.
- `calendar_assistant_eval_seed.jsonl` is held out: never train on it or use it
  as few-shot prompt material.
- Every line contains `messages` in system/user/assistant order and a category.
- Before use, validate every assistant string with the production parser and the
  schema above.
- Expand the seed through manual review to 1,200–1,800 training examples and
  250–400 held-out examples. Vary wording, names, dates, word order, and
  ASR-like errors without changing semantics.
- Do not include real calendar data. Search results are application-owned data
  and are intentionally absent from model examples.

## Expansion coverage

1. Full and partial creation, including every missing-field combination.
2. Relative dates, weekdays, explicit dates, weeks, and date ranges.
3. Search by all events, title keyword, and person name.
4. Ambiguous queries that must ask for clarification.
5. Ordinary chat and unsupported deletion/update requests.
6. Conversational and speech-recognition variants.
7. Month/year boundaries, leap day, Monday/Sunday edges, and supplied-zone DST
   boundaries where applicable.
