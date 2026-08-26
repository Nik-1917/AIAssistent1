"""Generate deterministic candidate JSONL corpora for calendar-assistant SFT.

The generated files are syntactically validated candidates, not a substitute for
semantic review before a production fine-tune. Run from the repository root:
    python tools/generate_calendar_training_dataset.py
"""

from __future__ import annotations

import json
import random
from calendar import monthrange
from datetime import date, datetime, time, timedelta
from pathlib import Path

from calendar_sft.dataset_contract import CLOCK_RE, parse_and_validate_assistant_response


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "docs" / "calendar_assistant_candidates"
TIME_ZONE = "Europe/Samara"

WEEKDAYS = (
    "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"
)
MONTHS_GENITIVE = (
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
DAYS_GENITIVE = {
    1: "первого", 2: "второго", 3: "третьего", 4: "четвёртого", 5: "пятого",
    6: "шестого", 7: "седьмого", 8: "восьмого", 9: "девятого", 10: "десятого",
    11: "одиннадцатого", 12: "двенадцатого", 13: "тринадцатого", 14: "четырнадцатого",
    15: "пятнадцатого", 16: "шестнадцатого", 17: "семнадцатого", 18: "восемнадцатого",
    19: "девятнадцатого", 20: "двадцатого", 21: "двадцать первого", 22: "двадцать второго",
    23: "двадцать третьего", 24: "двадцать четвёртого", 25: "двадцать пятого",
    26: "двадцать шестого", 27: "двадцать седьмого", 28: "двадцать восьмого",
    29: "двадцать девятого", 30: "тридцатого", 31: "тридцать первого",
}
HOURS = {
    0: "двенадцать часов ночи", 1: "час ночи", 2: "два часа ночи", 3: "три часа ночи",
    4: "четыре часа ночи", 5: "пять часов утра", 6: "шесть часов утра",
    7: "семь часов утра", 8: "восемь часов утра", 9: "девять часов утра",
    10: "десять часов утра", 11: "одиннадцать часов утра", 12: "двенадцать часов дня",
    13: "час дня", 14: "два часа дня", 15: "три часа дня", 16: "четыре часа дня",
    17: "пять часов дня", 18: "шесть часов вечера", 19: "семь часов вечера",
    20: "восемь часов вечера", 21: "девять часов вечера", 22: "десять часов вечера",
    23: "одиннадцать часов вечера",
}
MINUTES = {0: "", 5: " пять минут", 15: " пятнадцать минут", 20: " двадцать минут", 30: " тридцать минут", 40: " сорок минут", 45: " сорок пять минут"}
TIMES = (time(8, 0), time(9, 30), time(10, 15), time(11, 20), time(12, 0), time(14, 0), time(15, 45), time(18, 30), time(19, 0))
DURATIONS = (15, 30, 45, 60, 90, 120)
VALUES = (-20, 0, 5, 12, 25, 50, 100, 250)

TRAIN_TITLES = (
    "Встреча с Анной", "Созвон с командой", "Стоматолог", "Тренировка", "Оплата интернета",
    "Разговор с бухгалтером", "Урок английского", "Покупка продуктов", "Вебинар", "Пробежка",
    "Сервис автомобиля", "Проверка отчёта", "Совещание", "Поздравить маму", "Занятие йогой",
)
EVAL_TITLES = (
    "Встреча с Ильёй", "Созвон с дизайнером", "Приём у врача", "Плавание", "Оплата аренды",
    "Консультация", "Урок математики", "Забрать заказ", "Презентация", "Прогулка",
)

# Every requested occupation is represented in update examples. The event names
# describe normal work tasks and are deliberately not tied to real people.
UPDATE_SCENARIOS = (
    ("мастера маникюра", "Запись клиентки на маникюр", "маникюр", "Маникюр клиентке у Елены"),
    ("парикмахера", "Стрижка клиента", "стрижка", "Стрижка клиента в салоне"),
    ("врача", "Приём пациента", "приём", "Консультация пациента"),
    ("чиновника", "Приём граждан", "приём граждан", "Приём жителей района"),
    ("рабочего", "Смена на стройке", "смена", "Смена на объекте"),
    ("крестьянина", "Выезд в поле", "выезд в поле", "Осмотр поля"),
    ("спортсмена", "Тренировка в бассейне", "тренировка", "Тренировка по плаванию"),
    ("офисного сотрудника", "Созвон с отделом", "созвон", "Созвон по проекту"),
    ("таксиста", "Техосмотр такси", "техосмотр", "Техосмотр автомобиля"),
    ("уборщицы", "Уборка подъезда", "уборка", "Уборка первого подъезда"),
    ("водителя", "Рейс в Тольятти", "рейс", "Рейс в Тольятти утром"),
    ("преподавателя", "Урок алгебры", "урок", "Урок алгебры для десятого класса"),
    ("учащегося", "Консультация по курсовой", "консультация", "Консультация по курсовой работе"),
    ("соцработника", "Визит к подопечной", "визит", "Визит к подопечной Анне"),
    ("работника завода", "Смена у станка", "смена у станка", "Смена у фрезерного станка"),
)


def local_stamp(value: datetime) -> str:
    return value.strftime("%Y-%m-%dT%H:%M")


def implicit_calendar_add_start(anchor: datetime, event_time: time) -> datetime:
    """Resolve an omitted add date from the supplied local system time."""

    current_time = anchor.time().replace(second=0, microsecond=0)
    event_date = anchor.date() if event_time > current_time else anchor.date() + timedelta(days=1)
    return datetime.combine(event_date, event_time)


def add_months(value: date, months: int) -> date:
    absolute_month = value.year * 12 + value.month - 1 + months
    year, month_index = divmod(absolute_month, 12)
    month = month_index + 1
    return date(year, month, min(value.day, monthrange(year, month)[1]))


def month_bounds(value: date, offset: int) -> tuple[date, date]:
    start = add_months(value.replace(day=1), offset)
    return start, add_months(start, 1)


def week_bounds(value: date, offset: int) -> tuple[date, date]:
    start = value - timedelta(days=value.weekday()) + timedelta(days=offset * 7)
    return start, start + timedelta(days=7)


def system_message(anchor: datetime) -> str:
    # This must stay byte-for-byte compatible with SystemPromptProvider in the
    # Android application. The model is deliberately trained without a large
    # instruction prompt because production supplies only temporal context.
    return f"Сегодня дата и время:{anchor:%Y-%m-%d} ({WEEKDAYS[anchor.weekday()]}) {anchor:%H:%M} {TIME_ZONE} ответ JSON"


def date_words(value: date) -> str:
    return f"{DAYS_GENITIVE[value.day]} {MONTHS_GENITIVE[value.month - 1]}"


def reply_date_phrase(value: date, anchor: date) -> str:
    offset = (value - anchor).days
    relative = {
        0: "сегодня",
        1: "завтра",
        2: "послезавтра",
        3: "послепослезавтра",
        4: "через четыре дня",
    }
    return relative.get(offset, date_words(value))


def time_words(value: time) -> str:
    hour = HOURS[value.hour]
    minute = MINUTES[value.minute]
    if not minute:
        return f"в {hour}"
    period = hour.rsplit(" ", 1)[-1]
    hour_without_period = hour[: -(len(period) + 1)]
    return f"в {hour_without_period}{minute} {period}"


def clock_words(value: time) -> str:
    return time_words(value)[2:]


def duration_words(minutes: int) -> str:
    forms = {15: "пятнадцать минут", 30: "тридцать минут", 45: "сорок пять минут", 60: "час", 90: "полтора часа", 120: "два часа"}
    return forms[minutes]


def record(category: str, system: str, user: str, response: dict) -> dict:
    return {
        "category": category,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
            {"role": "assistant", "content": json.dumps(response, ensure_ascii=False, separators=(",", ":"))},
        ],
    }


