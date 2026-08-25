# Calendar Assistant: training contract

## Scope

This contract covers only the application's local Room calendar. The model has
no database access. Android validates its JSON, resolves the target against the
local calendar, and writes to Room only after the user confirms an add or
update draft. A uniquely resolved delete command is executed immediately.

| Intent | App action |
| --- | --- |
| `chat` | Shows an ordinary reply. |
| `calendar_add` | Opens an event draft and requests unknown event fields. |
| `calendar_search` | Searches local event titles in an explicit period. |
| `calendar_update` | Resolves an existing local event and opens a change preview. |
| `calendar_delete` | Resolves exactly one local event and deletes it immediately. |

Remote calendars, reminders, and `use_last_referenced` are not executable
model operations in this release. Do not emit `use_last_referenced` in training
data until the application has persistent last-referenced-event state.

## Exact response schema

Every assistant response is exactly one valid JSON object. No Markdown, code
fences, leading commentary, or trailing text is allowed. All top-level keys are
required; `params` is always an object; no additional top-level keys are
allowed.

```json
{"intent":"chat","reply":"...","params":{}}
```

```json
{"intent":"calendar_add","reply":"...","params":{"title":"...","starts_at":"YYYY-MM-DDTHH:MM","duration_min":60}}
```

```json
{"intent":"calendar_search","reply":"...","params":{"query":"...","range_start":"YYYY-MM-DDTHH:MM","range_end":"YYYY-MM-DDTHH:MM"}}
```

```json
{"intent":"calendar_update","reply":"...","params":{"target":{"query":"..."},"changes":{"date":"YYYY-MM-DD"}}}
```

```json
{"intent":"calendar_delete","reply":"...","params":{"target":{"query":"..."}}}
```

Never send `null`, an empty string, `0`, or an invented default for an unknown
calendar field. Omit the unknown field instead.

### chat

- `params` is exactly `{}`.
- Use it for ordinary conversation, an unclear search period, and operations
  unsupported by the application.

### calendar_add

- Allowed parameters are only `title`, `starts_at`, and `duration_min`.
- `starts_at` is a local timestamp in `YYYY-MM-DDTHH:MM`.
- `duration_min` is a positive integer number of minutes.
- Omit any unknown event field. Android asks for it in the draft dialog.
- For a complete command, use `Событие создано: <название> <дата> <время>.` in
  `reply`. The text describes the prepared command; the UI controls confirmation
  and the actual local save.
- A partial command must ask for the missing field and must not begin with an
  event-action prefix.

### calendar_search

- Allowed parameters are only `query`, `range_start`, and `range_end`.
- `query` is a short title keyword/name, or `""` for all events.
- Both boundaries are required local timestamps in `YYYY-MM-DDTHH:MM`.
- `range_start` is inclusive; `range_end` is exclusive.
- If a period cannot be determined exactly, use `chat` and ask for the period.
- Do not invent search results: Android owns the actual local query result.
- A search reply must not begin with an event-action prefix.

### calendar_update

`params` contains exactly two objects: `target` and `changes`.

```json
{
  "intent":"calendar_update",
  "reply":"Событие изменено: «Тренировка» перенесено на завтра.",
  "params":{
    "target":{"query":"тренировка"},
    "changes":{"date":"2026-08-25"}
  }
}
```

`target` identifies the existing event. Its allowed fields are:

- `query`: a non-empty title word or name. If it exists, it takes priority over
  any fallback flag.
- `range_start` and `range_end`: an optional, paired, local source period in
  `YYYY-MM-DDTHH:MM`. They may be used only with `query` and identify where to
  search for the old event.
- `use_last_created`: `true` only when the user did not name a particular event.
  It means the last event added to the local calendar, not the last chat message
  or last modified event.

`changes` contains only the replacement fields that the user actually gave:

- `title`: a non-empty new title;
- `date`: a new local date in `YYYY-MM-DD`;
- `time`: a new local time in `HH:MM`;
- `duration_min`: a positive new duration in minutes.

Omitted fields preserve their values in the resolved event. Therefore a time
change does not alter the title, date, or duration; a date move preserves the
old time and duration. `changes` may be `{}` when the event is known but the
user did not say what to change; Android then asks the user to select a field.

The source period and destination date have different roles. For example,
“Завтрашнюю тренировку перенеси на пятницу” must keep 25 August in
`target.range_start`/`target.range_end` and put 28 August only in
`changes.date`. Never use the destination date to search for the old event.

If a query matches several local events, Android presents a selection instead of
silently choosing one. If it matches none, Android reports that the event was
not found. The model must not fabricate an event ID or claim a database result.

For an executable update command, use a concise reply beginning with
`Событие изменено:`. It describes the prepared update command; the UI still
shows its preview and controls the actual Room update.
When `changes` is `{}`, ask what to change and do not begin the reply with an
event-action prefix.

### calendar_delete

`params` contains exactly one `target` object and must identify exactly one of:

- `query`: a non-empty event title word or name, optionally constrained by the
  paired `range_start` and `range_end` local timestamps;
