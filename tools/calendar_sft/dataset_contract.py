"""Shared validation for calendar-assistant supervised data and model output.

The module deliberately has no third-party dependencies. It validates the
training contract before a record can reach an SFT job or a score.
"""

from __future__ import annotations

from collections import Counter
from datetime import datetime
from hashlib import sha256
import json
from pathlib import Path
import re
from typing import Any, Iterable


INTENTS = frozenset(
    {
        "chat",
        "calendar_add",
        "calendar_search",
        "calendar_update",
        "calendar_delete",
        "calendar_sum",
        "note_add",
    },
)
V14_INTENTS = frozenset({"chat", "note_add", "calendar_add", "calendar_search", "calendar_sum"})
V14_FORBIDDEN_INTENTS = frozenset({"calendar_delete", "calendar_update"})
CALENDAR_INTENTS = frozenset(
    {"calendar_add", "calendar_search", "calendar_update", "calendar_delete", "calendar_sum"},
)
TOP_LEVEL_KEYS = frozenset({"intent", "reply", "params"})
RUNTIME_SYSTEM_RE = re.compile(
    r"^Сегодня дата и время:(?P<date>\d{4}-\d{2}-\d{2}) "
    r"\((?P<weekday>[^()\r\n]+)\) (?P<time>\d{2}:\d{2}) "
    r"(?P<zone>[A-Za-z_+\-/]+) ответ JSON$",
)
LEGACY_SYSTEM_RE = re.compile(
    r"^Сегодня дата и время:\s*(?P<date>\d{4}-\d{2}-\d{2})\s+"
    r"\((?P<weekday>[^()\r\n]+)\)\s+(?P<time>\d{2}:\d{2})\.\s+"
    r"Часовой пояс:\s*(?P<zone>[A-Za-z_+\-/]+)\.$",
)
YEAR_RE = re.compile(r"\b\d{4}\b")
CLOCK_RE = re.compile(
    r"\b(?:\d|[01]\d|2[0-3]):[0-5]\d\b"
    r"|\b(?:\d|[01]\d|2[0-3])\s+(?:(?:[0-5]\d)\b|утра\b|дня\b|вечера\b|ночи\b)",
)
NOTE_COMMAND_RE = re.compile(
    r"^\s*(?:(?:запиши|добавь|сохрани)\s+)?в\s+заметки\s*"
    r"(?::|,|-)?\s*(?P<text>.+?)\s*$",
    re.IGNORECASE | re.DOTALL,
)
ACTION_REPLY_PREFIXES = (
    "Событие создано:",
    "Событие изменено:",
    "Событие удалено:",
    "Заметка сохранена:",
    "Сохраняю заметку.",
)
FORBIDDEN_GENERIC_REPLIES = frozenset(
    {
        "Данные события распознаны.",
        "Недостаточно данных для выполнения операции.",
    },
)
CLARIFICATION_REPLY_RE = re.compile(
    r"\?"
    r"|\b(?:уточните|уточни|укажите|укажи)\b"
    r"|\b(?:скажите|скажи)\b"
    r"(?=[^.!?\r\n]*(?:\b(?:что|когда|где|сколько)\b|\bкак\w*\b|"
    r"\b(?:назван\w*|врем\w*|длительн\w*|дат\w*|период\w*)\b))",
    re.IGNORECASE,
)
CALENDAR_FIELD_REQUEST_RE = re.compile(
    r"\b(?:назовите|назови|сообщите|сообщи|введите|введи|выберите|выбери|"
    r"добавьте|добавь|задайте|задай)\b"
    r"|\b(?:нужно|необходимо|требуется)\s+"
    r"(?:назвать|указать|сообщить|ввести|выбрать|добавить|задать)\b",
    re.IGNORECASE,
)
SUM_RESULT_REPLY_RE = re.compile(
    r"\d|\b(?:итог|итого|составля\w*|равн\w*)\b",
    re.IGNORECASE,
)
FORBIDDEN_TEXT_PUNCTUATION_RE = re.compile(r"[\u2014\u00ab\u00bb]")