def response(intent: str, reply: str, params: dict) -> dict:
    return {"intent": intent, "reply": reply, "params": params}


def period(anchor: datetime, kind: str, explicit_offset: int = 0) -> tuple[str, date, date]:
    today = anchor.date()
    if kind == "today":
        return "сегодня", today, today + timedelta(days=1)
    if kind == "yesterday":
        return "вчера", today - timedelta(days=1), today
    if kind == "day_before_yesterday":
        return "позавчера", today - timedelta(days=2), today - timedelta(days=1)
    if kind == "tomorrow":
        return "завтра", today + timedelta(days=1), today + timedelta(days=2)
    if kind == "after_tomorrow":
        return "послезавтра", today + timedelta(days=2), today + timedelta(days=3)
    if kind == "third_day":
        return "послепослезавтра", today + timedelta(days=3), today + timedelta(days=4)
    if kind == "in_two_days":
        return "через два дня", today + timedelta(days=2), today + timedelta(days=3)
    if kind == "in_three_days":
        return "через три дня", today + timedelta(days=3), today + timedelta(days=4)
    if kind == "in_four_days":
        return "через четыре дня", today + timedelta(days=4), today + timedelta(days=5)
    if kind in {"previous_month", "month_ago"}:
        start, end = month_bounds(today, -1)
        return ("в предыдущем месяце" if kind == "previous_month" else "месяц назад"), start, end
    if kind == "next_month":
        start, end = month_bounds(today, 1)
        return "в следующем месяце", start, end
    if kind == "in_one_month":
        start = add_months(today, 1)
        return "через месяц", start, start + timedelta(days=1)
    if kind == "in_two_months":
        start, end = month_bounds(today, 2)
        return "через два месяца", start, end
    if kind == "this_week":
        start = today - timedelta(days=today.weekday())
        return "на этой неделе", start, start + timedelta(days=7)
    if kind == "next_week":
        start = today - timedelta(days=today.weekday()) + timedelta(days=7)
        return "на следующей неделе", start, start + timedelta(days=7)
    if kind == "previous_week":
        start = today - timedelta(days=today.weekday()) - timedelta(days=7)
        return "на прошлой неделе", start, start + timedelta(days=7)
    if kind == "current_month":
        start = today.replace(day=1)
        return "в этом месяце", start, add_months(start, 1)
    if kind == "in_quarter":
        start = add_months(today, 3)
        return "через квартал", start, start + timedelta(days=1)
    if kind == "in_four_months":
        start = add_months(today, 4)
        return "через четыре месяца", start, start + timedelta(days=1)
    if kind == "in_half_year":
        start = add_months(today, 6)
        return "через полгода", start, start + timedelta(days=1)
    if kind == "current_year":
        start = today.replace(month=1, day=1)
        return "в этом году", start, start.replace(year=start.year + 1)
    if kind == "previous_year":
        end = today.replace(month=1, day=1)
        return "в прошлом году", end.replace(year=end.year - 1), end
    if kind == "next_year":
        start = today.replace(month=1, day=1, year=today.year + 1)
        return "в следующем году", start, start.replace(year=start.year + 1)
    start = today + timedelta(days=explicit_offset)
    return date_words(start), start, start + timedelta(days=1)


def search_period(anchor: datetime, kind: str, explicit_offset: int = 0) -> tuple[str, datetime, datetime]:
    """Return the local search interval for a relative calendar expression."""

    phrase, start_date, end_date = period(anchor, kind, explicit_offset)
    start = anchor if kind in {"today", "this_week"} else datetime.combine(start_date, time.min)
    end = datetime.combine(end_date, time.min)
    return phrase, start, end