- `use_last_created`: `true` for the last event added to the local calendar.
- `use_last_in_range`: `true` only with paired `range_start` and `range_end`.
  It means the last event in the calendar list for that period, ordered by its
  start time and then event ID. For example, “Удали последнее на сегодня” uses
  today’s 00:00-to-next-day-00:00 period; it does not mean the last event added
  to the database today.

The model must send exactly one of `query`, `use_last_created`, or
`use_last_in_range`; it must not send an empty target. Android deletes the event
immediately when this target resolves to exactly one local event. If it matches
none or several events, Android does not delete anything and replaces the model
reply with the actual result. An executable delete reply begins with `Событие
удалено:`.

## Time rules

Every request supplies the current local date-time and IANA time-zone ID.
Resolve relative expressions in that supplied zone. The application then
interprets returned local timestamps in its system zone.

| Russian expression | Search/source range |
| --- | --- |
| `сегодня` | current date `00:00` to next date `00:00` |
| `завтра` | next date `00:00` to the following date `00:00` |
| `послезавтра`, `через два дня` | second next date `00:00` to third next date `00:00` |
| `послепослезавтра`, `через три дня` | third next date `00:00` to fourth next date `00:00` |
| `через четыре дня` | fourth next date `00:00` to fifth next date `00:00` |
| `на этой неделе` | Monday `00:00` to next Monday `00:00` |
| `в предыдущем месяце`, `месяц назад`, `в том месяце` | first day of the previous calendar month `00:00` to first day of the current month `00:00` |
| `в следующем месяце` | first day of the next calendar month `00:00` to first day of the following month `00:00` |
| `через месяц` | the same local day one calendar month later |
| `через два месяца` | the same local day two calendar months later |
| explicit date | that date `00:00` to next date `00:00` |

For a date update, emit the exact destination date only when the wording makes
that date exact. A vague month without a day is a search/source period, not a
license to invent a destination day. The seed data resolves only exact event
times. Day-parts such as “утром” and “после обеда” leave the time unknown and
ask for a precise value.

## Reply style

- Concise, neutral Russian, without Markdown.
- Never mention a year in `reply`.
- Write known event times in words in `reply`; retain ISO digits only in JSON
  params.
- `Событие создано:`, `Событие изменено:`, and `Событие удалено:` are reserved
  only for executable add, update, and delete commands respectively. Chat,
  search, and incomplete commands must not use them.
- Prefer `сегодня`, `завтра`, `послезавтра`, and `послепослезавтра` for dates
  from today through the third following day. Understand and vary `через два
  дня`, `через три дня`, and `через четыре дня`.
- Understand and use `в предыдущем месяце`, `месяц назад`, `в том месяце`, `в
  следующем месяце`, `через месяц`, and `через два месяца` according to the
  time rules above. `в том месяце` always means the previous calendar month;
  never infer it from an earlier user message.

## Runtime system prompt

The runtime prompt stays intentionally small. The application substitutes the
current values on every request:

```text
Сегодня дата и время:{{CURRENT_LOCAL_DATETIME}} {{TIME_ZONE}} ответ JSON
```

It carries temporal context only. The JSON shape, intent choice, field omission
rules, reply style, and unsupported-operation behavior are learned from the
supervised data. The train/evaluation examples must retain this minimal
system-message form so the model does not rely on a large prompt absent in
production.

The string has no full stop after the local time and no `Часовой пояс:` label.
It must match `SystemPromptProvider` byte-for-byte apart from the substituted
date, time, weekday, and IANA zone. A prompt must not contain an extra empty
system message before this one.

## Dataset process

- `calendar_assistant_train_seed.jsonl` is a reviewed supervised seed set.
- `calendar_assistant_eval_seed.jsonl` is held out: never train on it or use it
  as few-shot prompt material.
- `tools/generate_calendar_training_dataset.py` deterministically creates 1,200
  training candidates and 300 separate evaluation candidates in
  `docs/calendar_assistant_candidates/`. Run it with
  `python tools/generate_calendar_training_dataset.py`.
- The generated corpus mixes add, search, chat, delete refusal, and update
  examples. It structurally validates the nested update schema and forbids
  `null` update fields. Review naturalness and semantic correctness before final
  SFT; template expansion alone is not a production-quality dataset.
- Every line contains `messages` in system/user/assistant order and a category.
- Before use, validate every assistant JSON string with the production parser,
  the update mapper, and the schema above.
- Do not include real calendar data. Search results are application-owned data
  and are intentionally absent from model examples.

## Update coverage

The generated update candidates cover moves, time changes, duration changes,
renaming, incomplete change requests, source-period versus destination-date
separation, duplicate-title selection, and last-created fallback. They include
work-life wording for a manicurist, hairdresser, doctor, official, worker,
farmer, athlete, office employee, taxi driver, cleaner, driver, teacher,
student, social worker, and factory worker. The titles are fictional and must
remain so in future expansions.

## SFT pilot package

`tools/calendar_sft/` validates the sources, builds disjoint train/validation/
holdout artifacts, scores model outputs, and records the exact source model
lock. Read `docs/CALENDAR_ASSISTANT_SFT_PILOT.md` before staging a model or
creating a cloud training job.
