# Calendar Assistant: training contract

## Scope

This contract covers only the application's local Room calendar. The model has
no database access. It extracts fields supported by the user's wording and
explicit relative-time expressions, plus the mandatory implicit date for
`calendar_add`. Android validates the JSON, applies only user-enabled defaults
to the other omitted fields, decides whether to open a draft or execute a
complete command, resolves targets against the local calendar, and owns every
actual database result.

| Intent | App action |
| --- | --- |
| `chat` | Shows an ordinary reply. |
| `calendar_add` | Supplies the known fields of a new event. |
| `calendar_search` | Supplies known title and period fields for a local search. |
| `calendar_update` | Resolves an existing local event and opens a change preview. |
| `calendar_delete` | Resolves exactly one local event and deletes it immediately. |
| `calendar_sum` | Requests the sum of integer event values in a period. |

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

For a partial creation command, known date and time may be separate:

```json
{"intent":"calendar_add","reply":"...","params":{"title":"...","date":"YYYY-MM-DD","duration_min":60}}
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

```json
{"intent":"calendar_sum","reply":"...","params":{"range_start":"YYYY-MM-DDTHH:MM","range_end":"YYYY-MM-DDTHH:MM"}}
```

Never send `null`, an empty string, `0`, or an invented default as a placeholder
for an unknown calendar field. Omit the unknown field, except for the mandatory
implicit `calendar_add` date defined below. An explicitly supplied `value: 0`
is known data and remains valid. The existing explicit wildcard
`calendar_search.params.query: ""` remains valid when the user asks for all
events; it is not a placeholder for an unknown query.

### chat

- `params` is exactly `{}`.
- Use it for ordinary conversation and operations unsupported by the
  application. A calendar search with an omitted period remains
  `calendar_search` and contains the other known search fields.

### calendar_add

- Allowed parameters are only `title`, `starts_at`, `date`, `time`,
  `duration_min`, and `value`.
- `title` is the complete semantic event name. Keep every event-specific
  action, object, person, place, topic, and qualifier that belongs to the
  event itself. Remove only calendar command words and data represented by
  separate fields: date, time, duration, and value.
- Exact copying is not required. A natural grammatical reformulation or a
  contextually clear event name is valid when it preserves the complete event
  meaning. For example, `Поздравить бабушку` and
  `Поздравление с днём рождения бабушке` are both valid names for the
  recognized event. Do not add details that contradict or redirect the user's
  event.
- `starts_at` is a complete local timestamp in `YYYY-MM-DDTHH:MM`.
- `date` is a resolved date in `YYYY-MM-DD`; `time` is a known time in `HH:MM`.
  `time` may be paired with `date`, but must never appear without a resolved
  date. Prefer `starts_at` when both values are exact.
- Do not combine `starts_at` with `date` or `time` in one command.
- Every `calendar_add` contains either `starts_at` or `date`.
- `duration_min` is a positive integer number of minutes.
- `value` is an integer number of abstract event-value units. It has no
  currency and no fractional form. Omit it when the user did not supply it.
  If the user supplies a fractional value, do not round or truncate it and do
  not emit `value`, because the schema cannot represent that value exactly.
- An explicitly named absolute or relative date always wins. Resolve relative
  wording against the supplied local date-time; never replace an explicit date
  because its event time is in the past.
- If the date is omitted and an exact event time is known, compare that `HH:MM`
  with the supplied current local `HH:MM`. A strictly later event time means
  today. An earlier or equal event time means tomorrow. Emit the resulting
  local `starts_at`.
- If both the date and an exact event time are omitted, emit today's local
  `date`. Keep the unknown time absent; Android treats the command as an
  incomplete draft.
- Do not supply a default duration. When the user did not name a duration,
  omit `duration_min`; Android may apply its user-enabled default.
- Omit every other unknown event field. The model never asks for it.
- For a complete command, use `Событие создано: <название> <дата>, <время>.` in
  `reply`. The text describes the prepared command; the UI controls confirmation
  and the actual local save.
- A partial command uses an individually authored declarative `reply` that
  mentions only known data. It never asks a question. There is no shared
  fallback phrase for partial commands.

### calendar_search

- Allowed parameters are only `query`, `range_start`, and `range_end`.
- `query` is the complete semantic name or description of the requested
  events, or `""` for all events.
- Build `query` from the user's named event wording. Remove command words,
  temporal wording, generic calendar nouns, duration, and value. Keep all
  meaningful event-specific words and relations. Do not reduce a named phrase
  to a generic root: `визиты к подопечным` remains
  `визиты к подопечным`, not `визит`. Natural grammatical normalization is
  valid when it preserves the complete search meaning. A named event class is
  never converted to the all-events wildcard.
- Both boundaries are paired local timestamps in `YYYY-MM-DDTHH:MM` when the
  user supplied a resolvable period. If the period is unknown, omit both and
  let Android apply an enabled default period or keep the command incomplete.
- `range_start` is inclusive; `range_end` is exclusive.
- Через месяц means the same local calendar day one calendar month later;
  for a search, use that whole target day. Через два месяца keeps its
  existing full-month meaning: search the complete calendar month two months
  after the current month. Use в следующем месяце when the user means the
  full next calendar month. For example, from 28 August через месяц searches
  `[28 September 00:00; 29 September 00:00)`, через два месяца searches
  `[1 October 00:00; 1 November 00:00)`, and в следующем месяце searches
  `[1 September 00:00; 1 October 00:00)`.
- If a period cannot be determined exactly, do not invent one and do not ask a
  question. Emit only the search fields known from the request.
- Do not invent search results: Android owns the actual local query result.
- A search reply must not begin with an event-action prefix.

### calendar_update

`params` contains exactly two objects: `target` and `changes`.

```json
{
  "intent":"calendar_update",
  "reply":"Событие изменено: Тренировка перенесено на завтра.",
  "params":{
    "target":{"query":"тренировка"},
    "changes":{"date":"2026-08-25"}
  }
}
```

`target` identifies the existing event. Its allowed fields are:

- `query`: a non-empty complete semantic event name or description. If it
  exists, it takes priority over any fallback flag.
- `range_start` and `range_end`: an optional, paired, local source period in
  `YYYY-MM-DDTHH:MM`. They may be used only with `query` and identify where to
  search for the old event.
- `use_last_created`: `true` only when the user did not name a particular event.
  It means the last event added to the local calendar, not the last chat message
  or last modified event.

When the user names an event, keep its complete semantic description with the
same rules as `calendar_search.query`. A named target always takes priority
over `use_last_created`.

`changes` contains only the replacement fields that the user actually gave:

- `title`: a non-empty new title;
- `date`: a new local date in `YYYY-MM-DD`;
- `time`: a new local time in `HH:MM`;
- `duration_min`: a positive new duration in minutes.
- `value`: a new integer event value without currency or fractional units.
- `clear_value`: `true` only when the user explicitly asks to remove the stored
  value. Do not combine `clear_value: true` with `value`.

Omitted fields preserve their values in the resolved event. Therefore a time
change does not alter the title, date, duration, or value; a date move preserves
the old time, duration, and value. `changes` may be `{}` when the event is known
but the user did not say what to change; Android decides how to represent the
incomplete command. The model does not ask the user for a change.

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
When `changes` is `{}`, use an individually authored declarative reply and do
not begin it with an event-action prefix.

### calendar_delete

`params` contains exactly one `target` object. An executable command identifies
exactly one of:

- `query`: a non-empty complete semantic event name or description, optionally
  constrained by the paired `range_start` and `range_end` local timestamps;
- `use_last_created`: `true` for the last event added to the local calendar.
- `use_last_in_range`: `true` only with paired `range_start` and `range_end`.
  It means the last event in the calendar list for that period, ordered by its
  start time and then event ID. For example, “Удали последнее на сегодня” uses
  today’s 00:00-to-next-day-00:00 period; it does not mean the last event added
  to the database today.

When the user expressed a delete intent but supplied no target, emit
`{"target":{}}`; do not invent a target and do not ask for one. Its individually
authored declarative `reply` must not begin with an event-action prefix. Android
keeps that command incomplete and deletes nothing. Android deletes an event
only when exactly one target mode is present and resolves to exactly one local
event. If it matches none or several events, Android does not delete anything
and replaces the model reply with the actual result. An executable delete reply
begins with `Событие удалено:`.

### calendar_sum

`calendar_sum` prepares a local aggregate query. The model never calculates or
invents the result because it cannot read Room. Allowed parameters are only
`query`, `range_start`, and `range_end`.

- `query` is an optional complete semantic event-title filter governed by the
  `calendar_search.query` rules. Omit it when no filter was named.
- `range_start` is inclusive and `range_end` is exclusive.
- Emit both range boundaries together when the user supplied a resolvable
  explicit or relative period. If the period is unknown, omit both.
- Use the same relative-date, week, month, quarter, half-year, year, rollover,
  and short-month rules as calendar search.
- The future Android client sums stored integer `value` fields. Events without
  `value` do not become model-generated zeroes, and the model does not emit a
  currency field.
- `reply` describes the requested period or filter but never states a numeric
  total and never asks a question.

## Time rules

Every request supplies the current local date-time and IANA time-zone ID.
Resolve relative expressions in that supplied zone. The application then
interprets returned local timestamps in its system zone.

### Gregorian calendar and leap-year boundaries

Resolve every relative date with the Gregorian calendar. A year divisible by
4 is a leap year, except that a year divisible by 100 is not a leap year unless
it is also divisible by 400. February has 29 days in a leap year and 28 days in
all other years. Therefore, 2000 is a leap year and 2100 is not a leap year.

Calendar words advance local calendar dates, not an assumed fixed-length
February. For example:

- from 28 February 2024, `завтра` is 29 February 2024;
- from 28 February 2024, `послезавтра` is 1 March 2024;
- from 29 February 2024, `завтра` is 1 March 2024;
- from 28 February 2023, `завтра` is 1 March 2023.

Apply these boundaries consistently to `date`, `starts_at`, update destination
dates, and search, sum, update-source, or delete-target ranges. A one-day range
that selects 29 February 2024 starts at `2024-02-29T00:00` and ends at
`2024-03-01T00:00`. The relative wording in `reply` must describe the same
calendar date as the technical fields in `params`.

### Month vocabulary and offsets

Use the ordinary calendar month numbering:

| Month | Number |
| --- | ---: |
| январь | 1 |
| февраль | 2 |
| март | 3 |
| апрель | 4 |
| май | 5 |
| июнь | 6 |
| июль | 7 |
| август | 8 |
| сентябрь | 9 |
| октябрь | 10 |
| ноябрь | 11 |
| декабрь | 12 |

- A quarter (`квартал`) is exactly 3 calendar months, not 4 months.
- Four months (`четыре месяца`) is an offset of `+4` calendar months.
- Half a year (`полгода`) is exactly 6 calendar months; через полгода and
  через шесть месяцев both mean an offset of `+6` calendar months.
- A numeric month offset identifies a calendar month by adding `N` to the
  current month number, with normal year rollover. The search range then
  follows the specific expression rule above; do not replace a full-month
  rule with a single-day rule. When a same-day offset is explicitly required,
  preserve the day of month when it exists; if the target month has fewer
  days, use its last day. For example, 31 January plus one month is 28
  February in a non-leap year and 29 February in a leap year.

For `calendar_add`, an explicitly named absolute or relative date has priority.
When the date is omitted, resolve it in the supplied local zone with minute
precision: an exact event time strictly later than the current `HH:MM` means
today, while an earlier or equal time means tomorrow. Without an exact event
time, use today in `date` and omit `time`. This implicit rule applies only to
`calendar_add`; it does not create search, update, delete, or sum periods.

The model must resolve the clock value before it applies this date rule. The
comparison with the supplied current time selects only the event date. It must
never change a resolved morning hour into an evening hour merely to make the
event later than the current time. For example, with a supplied current time of
`14:30`, `в шесть сорок` is first resolved as `06:40` and therefore receives
tomorrow's date, while `в восемнадцать сорок` is `18:40` and receives today's
date. An explicitly named date bypasses this comparison: `послезавтра в шесть
сорок` is always the second next date at `06:40`.

| Russian expression | Search/source range |
| --- | --- |
| `сегодня` | for `calendar_search` and `calendar_sum`: supplied current local time to next date `00:00`; for an update source or delete target: current date `00:00` to next date `00:00` |
| `вчера` | previous date `00:00` to current date `00:00` |
| `позавчера` | second previous date `00:00` to previous date `00:00` |
| `завтра` | next date `00:00` to the following date `00:00` |
| `послезавтра`, `через два дня` | second next date `00:00` to third next date `00:00` |
| `послепослезавтра`, `через три дня` | third next date `00:00` to fourth next date `00:00` |
| `через четыре дня` | fourth next date `00:00` to fifth next date `00:00` |
| `на этой неделе` | for `calendar_search` and `calendar_sum`: supplied current local time to next Monday `00:00`; for an update source or delete target: current Monday `00:00` to next Monday `00:00` |
| `на прошлой неделе` | previous Monday `00:00` to current Monday `00:00` |
| `на следующей неделе` | next Monday `00:00` to the Monday after it `00:00` |
| `в этом месяце` | first day of the current calendar month `00:00` to first day of the next month `00:00` |
| `в предыдущем месяце`, `месяц назад`, `в том месяце` | first day of the previous calendar month `00:00` to first day of the current month `00:00` |
| `в следующем месяце` | first day of the next calendar month `00:00` to first day of the following month `00:00` |
| `через месяц` | the same local day one calendar month later |
| `через два месяца` | the complete calendar month two months after the current month |
| `через квартал` | the same local day three calendar months later |
| `через четыре месяца` | the same local day four calendar months later |
| `через полгода`, `через шесть месяцев` | the same local day six calendar months later |
| `в этом году` | first day of the current year `00:00` to first day of the next year `00:00` |
| `в прошлом году` | first day of the previous year `00:00` to first day of the current year `00:00` |
| `в следующем году` | first day of the next year `00:00` to first day of the following year `00:00` |
| explicit date | that date `00:00` to next date `00:00` |

For a date update, emit the exact destination date only when the wording makes
that date exact. A vague month without a day is a search/source period, not a
license to invent a destination day. The seed data resolves only exact event
times. Day-parts such as “утром” and “после обеда” leave the time unknown; omit
the exact time field.

### Spoken form for 12:00

Treat the spoken user expression `двенадцать ноль ноль` as the exact local time
`12:00`. Encode that time as `12:00` only in the technical JSON parameter. In
`reply`, use a word form such as `двенадцать ноль ноль` or `двенадцать часов
дня`, never digits.

In all supervised conversational text, both user messages and `reply` write
event times in words. The `HH:MM` notation is reserved for the system temporal
context and technical JSON parameters.

## Reply style

- Concise, neutral Russian, without Markdown.
- Do not emit Unicode U+2014, U+00AB, or U+00BB in supervised user text,
  `reply`, or string parameters. Join text separated by U+2014 with exactly one
  ordinary space and remove U+00AB/U+00BB without replacement.
- Never ask the user a question. Do not use `?`, `уточните`, `укажите`, or an
  imperative such as `скажите` to request missing data. A direct how-to answer
  may quote a complete command, but it must not request a value that the model
  failed to extract.
- Never mention a year in `reply`.
- Write known event times in words in `reply`; retain ISO digits only in JSON
  params.

### Exact clock vocabulary

One local day contains exactly 24 clock-hour values numbered from `00` through
`23`. The hour words below are exact clock pronunciations when they occur in a
clock construction such as `в один час` or `в восемнадцать часов`:

| Hour | Exact Russian pronunciation |
| ---: | --- |
| `00` | `ноль часов` |
| `01` | `один час` |
| `02` | `два часа` |
| `03` | `три часа` |
| `04` | `четыре часа` |
| `05` | `пять часов` |
| `06` | `шесть часов` |
| `07` | `семь часов` |
| `08` | `восемь часов` |
| `09` | `девять часов` |
| `10` | `десять часов` |
| `11` | `одиннадцать часов` |
| `12` | `двенадцать часов` |
| `13` | `тринадцать часов` |
| `14` | `четырнадцать часов` |
| `15` | `пятнадцать часов` |
| `16` | `шестнадцать часов` |
| `17` | `семнадцать часов` |
| `18` | `восемнадцать часов` |
| `19` | `девятнадцать часов` |
| `20` | `двадцать часов` |
| `21` | `двадцать один час` |
| `22` | `двадцать два часа` |
| `23` | `двадцать три часа` |

In an exact clock expression, an hour from one through eleven without a named
daypart is interpreted literally as the corresponding morning clock hour. For
example, `в шесть`, `в шесть часов`, and `в шесть сорок` resolve to `06:00`,
`06:00`, and `06:40`. Twelve without a daypart is `12:00`. A number from
thirteen through twenty-three directly identifies its 24-hour value, so
`в восемнадцать часов` is `18:00` and `в двадцать три часа` is `23:00`.

Explicit daypart wording provides an equivalent clock pronunciation. `в
полночь` and `в двенадцать часов ночи` are `00:00`; `в шесть часов утра` is
`06:00`; `в двенадцать часов дня` and `в полдень` are `12:00`; `в час дня` is
`13:00`; `в пять часов дня` and `в пять часов вечера` are both `17:00`; `в
шесть часов вечера` is `18:00`; and `в одиннадцать часов вечера` is `23:00`.
An isolated broad daypart such as `утром`, `вечером`, or `ночью` still does not
supply an exact time.

Resolve a `calendar_add` command in this order:

1. Parse the exact clock words into one fixed `HH:MM` value.
2. Apply an explicit daypart when one accompanies the numeric hour.
3. Apply an explicitly named absolute or relative date when present.
4. Only when the date is absent, compare the fixed `HH:MM` with the supplied
   current `HH:MM`: strictly later means today; earlier or equal means tomorrow.

The fourth step changes only the date. It never changes `06:00` into `18:00` or
the reverse. An explicit `сегодня` also keeps today's date even when the named
clock time is earlier than or equal to the supplied current time.

### Hours, days, durations, and offsets

The preposition and command structure determine whether an hour phrase is a
clock value, a duration, or an offset:

- `в один час` is the exact clock time `01:00`;
- `на один час` is `duration_min: 60`;
- `через один час` is the supplied current local date-time plus 60 minutes;
- `в восемнадцать часов` is the exact clock time `18:00`;
- `на восемнадцать часов` is `duration_min: 1080`;
- `через восемнадцать часов` is the supplied current local date-time plus 18
  hours, including any required date rollover.

One day (`сутки`, `одни сутки`, `двадцать четыре часа`) is 24 hours. Two days
(`двое суток`, `сорок восемь часов`) are 48 hours. Use these exact equivalents:

- `на сутки` and `на двадцать четыре часа` mean `duration_min: 1440`;
- `на двое суток` and `на сорок восемь часов` mean `duration_min: 2880`;
- `через сутки` and `через двадцать четыре часа` mean the supplied current
  local date-time plus 24 hours;
- `через двое суток` and `через сорок восемь часов` mean the supplied current
  local date-time plus 48 hours.

The calendar words `завтра` and `послезавтра` identify the next and second next
local calendar dates. They are not duration fields. The exact clock vocabulary
does not contain `24:00`; midnight at the end of a named date is encoded as
`00:00` on the following date. Never emit `24:00` in a technical JSON field.

An exact clock expression and a relative offset are different operations. For
example, `в три часа` is `03:00`, while `через три часа` is an offset from the
supplied current local time. Never treat them as synonyms.
- `Событие создано:`, `Событие изменено:`, and `Событие удалено:` are reserved
  only for executable add, update, and delete commands respectively. Chat,
  search, sum, and incomplete commands must not use them.
- Prefer `сегодня`, `завтра`, `послезавтра`, and `послепослезавтра` for dates
  from today through the third following day. Understand and vary `через два
  дня`, `через три дня`, and `через четыре дня`.
- Understand and use `в предыдущем месяце`, `месяц назад`, `в том месяце`, `в
  следующем месяце`, `через месяц`, `через два месяца`, `через квартал`,
  `через четыре месяца`, `через полгода`, and `через шесть месяцев` according
  to the time rules above. `в том месяце` always means the previous calendar
  month; never infer it from an earlier user message.

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

- `calendar_assistant_train_seed.jsonl` is the retained supervised seed set.
- `calendar_assistant_eval_seed.jsonl` remains validation-only: never train on
  it or use it as few-shot prompt material.
- `calendar_assistant_manual_train_v5.jsonl` and
  `calendar_assistant_manual_eval_v5.jsonl` contain the manually authored v5
  additions for omitted fields, implicit add dates, integer `value`,
  `clear_value`, and `calendar_sum`.
- `calendar_assistant_manual_train_v6.jsonl` and
  `calendar_assistant_manual_eval_v6.jsonl` contain the manually authored v6
  contrastive additions defined in `CALENDAR_ASSISTANT_V6_MANUAL_AUDIT.md`.
  They reinforce exact versus relative time, omitted-field discipline,
  durations, integer values, aggregate periods, target extraction, and
  command-versus-how-to intent choice. These files are written and reviewed
  line by line. The dataset generator is not used to create them.
- `calendar_assistant_manual_train_v7.jsonl` and
  `calendar_assistant_manual_eval_v7.jsonl` contain manually authored clock,
  day-unit, and Gregorian leap-year boundary additions. Leap-year examples are
  paired with non-leap contrasts and cover add timestamps, add dates, search
  and sum ranges, update destinations, and delete target ranges. Existing
  holdout rows are not copied into these files.
- `calendar_assistant_manual_train_v8.jsonl` and
  `calendar_assistant_manual_eval_v8.jsonl` are the manually authored
  correction layer for complete semantic `title` and `query` values. They do
  not copy H003, H017, or any other holdout prompt. Historical v7 sources stay
  byte-for-byte unchanged so the trained v7 adapter remains reproducible.
- `calendar_assistant_holdout_semantic_acceptance.json` records only explicitly
  reviewed semantic alternatives. It is bound to the exact holdout SHA-256;
  the scorer rejects it if the holdout changes. Exact params remain a separate
  metric, and no unlisted wording difference is accepted automatically.
- The checked-in files under `docs/calendar_assistant_candidates/` retain only
  the previously valid candidate rows. Old rows whose assistant reply requested
  clarification were deleted as complete JSONL records. Some retained
  historical rows use the superseded short-query contract; they remain frozen
  for provenance, while the manual v8 layer supplies the current full-query
  supervision.
- `tools/generate_calendar_training_dataset.py` is updated as a deterministic
  reference implementation of the current contract. It was not run for the v7
  or v8 additions. Do not regenerate the checked-in candidates; a later
  regeneration is a separate, explicitly reviewed dataset change.
- Template expansion alone is not a production-quality dataset. Review every
  retained candidate and every manual row for naturalness and semantic
  correctness before final SFT.
- Every line contains `messages` in system/user/assistant order and a category.
- Before use, validate every assistant JSON string with the production parser,
  the update mapper, and the schema above.
- Do not include real calendar data. Search results are application-owned data
  and are intentionally absent from model examples.

## Update coverage

The retained update candidates cover moves, time changes, duration changes,
renaming, source-period versus destination-date separation, duplicate-title
selection, and last-created fallback. Manually authored v5 rows cover an
incomplete change request without a clarification reply. The sources include
work-life wording for a manicurist, hairdresser, doctor, official, worker,
farmer, athlete, office employee, taxi driver, cleaner, driver, teacher,
student, social worker, and factory worker. The titles are fictional and must
remain so in future expansions.

## SFT pilot package

`tools/calendar_sft/` validates the sources, builds disjoint train/validation/
holdout artifacts, scores model outputs, and records the exact source model
lock. Read `docs/CALENDAR_ASSISTANT_SFT_PILOT.md` before staging a model or
creating a cloud training job.