def make_additions(anchor: datetime, titles: tuple[str, ...], rng: random.Random, total: int, start_index: int = 0) -> list[dict]:
    result = []
    relative_forms = (
        ("сегодня", 0), ("завтра", 1), ("послезавтра", 2),
        ("через два дня", 2), ("через три дня", 3), ("через четыре дня", 4),
    )
    for index in range(start_index, start_index + total):
        title = titles[index % len(titles)]
        event_date = anchor.date() + timedelta(days=index % 10)
        event_time = TIMES[index % len(TIMES)]
        duration = DURATIONS[index % len(DURATIONS)]
        start = datetime.combine(event_date, event_time)
        reply_phrase = None
        if index % 7 == 6:
            user_templates = (
                "Добавь событие {title} {time} на {duration}.",
                "Запиши в календарь событие {title} {time} на {duration}.",
                "Поставь событие {title} {time} на {duration}.",
            )
            user = user_templates[(index // 7) % len(user_templates)].format(
                title=title,
                time=time_words(event_time),
                duration=duration_words(duration),
            )
            start = implicit_calendar_add_start(anchor, event_time)
            reply = (
                f"Событие создано: {title} {reply_date_phrase(start.date(), anchor.date())}, "
                f"{time_words(event_time)}."
            )
            result.append(
                record(
                    "calendar_add_implicit_date",
                    system_message(anchor),
                    user,
                    response(
                        "calendar_add",
                        reply,
                        {
                            "title": title,
                            "starts_at": local_stamp(start),
                            "duration_min": duration,
                        },
                    ),
                ),
            )
            continue
        if index % 11 == 3:
            date_phrase = "через месяц"
            event_date = add_months(anchor.date(), 1)
            start = datetime.combine(event_date, event_time)
            reply_phrase = date_phrase
        elif index % 11 == 4:
            date_phrase = "через два месяца"
            event_date = add_months(anchor.date(), 2)
            start = datetime.combine(event_date, event_time)
            reply_phrase = date_phrase
        elif index % 5 == 0:
            date_phrase = date_words(event_date)
        elif index % 5 in (1, 2):
            date_phrase, offset = relative_forms[index % len(relative_forms)]
            event_date = anchor.date() + timedelta(days=offset)
            start = datetime.combine(event_date, event_time)
        else:
            date_phrase = date_words(event_date)
        user_templates = (
            "Добавь {date} {time} событие {title} на {duration}.",
            "Запиши событие {title} {date} {time} на {duration}.",
            "Поставь {date} {time} {title} на {duration}.",
        )
        user = user_templates[index % len(user_templates)].format(date=date_phrase, time=time_words(event_time), title=title, duration=duration_words(duration))
        reply = f"Событие создано: {title} {reply_phrase or reply_date_phrase(event_date, anchor.date())}, {time_words(event_time)}."
        result.append(record("calendar_add_complete", system_message(anchor), user, response("calendar_add", reply, {"title": title, "starts_at": local_stamp(start), "duration_min": duration})))
    return result


def make_partial_additions(anchor: datetime, titles: tuple[str, ...], total: int, start_index: int = 0) -> list[dict]:
    result = []
    for index in range(start_index, start_index + total):
        title = titles[index % len(titles)]
        event_date = anchor.date() + timedelta(days=(index % 7) + 1)
        event_time = TIMES[index % len(TIMES)]
        duration = DURATIONS[index % len(DURATIONS)]
        variant = index % 5
        if variant == 0:
            user = f"Запиши событие {title} {date_words(event_date)} {time_words(event_time)}."
            params = {"title": title, "starts_at": local_stamp(datetime.combine(event_date, event_time))}
            reply = f"{title} {date_words(event_date)}, {time_words(event_time)}."
        elif variant == 1:
            user = f"Добавь {date_words(event_date)} событие {title} на {duration_words(duration)}."
            params = {"title": title, "date": event_date.isoformat(), "duration_min": duration}
            reply = f"{title} {date_words(event_date)}, длительность {duration_words(duration)}."
        elif variant == 2:
            user = f"Поставь {date_words(event_date)} {time_words(event_time)} на {duration_words(duration)}."
            params = {"starts_at": local_stamp(datetime.combine(event_date, event_time)), "duration_min": duration}
            reply = f"{date_words(event_date).capitalize()}, {time_words(event_time)}, длительность {duration_words(duration)}."
        elif variant == 3:
            title_only_templates = (
                "Нужно не забыть: {title}.",
                "Добавь в календарь событие {title}.",
                "Хочу запланировать {title}.",
                "Запиши мне событие {title}.",
                "Надо внести в календарь {title}.",
            )
            template = title_only_templates[(index // (len(titles) * 5)) % len(title_only_templates)]
            user = template.format(title=title)
            params = {"title": title, "date": anchor.date().isoformat()}
            reply = f"{title} относится к сегодняшней дате."
        else:
            time_only_templates = (
                "Запиши событие {title} {time}.",
                "Добавь {title} {time}.",
                "Поставь {title} {time}.",
            )
            template = time_only_templates[(index // (len(titles) * 5)) % len(time_only_templates)]
            user = template.format(title=title, time=time_words(event_time))
            start = implicit_calendar_add_start(anchor, event_time)
            params = {"title": title, "starts_at": local_stamp(start)}
            reply = (
                f"{title} {reply_date_phrase(start.date(), anchor.date())}, "
                f"{time_words(event_time)}."
            )
        result.append(record("calendar_add_partial", system_message(anchor), user, response("calendar_add", reply, params)))
    return result


def make_searches(anchor: datetime, titles: tuple[str, ...], total: int, start_index: int = 0) -> list[dict]:
    result = []
    kinds = (
        "today", "tomorrow", "after_tomorrow", "third_day", "in_two_days",
        "in_three_days", "in_four_days", "this_week", "next_week", "previous_month",
        "month_ago", "next_month", "in_one_month", "in_two_months", "explicit",
    )
    for index in range(start_index, start_index + total):
        kind = kinds[index % len(kinds)]
        phrase, start, end = search_period(anchor, kind, explicit_offset=(index % 20) + 1)
        title = titles[index % len(titles)]
        query = "" if index % 3 == 0 else title
        if query:
            user_templates = (
                "Найди событие {title} {period}.",
                "Проверь, есть ли {title} {period}.",
                "Покажи {title} {period}.",
            )
            user = user_templates[(index // len(kinds)) % len(user_templates)].format(period=phrase, title=title)
            reply = f"Проверяю событие {title} {phrase}."
        else:
            user_templates = (
                "Что у меня {period}?",
                "Покажи все планы {period}.",
                "Какие события у меня {period}?",
            )
            user = user_templates[(index // len(kinds)) % len(user_templates)].format(period=phrase)
            reply = f"Проверяю все события {phrase}."
        if kind == "third_day":
            assert reply.endswith("послепослезавтра.")
        params = {"query": query, "range_start": local_stamp(start), "range_end": local_stamp(end)}
        result.append(record("calendar_search", system_message(anchor), user, response("calendar_search", reply, params)))
    return result


def make_value_commands(
    anchor: datetime,
    titles: tuple[str, ...],
    total: int,
    start_index: int = 0,
) -> list[dict]:
    rows = []
    for index in range(start_index, start_index + total):
        title = titles[index % len(titles)]
        value = VALUES[index % len(VALUES)]
        event_date = anchor.date() + timedelta(days=(index % 7) + 1)
        event_time = TIMES[index % len(TIMES)]
        duration = DURATIONS[index % len(DURATIONS)]
        variant = index % 4
        if variant == 0:
            user = (
                f"Добавь {date_words(event_date)} {time_words(event_time)} событие "
                f"{title} на {duration_words(duration)} с ценностью {value} единиц."
            )
            assistant = response(
                "calendar_add",
                f"Событие создано: {title} {date_words(event_date)}, {time_words(event_time)}.",
                {
                    "title": title,
                    "starts_at": local_stamp(datetime.combine(event_date, event_time)),
                    "duration_min": duration,
                    "value": value,
                },
            )
            category = "calendar_add_with_value"
        elif variant == 1:
            user = f"Запиши {title} {date_words(event_date)} с ценностью {value} единиц."
            assistant = response(
                "calendar_add",
                f"{title} {date_words(event_date)}, ценность {value} единиц.",
                {"title": title, "date": event_date.isoformat(), "value": value},
            )
            category = "calendar_add_partial_with_value"
        elif variant == 2:
            user = f"У события {title} установи ценность {value} единиц."
            assistant = response(
                "calendar_update",
                f"Событие изменено: ценность {title} равна {value} единицам.",
                {"target": {"query": title.lower()}, "changes": {"value": value}},
            )
            category = "calendar_update_value"
        else:
            user = f"Убери ценность у события {title}."
            assistant = response(
                "calendar_update",
                f"Событие изменено: ценность {title} удалена.",
                {"target": {"query": title.lower()}, "changes": {"clear_value": True}},
            )
            category = "calendar_update_clear_value"
        rows.append(record(category, system_message(anchor), user, assistant))
    return rows


def make_sums(
    anchor: datetime,
    titles: tuple[str, ...],
    total: int,
    start_index: int = 0,
) -> list[dict]:
    kinds = (
        "today",
        "yesterday",
        "day_before_yesterday",
        "tomorrow",
        "after_tomorrow",
        "this_week",
        "previous_week",
        "next_week",
        "current_month",
        "previous_month",
        "next_month",
        "in_one_month",
        "in_two_months",
        "in_quarter",
        "in_four_months",
        "in_half_year",
        "current_year",
        "previous_year",
        "next_year",
    )
    rows = []
    for index in range(start_index, start_index + total):
        title = titles[index % len(titles)]
        if index % 11 == 10:
            user = f"Подсчитай ценность событий {title}."
            reply = f"Ценность событий {title}."
            params = {"query": title.lower()}
            category = "calendar_sum_without_period"
        else:
            kind = kinds[index % len(kinds)]
            phrase, start, end = search_period(anchor, kind)
            has_query = index % 3 != 0
            if has_query:
                user = f"Подсчитай ценность событий {title} {phrase}."
                reply = f"Ценность событий {title} {phrase}."
                params = {
                    "query": title.lower(),
                    "range_start": local_stamp(start),
                    "range_end": local_stamp(end),
                }
                category = "calendar_sum_by_query"
            else:
                user = f"Подсчитай общую ценность всех событий {phrase}."
                reply = f"Общая ценность всех событий {phrase}."
                params = {"range_start": local_stamp(start), "range_end": local_stamp(end)}
                category = "calendar_sum_all"
        rows.append(record(category, system_message(anchor), user, response("calendar_sum", reply, params)))
    return rows


def update_destination(anchor: datetime, index: int) -> tuple[str, date]:
    today = anchor.date()
    kind = index % 10
    if kind == 0:
        return "завтра", today + timedelta(days=1)
    if kind == 1:
        return "послезавтра", today + timedelta(days=2)
    if kind == 2:
        return "через два дня", today + timedelta(days=2)
    if kind == 3:
        return "через три дня", today + timedelta(days=3)
    if kind == 4:
        return "через четыре дня", today + timedelta(days=4)
    if kind == 5:
        return "через месяц", add_months(today, 1)
    if kind == 6:
        return "через два месяца", add_months(today, 2)
    if kind == 7:
        days_until_next_monday = 7 - today.weekday()
        return "в следующий понедельник", today + timedelta(days=days_until_next_monday)
    next_month, _ = month_bounds(today, 1)
    if kind == 8:
        return "в первый день следующего месяца", next_month
    return "в пятнадцатый день следующего месяца", next_month.replace(day=15)


def move_request_phrase(destination: str) -> str:
    if destination in {"завтра", "послезавтра"}:
        return f"на {destination}"
    return destination


def make_updates(anchor: datetime, total: int, start_index: int = 0) -> list[dict]:
    result = []
    source_kinds = ("tomorrow", "after_tomorrow", "in_two_days", "previous_month", "month_ago")
    move_templates = (
        "Перенеси {title_lower} {destination}.",
        "Сдвинь {title_lower} {destination}.",
        "Перепиши в календаре {title_lower} {destination}.",
    )
    time_templates = (
        "У события {title} время исправь на {time}.",
        "Поставь для события {title} время на {time}.",
        "Передвинь событие {title} по времени на {time}.",
    )
    for index in range(start_index, start_index + total):
        occupation, title, query, renamed_title = UPDATE_SCENARIOS[index % len(UPDATE_SCENARIOS)]
        title_lower = title.lower()
        destination_phrase, destination_date = update_destination(anchor, index)
        new_time = TIMES[(index + 3) % len(TIMES)]
        new_duration = DURATIONS[(index + 2) % len(DURATIONS)]
        variant = index % 8

        if variant == 0:
            source_phrase, source_start, source_end = period(
                anchor,
                source_kinds[(index // len(UPDATE_SCENARIOS)) % len(source_kinds)],
            )
            if source_phrase == "в предыдущем месяце":
                source_phrase = "в том месяце"
            if source_start <= destination_date < source_end:
                destination_phrase, destination_date = "через четыре дня", anchor.date() + timedelta(days=4)
            source_move_templates = (
                "Перенеси событие {title}, запланированное {source}, {destination}.",
                "Сдвинь событие {title}, которое стоит {source}, {destination}.",
                "Перепиши в календаре событие {title}, запланированное {source}, {destination}.",
            )
            user = source_move_templates[index % len(source_move_templates)].format(
                title=title,
                source=source_phrase,
                destination=move_request_phrase(destination_phrase),
            )
            target = {
                "query": query,
                "range_start": f"{source_start:%Y-%m-%d}T00:00",
                "range_end": f"{source_end:%Y-%m-%d}T00:00",
            }
            changes = {"date": f"{destination_date:%Y-%m-%d}"}
            reply = f"Событие изменено: {title} перенесено {move_request_phrase(destination_phrase)}."
            category = "calendar_update_move_from_source_period"
        elif variant == 1:
            user = move_templates[index % len(move_templates)].format(
                title_lower=f"событие {title}",
                destination=move_request_phrase(destination_phrase),
            )
            target = {"query": query}
            changes = {"date": f"{destination_date:%Y-%m-%d}"}
            reply = f"Событие изменено: {title} перенесено {move_request_phrase(destination_phrase)}."
            category = "calendar_update_move_by_query"
        elif variant == 2:
            user = time_templates[index % len(time_templates)].format(
                title=title,
                time=clock_words(new_time),
            )
            target = {"query": query}
            changes = {"time": new_time.strftime("%H:%M")}
            reply = f"Событие изменено: новое время для {title} {time_words(new_time)}."
            category = "calendar_update_time_by_query"
        elif variant == 3:
            user = f"Измени длительность события {title} на {duration_words(new_duration)}."
            target = {"query": query}
            changes = {"duration_min": new_duration}
            reply = f"Событие изменено: длительность {title} {duration_words(new_duration)}."
            category = "calendar_update_duration_by_query"
        elif variant == 4:
            user = f"Переименуй {title} в {renamed_title}."
            target = {"query": query}
            changes = {"title": renamed_title}
            reply = f"Событие изменено: новое название {renamed_title}."
            category = "calendar_update_title_by_query"
        elif variant == 5:
            user = f"У последнего добавленного события время исправь на {clock_words(new_time)}."
            target = {"use_last_created": True}
            changes = {"time": new_time.strftime("%H:%M")}
            reply = f"Событие изменено: время последнего добавленного события {time_words(new_time)}."
            category = "calendar_update_last_created_time"
        elif variant == 6:
            user = f"Время исправь на {clock_words(new_time)}."
            target = {"use_last_created": True}
            changes = {"time": new_time.strftime("%H:%M")}
            reply = f"Событие изменено: время последнего добавленного события {time_words(new_time)}."
            category = "calendar_update_implicit_last_created"
        else:
            user = f"Измени событие {title}."
            target = {"query": query}
            changes = {}
            reply = f"Изменение события {title} без новых значений."
            category = "calendar_update_missing_change"

        result.append(
            record(
                category,
                system_message(anchor),
                user,
                response("calendar_update", reply, {"target": target, "changes": changes}),
            ),
        )
    return result


def make_chat(anchor: datetime, total: int, start_index: int = 0) -> list[dict]:
    ordinary = (
        ("Привет.", "Здравствуйте."),
        ("Что такое JSON?", "JSON текстовый формат для передачи структурированных данных."),
        ("Ты работаешь с моим календарём?", "Да, я могу подготовить создание события или поиск в локальном календаре приложения."),
        ("Как найти планы на неделю?", "Для поиска событий нужна конкретная неделя."),
        ("Спасибо.", "Пожалуйста."),
        ("Чем ты можешь помочь с календарём?", "Я могу подготовить событие или поискать события в локальном календаре."),
        ("Календарь работает без интернета?", "События хранятся в локальном календаре приложения."),
        ("Можно говорить обычными словами?", "Да, можно сформулировать запрос свободно, например назвать дату, время и событие."),
        ("Где сохраняются события?", "События сохраняются в локальном календаре приложения."),
        ("Покажи пример создания события.", "Например: добавь завтра в три часа дня встречу на час."),
        ("Можно искать события по названию?", "Да, поиск поддерживает слово из названия и период."),
        ("Ты знаешь текущее время?", "Я использую дату, время и часовой пояс, переданные приложением."),
        ("Как посмотреть планы на завтра?", "Подходит команда покажи события на завтра."),
        ("Нужно ли указывать длительность?", "Неизвестная длительность остаётся отсутствующим полем команды."),
        (
            "Что будет, если время неизвестно?",
            "Неизвестное время остаётся отсутствующим полем, а для события без даты используется сегодня.",
        ),
    )
    searches_without_period = (
        ("Найди встречу с Анной.", "встреча с Анной"),
        ("Покажи мои тренировки.", "тренировка"),
        ("Есть ли запись к врачу?", "запись к врачу"),
        ("Когда у меня созвон с командой?", "созвон с командой"),
        ("Найди оплату интернета.", "оплата интернета"),
        ("Проверь, есть ли дела на даче.", "дела на даче"),
        ("Покажи событие про МФЦ.", "МФЦ"),
        ("Есть ли у меня семейный ужин?", "семейный ужин"),
        ("Найди занятия английским.", "занятие английским"),
        ("Когда я забираю заказ из ПВЗ?", "заказ из ПВЗ"),
        ("Найди техосмотр автомобиля.", "техосмотр автомобиля"),
        ("Есть ли родительское собрание?", "родительское собрание"),
        ("Когда у меня визит к нотариусу?", "визит к нотариусу"),
        ("Покажи оплату мобильной связи.", "оплата мобильной связи"),
        ("Есть ли встреча с классным руководителем?", "встреча с классным руководителем"),
    )
    rows = []
    for index in range(start_index, start_index + total):
        item_index, kind = divmod(index, 2)
        if kind == 0:
            user, reply = ordinary[item_index % len(ordinary)]
            category = "ordinary_chat"
            intent = "chat"
            params = {}
        else:
            user, query = searches_without_period[item_index % len(searches_without_period)]
            reply = f"Проверяю события по запросу {query} без указанного периода."
            category = "calendar_search_without_period"
            intent = "calendar_search"
            params = {"query": query}
        rows.append(record(category, system_message(anchor), user, response(intent, reply, params)))
    return rows


def make_deletions(anchor: datetime, total: int, start_index: int = 0) -> list[dict]:
    scenarios = (
        ("встречу с врачом", "Встреча с врачом"),
        ("оплату ЖКХ", "Оплата ЖКХ"),
        ("запись в МФЦ", "Запись в МФЦ"),
        ("созвон с командой", "Созвон с командой"),
        ("напоминание забрать заказ из ПВЗ", "Напоминание забрать заказ из ПВЗ"),
        ("поездку на дачу", "Поездка на дачу"),
        ("семейный ужин", "Семейный ужин"),
        ("тренировку", "Тренировка"),
        ("запись клиентки на маникюр", "Запись клиентки на маникюр"),
        ("стрижку клиента", "Стрижка клиента"),
        ("приём пациента", "Приём пациента"),
        ("приём граждан", "Приём граждан"),
        ("смену на стройке", "Смена на стройке"),
        ("выезд в поле", "Выезд в поле"),
        ("тренировку в бассейне", "Тренировка в бассейне"),
        ("техосмотр такси", "Техосмотр такси"),
        ("уборку подъезда", "Уборка подъезда"),
        ("рейс в Тольятти", "Рейс в Тольятти"),
        ("урок алгебры", "Урок алгебры"),
        ("консультацию по курсовой", "Консультация по курсовой"),
        ("визит к подопечной", "Визит к подопечной"),
        ("смену у станка", "Смена у станка"),
    )
    verbs = ("Удали", "Отмени", "Убери из календаря", "Сотри из планов", "Удалите")
    rows = []
    for index in range(start_index, start_index + total):
        if index % 11 == 9:
            period_kind = (index // 11) % 6
            if period_kind == 0:
                phrase = "на сегодня"
                start = anchor.date()
                end = start + timedelta(days=1)
            elif period_kind == 1:
                phrase = "на завтра"
                start = anchor.date() + timedelta(days=1)
                end = start + timedelta(days=1)
            elif period_kind == 2:
                phrase = "на послезавтра"
                start = anchor.date() + timedelta(days=2)
                end = start + timedelta(days=1)
            elif period_kind == 3:
                phrase = "на этой неделе"
                start, end = week_bounds(anchor.date(), 0)
            elif period_kind == 4:
                phrase = "на следующей неделе"
                start, end = week_bounds(anchor.date(), 1)
            else:
                phrase = "в следующем месяце"
                start, end = month_bounds(anchor.date(), 1)
            rows.append(
                record(
                    "calendar_delete_last_in_range",
                    system_message(anchor),
                    f"Удали последнее {phrase}.",
                    response(
                        "calendar_delete",
                        f"Событие удалено: последнее событие {phrase}.",
                        {
                            "target": {
                                "use_last_in_range": True,
                                "range_start": f"{start:%Y-%m-%d}T00:00",
                                "range_end": f"{end:%Y-%m-%d}T00:00",
                            },
                        },
                    ),
                ),
            )
            continue
        if index % 11 == 10:
            user = (
                "Удали последнее добавленное событие.",
                "Убери последнее событие.",
                "Отмени последнее из календаря.",
                "Сотри последнее добавленное дело.",
                "Удалите последнее событие из планов.",
            )[index % 5]
            rows.append(
                record(
                    "calendar_delete_last_created",
                    system_message(anchor),
                    user,
                    response(
                        "calendar_delete",
                        "Событие удалено: последнее добавленное событие.",
                        {"target": {"use_last_created": True}},
                    ),
                ),
            )
            continue
        scenario_index = (index // 8) % len(scenarios)
        spoken_title, title = scenarios[scenario_index]
        period_kind = index % 8
        if period_kind < 4:
            days_from_now = period_kind + 1
            phrase = ("завтра", "послезавтра", "через три дня", "через четыре дня")[period_kind]
            start = anchor.date() + timedelta(days=days_from_now)
            end = start + timedelta(days=1)
        elif period_kind == 4:
            phrase = "на этой неделе"
            start, end = week_bounds(anchor.date(), 0)
        elif period_kind == 5:
            phrase = "на следующей неделе"
            start, end = week_bounds(anchor.date(), 1)
        elif period_kind == 6:
            phrase = "в следующем месяце"
            start, end = month_bounds(anchor.date(), 1)
        else:
            phrase = "в том месяце"
            start, end = month_bounds(anchor.date(), -1)
        target = {
            "query": title,
            "range_start": f"{start:%Y-%m-%d}T00:00",
            "range_end": f"{end:%Y-%m-%d}T00:00",
        }
        verb = verbs[index % len(verbs)]
        user = f"{verb} {spoken_title} {phrase}."
        reply = f"Событие удалено: {title} {phrase}."
        rows.append(record("calendar_delete", system_message(anchor), user, response("calendar_delete", reply, {"target": target})))
    return rows


def make_error_reinforcement(anchor: datetime, total: int, start_index: int = 0) -> list[dict]:
    """Add distinct paraphrases for known errors without copying holdout prompts."""
    rows = []
    for index in range(start_index, start_index + total):
        today = anchor.date()
        variant = index % 16
        if variant == 0:
            event_date = today + timedelta(days=2)
            event_time = time(6, 40)
            title = "Отправка документов в администрацию"
            user = f"Запиши на послезавтра в шесть сорок утра {title.lower()} на час."
            assistant = response(
                "calendar_add",
                f"Событие создано: {title} {reply_date_phrase(event_date, today)}, {time_words(event_time)}.",
                {"title": title, "starts_at": local_stamp(datetime.combine(event_date, event_time)), "duration_min": 60},
            )
            category = "reinforcement_add_complete_asr"
        elif variant == 1:
            event_date = today + timedelta(days=3)
            event_time = time(10, 15)
            user = "Поставь послепослезавтра в десять часов пятнадцать минут утра на сорок пять минут."
            assistant = response(
                "calendar_add",
                f"{date_words(event_date).capitalize()}, {time_words(event_time)}, длительность сорок пять минут.",
                {"starts_at": local_stamp(datetime.combine(event_date, event_time)), "duration_min": 45},
            )
            category = "reinforcement_add_missing_title"
        elif variant == 2:
            event_date = today + timedelta(days=1)
            title = "Обход склада"
            user = "Добавь обход склада завтра на час."
            assistant = response(
                "calendar_add",
                "Обход склада завтра, длительность час.",
                {"title": title, "date": event_date.isoformat(), "duration_min": 60},
            )
            category = "reinforcement_add_missing_time"
        elif variant == 3:
            event_date = add_months(today, 1)
            event_time = time(16, 0)
            title = "Оплата аренды склада"
            user = "Через месяц поставь оплату аренды склада в четыре часа дня."
            assistant = response(
                "calendar_add",
                "Оплата аренды склада через месяц, в четыре часа дня.",
                {"title": title, "starts_at": local_stamp(datetime.combine(event_date, event_time))},
            )
            category = "reinforcement_add_missing_duration"
        elif variant == 4:
            event_date = today + timedelta(days=3)
            query = "выдача спецодежды"
            user = "Через три дня найди выдачу спецодежды."
            assistant = response(
                "calendar_search",
                "Проверяю выдачу спецодежды послепослезавтра.",
                {"query": query, "range_start": f"{event_date:%Y-%m-%d}T00:00", "range_end": f"{event_date + timedelta(days=1):%Y-%m-%d}T00:00"},
            )
            category = "reinforcement_search_full_query_day"
        elif variant == 5:
            _, start, end = search_period(anchor, "this_week")
            query = "собрание родительского комитета"
            user = "На этой неделе покажи собрание родительского комитета."
            assistant = response(
                "calendar_search",
                "Проверяю собрание родительского комитета на этой неделе.",
                {"query": query, "range_start": local_stamp(start), "range_end": local_stamp(end)},
            )
            category = "reinforcement_search_week_full_query"
        elif variant == 6:
            start, end = month_bounds(today, -1)
            query = "план технического обслуживания"
            user = "Найди план технического обслуживания в том месяце."
            assistant = response(
                "calendar_search",
                "Проверяю план технического обслуживания в том месяце.",
                {"query": query, "range_start": f"{start:%Y-%m-%d}T00:00", "range_end": f"{end:%Y-%m-%d}T00:00"},
            )
            category = "reinforcement_search_previous_month_full_query"
        elif variant == 7:
            start, end = month_bounds(today, 2)
            query = "медицинская комиссия водителя"
            user = "Через два месяца посмотри медицинскую комиссию водителя."
            assistant = response(
                "calendar_search",
                "Проверяю медицинскую комиссию водителя через два месяца.",
                {"query": query, "range_start": f"{start:%Y-%m-%d}T00:00", "range_end": f"{end:%Y-%m-%d}T00:00"},
            )
            category = "reinforcement_search_two_months_full_query"
        elif variant == 8:
            query = "техосмотр грузовой машины"
            user = "У техосмотра грузовой машины время исправь на десять часов утра."
            assistant = response(
                "calendar_update",
                "Событие изменено: время техосмотра грузовой машины в десять часов утра.",
                {"target": {"query": query}, "changes": {"time": "10:00"}},
            )
            category = "reinforcement_update_time_full_query"
        elif variant == 9:
            query = "смена по разгрузке"
            user = "Смену по разгрузке сократи до шести часов."
            assistant = response(
                "calendar_update",
                "Событие изменено: длительность смены по разгрузке шесть часов.",
                {"target": {"query": query}, "changes": {"duration_min": 360}},
            )
            category = "reinforcement_update_duration_not_add"
        elif variant == 10:
            source_date = today + timedelta(days=2)
            destination_date = today + timedelta(days=4)
            query = "проверка пожарного щита"
            user = "Проверку пожарного щита послезавтра перенеси через четыре дня."
            assistant = response(
                "calendar_update",
                "Событие изменено: проверка пожарного щита перенесена через четыре дня.",
                {
                    "target": {
                        "query": query,
                        "range_start": f"{source_date:%Y-%m-%d}T00:00",
                        "range_end": f"{source_date + timedelta(days=1):%Y-%m-%d}T00:00",
                    },
                    "changes": {"date": f"{destination_date:%Y-%m-%d}"},
                },
            )
            category = "reinforcement_update_source_range"
        elif variant == 11:
            query = "выдача пропуска на завод"
            user = "Исправь выдачу пропуска на завод."
            assistant = response(
                "calendar_update",
                "Изменение события Выдача пропуска на завод без новых значений.",
                {"target": {"query": query}, "changes": {}},
            )
            category = "reinforcement_update_missing_change"
        elif variant == 12:
            event_date = today + timedelta(days=1)
            query = "приём документов в МФЦ"
            user = "Удали приём документов в МФЦ на завтра."
            assistant = response(
                "calendar_delete",
                "Событие удалено: приём документов в МФЦ на завтра.",
                {
                    "target": {
                        "query": query,
                        "range_start": f"{event_date:%Y-%m-%d}T00:00",
                        "range_end": f"{event_date + timedelta(days=1):%Y-%m-%d}T00:00",
                    },
                },
            )
            category = "reinforcement_delete_full_query"
        elif variant == 13:
            user = "Сотри последнее добавленное событие."
            assistant = response(
                "calendar_delete",
                "Событие удалено: последнее добавленное событие.",
                {"target": {"use_last_created": True}},
            )
            category = "reinforcement_delete_last_created"
        elif variant == 14:
            event_date = today + timedelta(days=2)
            user = "Удали последнее на послезавтра."
            assistant = response(
                "calendar_delete",
                "Событие удалено: последнее событие послезавтра.",
                {
                    "target": {
                        "use_last_in_range": True,
                        "range_start": f"{event_date:%Y-%m-%d}T00:00",
                        "range_end": f"{event_date + timedelta(days=1):%Y-%m-%d}T00:00",
                    },
                },
            )
            category = "reinforcement_delete_last_in_range"
        else:
            default_case = (index // 16) % 3
            if default_case == 0:
                title = "Проверка отчёта"
                event_time = time(18, 30)
                user = "Добавь проверку отчёта в шесть часов тридцать минут вечера на час."
                category = "reinforcement_add_implicit_date_today"
            elif default_case == 1:
                title = "Техосмотр автомобиля"
                event_time = time(9, 0)
                user = "Запиши техосмотр автомобиля в девять часов утра на час."
                category = "reinforcement_add_implicit_date_tomorrow"
            else:
                title = "Созвон с мастером"
                event_time = anchor.time().replace(second=0, microsecond=0)
                user = f"Поставь созвон с мастером {time_words(event_time)} на сорок пять минут."
                category = "reinforcement_add_implicit_date_equal_tomorrow"
            start = implicit_calendar_add_start(anchor, event_time)
            assistant = response(
                "calendar_add",
                (
                    f"Событие создано: {title} {reply_date_phrase(start.date(), anchor.date())}, "
                    f"{time_words(event_time)}."
                ),
                {
                    "title": title,
                    "starts_at": local_stamp(start),
                    "duration_min": 60 if default_case != 2 else 45,
                },
            )
        rows.append(record(category, system_message(anchor), user, assistant))
    return rows


def make_previous_month_phrase_searches(anchor: datetime, titles: tuple[str, ...], total: int, start_index: int = 0) -> list[dict]:
    rows = []
    for index in range(start_index, start_index + total):
        start, end = month_bounds(anchor.date(), -1)
        title = titles[index % len(titles)]
        user_templates = (
            "Что у меня было в том месяце?",
            "Найди {title} в том месяце.",
            "Покажи планы в том месяце.",
        )
        template_index = (index // len(titles)) % len(user_templates)
        user = user_templates[template_index].format(title=title.lower())
        params = {"query": title.split()[-1], "range_start": f"{start:%Y-%m-%d}T00:00", "range_end": f"{end:%Y-%m-%d}T00:00"}
        if template_index != 1:
            params["query"] = ""
        rows.append(record("calendar_search_previous_month_phrase", system_message(anchor), user, response("calendar_search", f"Проверяю {title.lower() if params['query'] else 'все события'} в том месяце.", params)))
    return rows


def build(anchor: datetime, titles: tuple[str, ...], target: int, seed: int, sequence_offset: int) -> list[dict]:
    rng = random.Random(seed)
    def in_date_varied_batches(count: int, factory) -> list[dict]:
        rows = []
        remaining = count
        batch = 0
        while remaining:
            # A distinct temporal context on every row prevents exact template
            # duplicates and teaches relative-date resolution rather than recall.
            size = 1
            context_anchor = anchor + timedelta(days=batch)
            rows.extend(factory(context_anchor, size, batch + sequence_offset))
            remaining -= size
            batch += 1
        return rows

    additions = in_date_varied_batches(
        int(target * 0.18),
        lambda context_anchor, size, index: make_additions(context_anchor, titles, rng, size, index),
    )
    partial = in_date_varied_batches(
        int(target * 0.10),
        lambda context_anchor, size, index: make_partial_additions(context_anchor, titles, size, index),
    )
    searches = in_date_varied_batches(
        int(target * 0.14),
        lambda context_anchor, size, index: make_searches(context_anchor, titles, size, index),
    )
    updates = in_date_varied_batches(
        int(target * 0.18),
        lambda context_anchor, size, index: make_updates(context_anchor, size, index),
    )
    deletions = in_date_varied_batches(
        int(target * 0.05),
        lambda context_anchor, size, index: make_deletions(context_anchor, size, index),
    )
    reinforcement = in_date_varied_batches(
        int(target * 0.10),
        lambda context_anchor, size, index: make_error_reinforcement(context_anchor, size, index),
    )
    values = in_date_varied_batches(
        int(target * 0.095),
        lambda context_anchor, size, index: make_value_commands(context_anchor, titles, size, index),
    )
    sums = in_date_varied_batches(
        int(target * 0.10),
        lambda context_anchor, size, index: make_sums(context_anchor, titles, size, index),
    )
    previous_month_phrase_count = int(target * 0.03)
    chat = in_date_varied_batches(
        target
        - len(additions)
        - len(partial)
        - len(searches)
        - len(updates)
        - len(deletions)
        - len(reinforcement)
        - len(values)
        - len(sums)
        - previous_month_phrase_count,
        lambda context_anchor, size, index: make_chat(context_anchor, size, index),
    )
    previous_month_phrases = in_date_varied_batches(
        previous_month_phrase_count,
        lambda context_anchor, size, index: make_previous_month_phrase_searches(context_anchor, titles, size, index),
    )
    result = (
        additions
        + partial
        + searches
        + updates
        + deletions
        + reinforcement
        + values
        + sums
        + chat
        + previous_month_phrases
    )
    rng.shuffle(result)
    return result


def validate(rows: list[dict]) -> None:
    allowed = {"chat", "calendar_add", "calendar_search", "calendar_update", "calendar_delete", "calendar_sum"}
    non_calendar_chat_signatures = set()
    for row in rows:
        messages = row["messages"]
        assert len(messages) in {3, 4}
        assert messages[0]["role"] == "system"
        assert all(message["role"] == "user" for message in messages[1:-1])
        assert messages[-1]["role"] == "assistant"
        assert all(CLOCK_RE.search(message["content"]) is None for message in messages[1:-1])
        signature = json.dumps(messages[1:], ensure_ascii=False, sort_keys=True)
        if row["category"] in {"ordinary_chat", "calendar_search_without_period"}:
            if signature in non_calendar_chat_signatures:
                raise AssertionError(f"Duplicate chat example in {row['category']}: {messages[1]['content']}")
            non_calendar_chat_signatures.add(signature)
        parsed = parse_and_validate_assistant_response(
            messages[-1]["content"],
            f"generated[{row['category']}].assistant",
        )
        assert set(parsed) == {"intent", "reply", "params"}
        assert parsed["intent"] in allowed and parsed["reply"]
        if parsed["intent"] == "chat": assert parsed["params"] == {}
        if parsed["intent"] == "calendar_search":
            params = parsed["params"]
            assert set(params).issubset({"query", "range_start", "range_end"})
            assert ("range_start" in params) == ("range_end" in params)
            assert "range_start" not in params or params["range_start"] < params["range_end"]
        if parsed["intent"] == "calendar_add":
            assert set(parsed["params"]).issubset({"title", "starts_at", "date", "time", "duration_min", "value"})
            assert "starts_at" in parsed["params"] or "date" in parsed["params"]
            if "duration_min" in parsed["params"]: assert parsed["params"]["duration_min"] > 0
            if "value" in parsed["params"]: assert type(parsed["params"]["value"]) is int
        if parsed["intent"] == "calendar_update":
            assert set(parsed["params"]) == {"target", "changes"}
            target = parsed["params"]["target"]
            changes = parsed["params"]["changes"]
            assert set(target).issubset({"query", "range_start", "range_end", "use_last_created"})
            assert set(changes).issubset({"title", "date", "time", "duration_min", "value", "clear_value"})
            assert all(value is not None for value in target.values())
            assert all(value is not None for value in changes.values())
            assert ("range_start" in target) == ("range_end" in target)
            assert "range_start" not in target or "query" in target
            assert not ("query" in target and target.get("use_last_created") is True)
            assert "duration_min" not in changes or changes["duration_min"] > 0
            assert "value" not in changes or type(changes["value"]) is int
            assert "clear_value" not in changes or changes["clear_value"] is True
            assert not ("value" in changes and "clear_value" in changes)
        if parsed["intent"] == "calendar_delete":
            assert set(parsed["params"]) == {"target"}
            target = parsed["params"]["target"]
            assert set(target).issubset({"query", "range_start", "range_end", "use_last_created", "use_last_in_range"})
            assert ("range_start" in target) == ("range_end" in target)
            assert "range_start" not in target or "query" in target or target.get("use_last_in_range") is True
            assert sum(("query" in target, target.get("use_last_created") is True, target.get("use_last_in_range") is True)) == 1
            assert "use_last_in_range" not in target or ("range_start" in target and "range_end" in target)
            assert parsed["reply"].startswith("Событие удалено:")
        if parsed["intent"] == "calendar_sum":
            params = parsed["params"]
            assert set(params).issubset({"query", "range_start", "range_end"})
            assert ("range_start" in params) == ("range_end" in params)
            assert "range_start" not in params or params["range_start"] < params["range_end"]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows), encoding="utf-8")


def main() -> None:
    # Keep generated temporal contexts outside the hand-authored seed contexts.
    # This guarantees that an identical system-and-user prompt cannot leak from
    # a reviewed seed into a synthetic candidate split.
    train = build(datetime(2030, 8, 24, 14, 30), TRAIN_TITLES, 1200, 20300824, 0)
    evaluation = build(datetime(2036, 2, 26, 9, 15), EVAL_TITLES, 300, 20360226, 48)
    validate(train)
    validate(evaluation)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_jsonl(OUTPUT_DIR / "calendar_assistant_train_candidates.jsonl", train)
    write_jsonl(OUTPUT_DIR / "calendar_assistant_eval_candidates.jsonl", evaluation)
    print(f"Wrote {len(train)} training and {len(evaluation)} evaluation candidates to {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
