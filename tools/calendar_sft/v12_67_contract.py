"""Independent four-action V12.67 contract; no historical command dispatch."""
from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import re

INTENTS = frozenset({"chat", "calendar_add", "calendar_search", "calendar_sum"})
CONTRACT_VERSION = "v12.67"
SYSTEM_RE = re.compile(
    r"^Сегодня дата и время:(\d{4}-\d{2}-\d{2}) \(([^()\r\n]+)\) "
    r"(\d{2}:\d{2}) Europe/Samara ответ JSON$"
)
TEMPORAL_RE = {
    "date": r"\d{4}-\d{2}-\d{2}",
    "time": r"\d{2}:\d{2}",
    "datetime": r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}",
}
FORMATS = {"date": "%Y-%m-%d", "time": "%H:%M", "datetime": "%Y-%m-%dT%H:%M"}


class DatasetContractError(ValueError):
    pass


def fail(location, message):
    raise DatasetContractError(f"{location}: {message}")


def string(value, location, allow_empty=False):
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        fail(location, "expected a string of the permitted length")
    return value


def integer(value, location, minimum=-(2 ** 63), maximum=2 ** 63 - 1):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        fail(location, "integer is outside the supported range")
    return value


def temporal(value, kind, location):
    value = string(value, location)
    if not re.fullmatch(TEMPORAL_RE[kind], value):
        fail(location, f"expected local {kind}")
    try:
        return datetime.strptime(value, FORMATS[kind])
    except ValueError as error:
        fail(location, str(error))


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail("JSON", "duplicate object key")
        result[key] = value
    return result


def invalid_constant(value):
    fail("JSON", "non-finite numbers are not permitted")


def decode(raw):
    try:
        return json.loads(raw, object_pairs_hook=unique_object, parse_constant=invalid_constant)
    except (TypeError, json.JSONDecodeError) as error:
        fail("JSON", str(error))


def parse_and_validate_assistant_response(raw, location="assistant", *, contract_version=CONTRACT_VERSION):
    if contract_version != CONTRACT_VERSION:
        fail(location, "use the V12.67 contract")
    response = decode(raw)
    if not isinstance(response, dict) or set(response) != {"intent", "reply", "params"}:
        fail(location, "expected exactly intent, reply and params")
    intent = response["intent"]
    if not isinstance(intent, str) or intent not in INTENTS:
        fail(location + ".intent", "action is outside the four-action contract")
    reply = string(response["reply"], location + ".reply")
    params = response["params"]
    if not isinstance(params, dict):
        fail(location + ".params", "expected an object")
    if any(value is None for value in params.values()):
        fail(location + ".params", "unknown fields must be omitted")
    if intent == "chat":
        if params:
            fail(location + ".params", "chat has no command fields")
        return response
    if re.search(r"[\u2014\u00ab\u00bb]", reply):
        fail(location + ".reply", "unsupported calendar reply punctuation")
    if reply.startswith("Событие создано:"):
        fail(location + ".reply", "model output is not a database receipt")
    if intent == "calendar_add":
        allowed = {"title", "starts_at", "ends_at", "date", "time", "duration_min", "value", "notes"}
        if not set(params) <= allowed:
            fail(location + ".params", "unsupported creation field")
        for key in ("title", "notes"):
            if key in params:
                string(params[key], location + ".params." + key, allow_empty=key == "notes")
        parsed = {}
        for key, kind in (("starts_at", "datetime"), ("ends_at", "datetime"), ("date", "date"), ("time", "time")):
            if key in params:
                parsed[key] = temporal(params[key], kind, location + ".params." + key)
        if "starts_at" in parsed:
            if "date" in parsed and parsed["starts_at"].date() != parsed["date"].date():
                fail(location + ".params.date", "conflicts with starts_at")
            if "time" in parsed and parsed["starts_at"].time() != parsed["time"].time():
                fail(location + ".params.time", "conflicts with starts_at")
        if "duration_min" in params:
            integer(params["duration_min"], location + ".params.duration_min", 1, 2147483647)
        if "value" in params:
            integer(params["value"], location + ".params.value")
        if "ends_at" in parsed:
            start = parsed.get("starts_at")
            if start is None and "date" in parsed and "time" in parsed:
                start = datetime.combine(parsed["date"].date(), parsed["time"].time())
            if start is None:
                fail(location + ".params.ends_at", "endpoint requires a known start")
            duration = (parsed["ends_at"] - start).total_seconds() / 60
            if not 0 < duration <= 2147483647:
                fail(location + ".params.ends_at", "endpoint must follow the start")
            if "duration_min" in params and params["duration_min"] != duration:
                fail(location + ".params.duration_min", "conflicts with the known endpoints")
    else:
        if not set(params) <= {"query", "range_start", "range_end"}:
            fail(location + ".params", "unsupported query field")
        if "query" in params:
            string(params["query"], location + ".params.query", allow_empty=intent == "calendar_search")
        if ("range_start" in params) != ("range_end" in params):
            fail(location + ".params", "range boundaries must be paired")
        if "range_start" in params:
            start = temporal(params["range_start"], "datetime", location + ".params.range_start")
            end = temporal(params["range_end"], "datetime", location + ".params.range_end")
            if start >= end:
                fail(location + ".params", "range start must precede its exclusive end")
        if intent == "calendar_sum" and re.search(r"\d|\b(?:итог|итого|составля\w*|равн\w*)\b", reply, re.I):
            fail(location + ".reply", "only the application can calculate the stored total")
    return response


def normalize_record(row, location="row"):
    if not isinstance(row, dict) or not set(row) <= {"category", "messages", "case_id", "contract_version"}:
        fail(location, "unsupported record shape")
    string(row.get("category"), location + ".category")
    if row.get("contract_version") != CONTRACT_VERSION:
        fail(location, "record must use the V12.67 contract")
    messages = row.get("messages")
    if not isinstance(messages, list) or len(messages) != 3:
        fail(location + ".messages", "expected one complete conversation")
    for index, (message, role) in enumerate(zip(messages, ("system", "user", "assistant"))):
        if not isinstance(message, dict) or set(message) != {"role", "content"} or message["role"] != role:
            fail(location, "invalid message order or fields")
        string(message["content"], f"{location}.messages[{index}].content")
    match = SYSTEM_RE.fullmatch(messages[0]["content"])
    if not match:
        fail(location, "unexpected temporal anchor")
    temporal(match[1] + "T" + match[3], "datetime", location + ".system")
    parse_and_validate_assistant_response(messages[-1]["content"], location + ".assistant")
    if "case_id" in row:
        string(row["case_id"], location + ".case_id")
    return row


def load_jsonl(path):
    rows = []
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        rows.append(normalize_record(decode(line), f"{path}:{number}"))
    if not rows:
        fail(str(path), "empty data file")
    return rows
