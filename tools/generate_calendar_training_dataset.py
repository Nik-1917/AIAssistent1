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
MINUTES = {0: "", 5: " пять минут", 15: " пятнадцать минут", 20: " двадцать минут", 30: " тридцать минут", 45: " сорок пять минут"}
TIMES = (time(8, 0), time(9, 30), time(10, 15), time(11, 20), time(12, 0), time(14, 0), time(15, 45), time(18, 30), time(19, 0))
DURATIONS = (15, 30, 45, 60, 90, 120)

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
        target = add_months(today, 1)
        return "через месяц", target, target + timedelta(days=1)
    if kind == "in_two_months":
        target = add_months(today, 2)
        return "через два месяца", target, target + timedelta(days=1)
    if kind == "this_week":
        start = today - timedelta(days=today.weekday())
        return "на этой неделе", start, start + timedelta(days=7)
    if kind == "next_week":
        start = today - timedelta(days=today.weekday()) + timedelta(days=7)
        return "на следующей неделе", start, start + timedelta(days=7)
    start = today + timedelta(days=explicit_offset)
    return date_words(start), start, start + timedelta(days=1)


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
            "Добавь {date} {time} событие «{title}» на {duration}.",
            "Запиши событие «{title}» {date} в {numeric} на {duration}.",
            "Поставь {date} {time} «{title}» на {duration}.",
        )
        user = user_templates[index % len(user_templates)].format(date=date_phrase, time=time_words(event_time), numeric=event_time.strftime("%H:%M"), title=title, duration=duration_words(duration))
        reply = f"Событие создано: {title} {reply_phrase or reply_date_phrase(event_date, anchor.date())} {time_words(event_time)}."
        result.append(record("calendar_add_complete", system_message(anchor), user, response("calendar_add", reply, {"title": title, "starts_at": local_stamp(start), "duration_min": duration})))
    return result