class DatasetContractError(ValueError):
    """Raised when a record cannot safely be used for training or scoring."""


def _fail(location: str, message: str) -> None:
    raise DatasetContractError(f"{location}: {message}")


def _require_string(value: Any, location: str, *, non_empty: bool = False) -> str:
    if not isinstance(value, str):
        _fail(location, "expected a string")
    if non_empty and not value.strip():
        _fail(location, "must not be empty")
    return value


def _require_positive_int(value: Any, location: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        _fail(location, "expected a positive integer")
    return value


def _require_int(value: Any, location: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        _fail(location, "expected an integer")
    return value


def _reject_action_reply_prefix(reply: str, location: str, context: str) -> None:
    if reply.startswith(ACTION_REPLY_PREFIXES):
        _fail(
            f"{location}.reply",
            f"{context} reply must not begin with an event-action prefix",
        )


def _parse_datetime(value: Any, location: str) -> str:
    text = _require_string(value, location, non_empty=True)
    try:
        datetime.strptime(text, "%Y-%m-%dT%H:%M")
    except ValueError as error:
        _fail(location, f"invalid local timestamp: {error}")
    return text


def _parse_date(value: Any, location: str) -> str:
    text = _require_string(value, location, non_empty=True)
    try:
        datetime.strptime(text, "%Y-%m-%d")
    except ValueError as error:
        _fail(location, f"invalid local date: {error}")
    return text


def _parse_time(value: Any, location: str) -> str:
    text = _require_string(value, location, non_empty=True)
    try:
        datetime.strptime(text, "%H:%M")
    except ValueError as error:
        _fail(location, f"invalid local time: {error}")
    return text


def canonical_system_prompt(content: str) -> str:
    """Return the exact minimal Android system-prompt form.

    Old candidate rows used a verbose sentence. They are normalised only while
    producing SFT artifacts; the checked-in generator now produces this form.
    """

    matched = RUNTIME_SYSTEM_RE.fullmatch(content) or LEGACY_SYSTEM_RE.fullmatch(content)
    if not matched:
        _fail("messages[0].content", "does not match the Android temporal system prompt")
    fields = matched.groupdict()
    _parse_datetime(f"{fields['date']}T{fields['time']}", "messages[0].content")
    return (
        f"Сегодня дата и время:{fields['date']} ({fields['weekday']}) "
        f"{fields['time']} {fields['zone']} ответ JSON"
    )


def _contains_null(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, dict):
        return any(_contains_null(item) for item in value.values())
    if isinstance(value, list):
        return any(_contains_null(item) for item in value)
    return False


def _contains_forbidden_text_punctuation(value: Any) -> bool:
    if isinstance(value, str):
        return FORBIDDEN_TEXT_PUNCTUATION_RE.search(value) is not None
    if isinstance(value, dict):
        return any(_contains_forbidden_text_punctuation(item) for item in value.values())
    if isinstance(value, list):
        return any(_contains_forbidden_text_punctuation(item) for item in value)
    return False


def parse_and_validate_assistant_response(
    content: str,
    location: str = "assistant.content",
    *,
    contract_version: str | None = None,
) -> dict[str, Any]:
    """Parse and validate one strict response object, returning its JSON value."""

    if contract_version not in {None, "v12.1", "v12.5"}:
        _fail(location, f"unsupported contract version {contract_version!r}")
    raw = _require_string(content, location, non_empty=True)
    try:
        response = json.loads(raw)
    except json.JSONDecodeError as error:
        _fail(location, f"invalid JSON: {error.msg}")
    if not isinstance(response, dict) or frozenset(response) != TOP_LEVEL_KEYS:
        _fail(location, "must contain exactly intent, reply, and params")
    if _contains_null(response):
        _fail(location, "must not contain null")
    intent = response["intent"]
    if intent not in INTENTS:
        _fail(f"{location}.intent", f"unsupported intent {intent!r}")
    if contract_version == "v12.5" and intent == "note_add":
        _fail(f"{location}.intent", "intent is outside the v12.5 calendar contract")
    # note_add.params.text is opaque user-authored text and may contain any
    # punctuation that the user wants preserved verbatim.
    # In the current contract, presentation rules apply to reply only. Keep
    # legacy validation reproducible without imposing its style on new inputs.
    styled_text = response["reply"] if contract_version == "v12.5" else response
    if intent != "note_add" and _contains_forbidden_text_punctuation(styled_text):
        _fail(location, "must not contain forbidden text punctuation")
    reply = _require_string(response["reply"], f"{location}.reply", non_empty=True)
    if reply in FORBIDDEN_GENERIC_REPLIES:
        _fail(f"{location}.reply", "uses an excluded generic fallback reply")
    if CLARIFICATION_REPLY_RE.search(reply):
        _fail(f"{location}.reply", "must not ask the user for clarification")
    if intent in CALENDAR_INTENTS and CALENDAR_FIELD_REQUEST_RE.search(reply):
        _fail(f"{location}.reply", "must not request a missing calendar field")
    if intent in CALENDAR_INTENTS and YEAR_RE.search(reply):
        _fail(f"{location}.reply", "must not contain a year")
    if intent in CALENDAR_INTENTS and CLOCK_RE.search(reply):
        _fail(f"{location}.reply", "must spell event times in words")
    params = response["params"]
    if not isinstance(params, dict):
        _fail(f"{location}.params", "must be an object")

    if intent == "chat":
        if params:
            _fail(f"{location}.params", "chat params must be exactly {}")
        _reject_action_reply_prefix(reply, location, "a chat")
    elif intent == "calendar_add":
        _validate_add(params, reply, location, contract_version=contract_version)
    elif intent == "calendar_search":
        _validate_search(params, reply, location)
    elif intent == "calendar_delete":
        _validate_delete(params, reply, location)
    elif intent == "calendar_sum":
        _validate_sum(params, reply, location)
    elif intent == "note_add":
        _validate_note_add(params, reply, location)
    else:
        _validate_update(params, reply, location)
    return response


def _validate_add(
    params: dict[str, Any], reply: str, location: str, *, contract_version: str | None = None,
) -> None:
    allowed = {"title", "starts_at", "date", "time", "duration_min", "value"}
    if not set(params).issubset(allowed):
        _fail(f"{location}.params", "contains an unsupported calendar_add field")
    has_starts_at = "starts_at" in params
    has_date = "date" in params
    has_time = "time" in params
    if has_starts_at and (has_date or has_time):
        _fail(f"{location}.params", "starts_at must not be combined with date or time")
    if not has_starts_at and not has_date:
        _fail(
            f"{location}.params",
            "calendar_add must contain an explicit or inferred date",
        )
    if "title" in params:
        _require_string(params["title"], f"{location}.params.title", non_empty=True)
    if has_starts_at:
        _parse_datetime(params["starts_at"], f"{location}.params.starts_at")
    if has_date:
        _parse_date(params["date"], f"{location}.params.date")
    if has_time:
        _parse_time(params["time"], f"{location}.params.time")
    if "duration_min" in params:
        _require_positive_int(params["duration_min"], f"{location}.params.duration_min")
    if "value" in params:
        _require_int(params["value"], f"{location}.params.value")
    is_complete = (
        "title" in params
        and "duration_min" in params
        and (has_starts_at or (has_date and has_time))
    )
    if contract_version == "v12.1":
        expected_reply = v12_1_creation_reply(params)
        if reply != expected_reply:
            _fail(f"{location}.reply", "v12.1 calendar_add reply must contain only the title")
    elif contract_version == "v12.5":
        # Known time may be described in reply. A model extraction is not a
        # database receipt, even when all calendar parameters are present.
        _reject_action_reply_prefix(reply, location, "an unexecuted calendar_add")
    elif is_complete:
        if ", в " not in reply:
            _fail(
                f"{location}.reply",
                "a complete calendar_add reply must put a comma before the spoken time",
            )
    else:
        _reject_action_reply_prefix(reply, location, "a partial calendar_add")


def v12_1_creation_reply(params: dict[str, Any]) -> str:
    """Copy the reviewed title, without a saving claim or scheduling metadata."""

    title = params.get("title")
    if title is None:
        return "Название события не указано."
    return title if title.endswith((".", "!", "?")) else title + "."


def _validate_search(params: dict[str, Any], reply: str, location: str) -> None:
    allowed = {"query", "range_start", "range_end"}
    if not set(params).issubset(allowed):
        _fail(f"{location}.params", "contains an unsupported calendar_search field")
    if "query" in params:
        _require_string(params["query"], f"{location}.params.query")
    _validate_optional_range(params, f"{location}.params")
    _reject_action_reply_prefix(reply, location, "a calendar_search")


def _validate_update(params: dict[str, Any], reply: str, location: str) -> None:
    if set(params) != {"target", "changes"}:
        _fail(f"{location}.params", "calendar_update needs exactly target and changes")
    target = params["target"]
    changes = params["changes"]
    if not isinstance(target, dict) or not isinstance(changes, dict):
        _fail(f"{location}.params", "target and changes must be objects")
    target_allowed = {"query", "range_start", "range_end", "use_last_created"}
    change_allowed = {"title", "date", "time", "duration_min", "value", "clear_value"}
    if not set(target).issubset(target_allowed):
        _fail(f"{location}.params.target", "contains an unsupported target field")
    if not set(changes).issubset(change_allowed):
        _fail(f"{location}.params.changes", "contains an unsupported change field")
    has_query = "query" in target
    has_last_created = "use_last_created" in target
    if has_query:
        _require_string(target["query"], f"{location}.params.target.query", non_empty=True)
    if has_last_created and target["use_last_created"] is not True:
        _fail(f"{location}.params.target.use_last_created", "must be true when present")
    if has_query and has_last_created:
        _fail(f"{location}.params.target", "must not combine query and use_last_created")
    has_range_start = "range_start" in target
    has_range_end = "range_end" in target
    if has_range_start != has_range_end:
        _fail(f"{location}.params.target", "range_start and range_end must be paired")
    if has_range_start:
        if not has_query:
            _fail(f"{location}.params.target", "a source range requires query")
        start = _parse_datetime(target["range_start"], f"{location}.params.target.range_start")
        end = _parse_datetime(target["range_end"], f"{location}.params.target.range_end")
        if start >= end:
            _fail(f"{location}.params.target", "range_start must be before range_end")
    if "title" in changes:
        _require_string(changes["title"], f"{location}.params.changes.title", non_empty=True)
    if "date" in changes:
        _parse_date(changes["date"], f"{location}.params.changes.date")
    if "time" in changes:
        _parse_time(changes["time"], f"{location}.params.changes.time")
    if "duration_min" in changes:
        _require_positive_int(changes["duration_min"], f"{location}.params.changes.duration_min")
    if "value" in changes:
        _require_int(changes["value"], f"{location}.params.changes.value")
    if "clear_value" in changes and changes["clear_value"] is not True:
        _fail(f"{location}.params.changes.clear_value", "must be true when present")
    if "value" in changes and "clear_value" in changes:
        _fail(f"{location}.params.changes", "must not combine value and clear_value")
    if changes and (has_query or has_last_created):
        if not reply.startswith("Событие изменено:"):
            _fail(f"{location}.reply", "an executable calendar_update reply must begin with 'Событие изменено:'")
    else:
        _reject_action_reply_prefix(reply, location, "an incomplete calendar_update")


def _validate_delete(params: dict[str, Any], reply: str, location: str) -> None:
    if set(params) != {"target"} or not isinstance(params["target"], dict):
        _fail(f"{location}.params", "calendar_delete needs exactly one target object")
    target = params["target"]
    allowed = {"query", "range_start", "range_end", "use_last_created", "use_last_in_range"}
    if not set(target).issubset(allowed):
        _fail(f"{location}.params.target", "contains an unsupported calendar_delete field")
    has_query = "query" in target
    has_last_created = "use_last_created" in target
    has_last_in_range = "use_last_in_range" in target
    if has_query:
        _require_string(target["query"], f"{location}.params.target.query", non_empty=True)
    if has_last_created and target["use_last_created"] is not True:
        _fail(f"{location}.params.target.use_last_created", "must be true when present")
    if has_last_in_range and target["use_last_in_range"] is not True:
        _fail(f"{location}.params.target.use_last_in_range", "must be true when present")
    target_count = sum((has_query, has_last_created, has_last_in_range))
    if target_count > 1:
        _fail(f"{location}.params.target", "must not combine delete target modes")
    has_range_start = "range_start" in target
    has_range_end = "range_end" in target
    if has_range_start != has_range_end:
        _fail(f"{location}.params.target", "range_start and range_end must be paired")
    if has_range_start:
        if not has_query and not has_last_in_range:
            _fail(f"{location}.params.target", "a delete range requires query or use_last_in_range")
        start = _parse_datetime(target["range_start"], f"{location}.params.target.range_start")
        end = _parse_datetime(target["range_end"], f"{location}.params.target.range_end")
        if start >= end:
            _fail(f"{location}.params.target", "range_start must be before range_end")
    elif has_last_in_range:
        _fail(f"{location}.params.target", "use_last_in_range requires a target range")
    if target_count == 1:
        if not reply.startswith("Событие удалено:"):
            _fail(f"{location}.reply", "a calendar_delete reply must begin with 'Событие удалено:'")
    else:
        _reject_action_reply_prefix(reply, location, "an incomplete calendar_delete")


def _validate_optional_range(params: dict[str, Any], location: str) -> None:
    has_range_start = "range_start" in params
    has_range_end = "range_end" in params
    if has_range_start != has_range_end:
        _fail(location, "range_start and range_end must be paired")
    if has_range_start:
        start = _parse_datetime(params["range_start"], f"{location}.range_start")
        end = _parse_datetime(params["range_end"], f"{location}.range_end")
        if start >= end:
            _fail(location, "range_start must be before range_end")


def _validate_sum(params: dict[str, Any], reply: str, location: str) -> None:
    allowed = {"query", "range_start", "range_end"}
    if not set(params).issubset(allowed):
        _fail(f"{location}.params", "contains an unsupported calendar_sum field")
    if "query" in params:
        _require_string(params["query"], f"{location}.params.query", non_empty=True)
    _validate_optional_range(params, f"{location}.params")
    if SUM_RESULT_REPLY_RE.search(reply):
        _fail(f"{location}.reply", "must not state a calculated sum result")
    _reject_action_reply_prefix(reply, location, "a calendar_sum")


def _validate_note_add(params: dict[str, Any], reply: str, location: str) -> None:
    if set(params) != {"text"}:
        _fail(f"{location}.params", "note_add needs exactly one text field")
    _require_string(params["text"], f"{location}.params.text", non_empty=True)
    if reply != "Сохраняю заметку.":
        _fail(f"{location}.reply", "note_add reply must be exactly 'Сохраняю заметку.'")


def normalize_record(record: Any, location: str) -> dict[str, Any]:
    """Validate a JSONL row and return its deterministic training representation."""

    if not isinstance(record, dict):
        _fail(location, "row must be an object")
    allowed = {"category", "messages", "case_id", "contract_version"}
    if not set(record).issubset(allowed) or not {"category", "messages"}.issubset(record):
        _fail(location, "row needs category and messages, with optional case_id and contract_version")
    contract_version = record.get("contract_version")
    if "contract_version" in record and contract_version not in {"v12.1", "v12.5"}:
        _fail(location, "explicit contract_version must be v12.1 or v12.5")
    category = _require_string(record["category"], f"{location}.category", non_empty=True)
    messages = record["messages"]
    if not isinstance(messages, list) or len(messages) not in {3, 4}:
        _fail(f"{location}.messages", "must contain system, one or two user messages, and assistant")
    last_index = len(messages) - 1
    last_message = messages[last_index]
    if not isinstance(last_message, dict) or set(last_message) != {"role", "content"}:
        _fail(f"{location}.messages[{last_index}]", "each message needs exactly role and content")
    if last_message["role"] != "assistant":
        _fail(f"{location}.messages[{last_index}].role", "last role must be assistant")
    assistant_response = parse_and_validate_assistant_response(
        last_message["content"],
        f"{location}.messages[{last_index}].content",
        contract_version=contract_version,
    )
    is_note_add = assistant_response["intent"] == "note_add"
    normalized_messages: list[dict[str, str]] = []
    for index, message in enumerate(messages):
        if not isinstance(message, dict) or set(message) != {"role", "content"}:
            _fail(f"{location}.messages[{index}]", "each message needs exactly role and content")
        role = _require_string(message["role"], f"{location}.messages[{index}].role", non_empty=True)
        content = _require_string(message["content"], f"{location}.messages[{index}].content")
        if index == 0:
            if role != "system":
                _fail(f"{location}.messages[0].role", "first role must be system")
            content = canonical_system_prompt(content)
        elif index == last_index:
            if role != "assistant":
                _fail(f"{location}.messages[{index}].role", "last role must be assistant")
            content = json.dumps(assistant_response, ensure_ascii=False, separators=(",", ":"))
        elif role != "user":
            _fail(f"{location}.messages[{index}].role", "middle roles must be user")
        elif contract_version != "v12.5" and not is_note_add and FORBIDDEN_TEXT_PUNCTUATION_RE.search(content):
            _fail(
                f"{location}.messages[{index}].content",
                "must not contain forbidden text punctuation",
            )
        elif contract_version != "v12.5" and not is_note_add and CLOCK_RE.search(content):
            _fail(
                f"{location}.messages[{index}].content",
                "must spell clock times in words",
            )
        normalized_messages.append({"role": role, "content": content})
    if is_note_add:
        source_text = normalized_messages[-2]["content"]
        matched = NOTE_COMMAND_RE.fullmatch(source_text)
        if not matched:
            _fail(
                f"{location}.messages[-2].content",
                "note_add source must use a supported note command prefix",
            )
        if matched.group("text") != assistant_response["params"]["text"]:
            _fail(
                f"{location}.messages[-1].content.params.text",
                "must exactly preserve the note text after the command prefix",
            )
    normalized: dict[str, Any] = {"category": category, "messages": normalized_messages}
    if contract_version is not None:
        normalized["contract_version"] = contract_version
    if "case_id" in record:
        normalized["case_id"] = _require_string(record["case_id"], f"{location}.case_id", non_empty=True)
    return normalized


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            _fail(f"{path}:{line_number}", "blank rows are not allowed")
        try:
            raw = json.loads(line)
        except json.JSONDecodeError as error:
            _fail(f"{path}:{line_number}", f"invalid row JSON: {error.msg}")
        rows.append(normalize_record(raw, f"{path}:{line_number}"))
    if not rows:
        _fail(str(path), "file is empty")
    return rows


def message_signature(row: dict[str, Any]) -> str:
    """Signature including temporal context, used for leakage and duplicate checks."""

    messages = row["messages"]
    signature = [{"role": item["role"], "content": item["content"]} for item in messages[:-1]]
    return json.dumps(signature, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def normalized_user_prompt(row: dict[str, Any]) -> str:
    """Normalize user wording independently of the changing temporal system context."""

    messages = row["messages"]
    text = " ".join(message["content"] for message in messages[1:-1]).casefold()
    return " ".join("".join(character if character.isalnum() else " " for character in text).split())


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def category_counts(rows: Iterable[dict[str, Any]]) -> dict[str, int]:
    return dict(sorted(Counter(row["category"] for row in rows).items()))