def make_partial_additions(anchor: datetime, titles: tuple[str, ...], total: int, start_index: int = 0) -> list[dict]:
    result = []
    for index in range(start_index, start_index + total):
        title = titles[index % len(titles)]
        event_date = anchor.date() + timedelta(days=(index % 7) + 1)
        event_time = TIMES[index % len(TIMES)]
        duration = DURATIONS[index % len(DURATIONS)]
        variant = index % 4
        if variant == 0:
            user, params, reply = f"Запиши событие «{title}» {date_words(event_date)} {time_words(event_time)}.", {"title": title, "starts_at": local_stamp(datetime.combine(event_date, event_time))}, f"Уточню длительность для события «{title}»."
        elif variant == 1:
            user, params, reply = f"Добавь {date_words(event_date)} событие «{title}» на {duration_words(duration)}.", {"title": title, "duration_min": duration}, f"Уточню точное время для события «{title}»."
        elif variant == 2:
            user, params, reply = f"Поставь {date_words(event_date)} в {event_time:%H:%M} на {duration_words(duration)}.", {"starts_at": local_stamp(datetime.combine(event_date, event_time)), "duration_min": duration}, "Уточню название события."
        else:
            title_only_templates = (
                "Нужно не забыть: «{title}».",
                "Добавь в календарь событие «{title}».",
                "Хочу запланировать «{title}».",
                "Запиши мне событие «{title}».",
                "Надо внести в календарь «{title}».",
            )
            template = title_only_templates[(index // (len(titles) * 4)) % len(title_only_templates)]
            user, params, reply = template.format(title=title), {"title": title}, f"Уточню дату, время и длительность для события «{title}»."
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
        phrase, start, end = period(anchor, kind, explicit_offset=(index % 20) + 1)
        title = titles[index % len(titles)]
        query = "" if index % 3 == 0 else title
        if query:
            user_templates = (
                "Найди событие «{title}» {period}.",
                "Проверь, есть ли «{title}» {period}.",
                "Покажи «{title}» {period}.",
            )
            user = user_templates[(index // len(kinds)) % len(user_templates)].format(period=phrase, title=title)
            reply = f"Проверяю событие «{title}» {phrase}."
        else:
            user_templates = (
                "Что у меня {period}?",
                "Покажи все планы {period}.",
                "Какие события у меня {period}?",
            )
            user = user_templates[(index // len(kinds)) % len(user_templates)].format(period=phrase)
            reply = f"Проверяю все события {phrase}."
        params = {"query": query, "range_start": f"{start:%Y-%m-%d}T00:00", "range_end": f"{end:%Y-%m-%d}T00:00"}
        result.append(record("calendar_search", system_message(anchor), user, response("calendar_search", reply, params)))
    return result


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
        "У события «{title}» время исправь на {time}.",
        "Поставь для события «{title}» время на {time}.",
        "Передвинь событие «{title}» по времени на {time}.",
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
                "Перенеси событие «{title}», запланированное {source}, {destination}.",
                "Сдвинь событие «{title}», которое стоит {source}, {destination}.",
                "Перепиши в календаре событие «{title}», запланированное {source}, {destination}.",
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
            reply = f"Событие изменено: «{title}» перенесено {move_request_phrase(destination_phrase)}."
            category = "calendar_update_move_from_source_period"
        elif variant == 1:
            user = move_templates[index % len(move_templates)].format(
                title_lower=f"событие «{title}»",
                destination=move_request_phrase(destination_phrase),
            )
            target = {"query": query}
            changes = {"date": f"{destination_date:%Y-%m-%d}"}
            reply = f"Событие изменено: «{title}» перенесено {move_request_phrase(destination_phrase)}."
            category = "calendar_update_move_by_query"
        elif variant == 2:
            user = time_templates[index % len(time_templates)].format(
                title=title,
                time=clock_words(new_time),
            )
            target = {"query": query}
            changes = {"time": new_time.strftime("%H:%M")}
            reply = f"Событие изменено: новое время для «{title}» — {time_words(new_time)}."
            category = "calendar_update_time_by_query"
        elif variant == 3:
            user = f"Измени длительность события «{title}» на {duration_words(new_duration)}."
            target = {"query": query}
            changes = {"duration_min": new_duration}
            reply = f"Событие изменено: длительность «{title}» — {duration_words(new_duration)}."
            category = "calendar_update_duration_by_query"
        elif variant == 4:
            user = f"Переименуй «{title}» в «{renamed_title}»."
            target = {"query": query}
            changes = {"title": renamed_title}
            reply = f"Событие изменено: новое название — «{renamed_title}»."
            category = "calendar_update_title_by_query"
        elif variant == 5:
            user = f"У последнего добавленного события время исправь на {clock_words(new_time)}."
            target = {"use_last_created": True}
            changes = {"time": new_time.strftime("%H:%M")}
            reply = f"Событие изменено: время последнего добавленного события — {time_words(new_time)}."
            category = "calendar_update_last_created_time"
        elif variant == 6:
            user = f"Время исправь на {clock_words(new_time)}."
            target = {"use_last_created": True}
            changes = {"time": new_time.strftime("%H:%M")}
            reply = f"Событие изменено: время последнего добавленного события — {time_words(new_time)}."
            category = "calendar_update_implicit_last_created"
        else:
            user = f"Измени событие «{title}»."
            target = {"query": query}
            changes = {}
            reply = f"Уточню, что изменить у события «{title}»."
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
        ("Привет.", "Здравствуйте. Чем могу помочь?"),
        ("Что такое JSON?", "JSON — текстовый формат для передачи структурированных данных."),
        ("Ты работаешь с моим календарём?", "Да, я могу подготовить создание события или поиск в локальном календаре приложения."),
        ("Как найти планы на неделю?", "Скажите, за какую неделю проверить события."),
        ("Спасибо.", "Пожалуйста."),
        ("Чем ты можешь помочь с календарём?", "Я могу подготовить событие или поискать события в локальном календаре."),
        ("Календарь работает без интернета?", "События хранятся в локальном календаре приложения."),
        ("Можно говорить обычными словами?", "Да, можно сформулировать запрос свободно, например назвать дату, время и событие."),
        ("Где сохраняются события?", "События сохраняются в локальном календаре приложения."),
        ("Покажи пример создания события.", "Например: добавь завтра в три часа дня встречу на час."),
        ("Можно искать события по названию?", "Да, назовите слово из названия и период поиска."),
        ("Ты знаешь текущее время?", "Я использую дату, время и часовой пояс, переданные приложением."),
        ("Как посмотреть планы на завтра?", "Скажите: покажи события на завтра."),
        ("Нужно ли указывать длительность?", "Для создания события нужна положительная длительность в минутах."),
        ("Что будет, если время неизвестно?", "Я попрошу уточнить точное время события."),
    )
    ambiguous = (
        "Найди встречу с Анной.",
        "Покажи мои тренировки.",
        "Есть ли запись к врачу?",
        "Когда у меня созвон с командой?",
        "Найди оплату интернета.",
        "Проверь, есть ли дела на даче.",
        "Покажи событие про МФЦ.",
        "Есть ли у меня семейный ужин?",
        "Найди занятия английским.",
        "Когда я забираю заказ из ПВЗ?",
        "Найди техосмотр автомобиля.",
        "Есть ли родительское собрание?",
        "Когда у меня визит к нотариусу?",
        "Покажи оплату мобильной связи.",
        "Есть ли встреча с классным руководителем?",
    )
    rows = []
    for index in range(start_index, start_index + total):
        item_index, kind = divmod(index, 2)
        if kind == 0:
            user, reply = ordinary[item_index % len(ordinary)]
            category = "ordinary_chat"
        else:
            user = ambiguous[item_index % len(ambiguous)]
            reply, category = "За какой период выполнить поиск?", "ambiguous_search"
        rows.append(record(category, system_message(anchor), user, response("chat", reply, {})))
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
        if index % 11 == 10:
            rows.append(
                record(
                    "calendar_delete_last_created",
                    system_message(anchor),
                    "Удали последнее добавленное событие.",
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
        int(target * 0.27),
        lambda context_anchor, size, index: make_additions(context_anchor, titles, rng, size, index),
    )
    partial = in_date_varied_batches(
        int(target * 0.16),
        lambda context_anchor, size, index: make_partial_additions(context_anchor, titles, size, index),
    )
    searches = in_date_varied_batches(
        int(target * 0.20),
        lambda context_anchor, size, index: make_searches(context_anchor, titles, size, index),
    )
    updates = in_date_varied_batches(
        int(target * 0.27),
        lambda context_anchor, size, index: make_updates(context_anchor, size, index),
    )
    deletions = in_date_varied_batches(
        int(target * 0.05),
        lambda context_anchor, size, index: make_deletions(context_anchor, size, index),
    )
    previous_month_phrase_count = int(target * 0.03)
    chat = in_date_varied_batches(
        target - len(additions) - len(partial) - len(searches) - len(updates) - len(deletions) - previous_month_phrase_count,
        lambda context_anchor, size, index: make_chat(context_anchor, size, index),
    )
    previous_month_phrases = in_date_varied_batches(
        previous_month_phrase_count,
        lambda context_anchor, size, index: make_previous_month_phrase_searches(context_anchor, titles, size, index),
    )
    result = additions + partial + searches + updates + deletions + chat + previous_month_phrases
    rng.shuffle(result)
    return result


def validate(rows: list[dict]) -> None:
    allowed = {"chat", "calendar_add", "calendar_search", "calendar_update", "calendar_delete"}
    non_calendar_chat_signatures = set()
    for row in rows:
        messages = row["messages"]
        assert len(messages) in {3, 4}
        assert messages[0]["role"] == "system"
        assert all(message["role"] == "user" for message in messages[1:-1])
        assert messages[-1]["role"] == "assistant"
        signature = json.dumps(messages[1:], ensure_ascii=False, sort_keys=True)
        if row["category"] in {"ordinary_chat", "ambiguous_search"}:
            if signature in non_calendar_chat_signatures:
                raise AssertionError(f"Duplicate chat example in {row['category']}: {messages[1]['content']}")
            non_calendar_chat_signatures.add(signature)
        parsed = json.loads(messages[-1]["content"])
        assert set(parsed) == {"intent", "reply", "params"}
        assert parsed["intent"] in allowed and parsed["reply"]
        if parsed["intent"] == "chat": assert parsed["params"] == {}
        if parsed["intent"] == "calendar_search":
            assert set(parsed["params"]) == {"query", "range_start", "range_end"}
            assert parsed["params"]["range_start"] < parsed["params"]["range_end"]
        if parsed["intent"] == "calendar_add":
            assert set(parsed["params"]).issubset({"title", "starts_at", "duration_min"})
            if "duration_min" in parsed["params"]: assert parsed["params"]["duration_min"] > 0
        if parsed["intent"] == "calendar_update":
            assert set(parsed["params"]) == {"target", "changes"}
            target = parsed["params"]["target"]
            changes = parsed["params"]["changes"]
            assert set(target).issubset({"query", "range_start", "range_end", "use_last_created"})
            assert set(changes).issubset({"title", "date", "time", "duration_min"})
            assert all(value is not None for value in target.values())
            assert all(value is not None for value in changes.values())
            assert ("range_start" in target) == ("range_end" in target)
            assert "range_start" not in target or "query" in target
            assert not ("query" in target and target.get("use_last_created") is True)
            assert "duration_min" not in changes or changes["duration_min"] > 0
        if parsed["intent"] == "calendar_delete":
            assert set(parsed["params"]) == {"target"}
            target = parsed["params"]["target"]
            assert set(target).issubset({"query", "range_start", "range_end", "use_last_created"})
            assert ("range_start" in target) == ("range_end" in target)
            assert "range_start" not in target or "query" in target
            assert ("query" in target) != (target.get("use_last_created") is True)
            assert parsed["reply"].startswith("Событие удалено:")


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
