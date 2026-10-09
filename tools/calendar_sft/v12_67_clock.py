"""Pure Russian clock checks retained for V12.67; never author training text."""
from datetime import datetime, timedelta
import re

CARDINAL = ("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
NUMBERS = {word: n for n, word in enumerate(CARDINAL)}
NUMBERS.update({"час": 1, "одна": 1, "одну": 1, "две": 2, "двадцать": 20, "тридцать": 30, "сорок": 40, "пятьдесят": 50})
GENITIVE = {"одной": 1, "двух": 2, "трех": 3, "четырех": 4, "пяти": 5, "шести": 6, "семи": 7, "восьми": 8, "девяти": 9, "десяти": 10, "одиннадцати": 11, "двенадцати": 12, "тринадцати": 13, "четырнадцати": 14, "пятнадцати": 15, "шестнадцати": 16, "семнадцати": 17, "восемнадцати": 18, "девятнадцати": 19, "двадцати": 20}
ORDINAL = {word: n for n, word in enumerate(("первого", "второго", "третьего", "четвертого", "пятого", "шестого", "седьмого", "восьмого", "девятого", "десятого", "одиннадцатого", "двенадцатого"), 1)}


def norm(value):
    return re.sub(r"\s+", " ", value.lower().replace("ё", "е")).strip()

def number(words, mapping=NUMBERS):
    words = words.split() if isinstance(words, str) else words
    if len(words) == 1 and words[0] in mapping:
        return mapping[words[0]]
    if len(words) == 2 and mapping.get(words[0], -1) >= 20 and 1 <= mapping.get(words[1], -1) <= 9:
        return mapping[words[0]] + mapping[words[1]]
    raise ValueError(f"unrecognized number: {words}")

ORD = "(?:" + "|".join(ORDINAL) + ")"
CARD = "(?:" + "|".join(sorted(NUMBERS, key=len, reverse=True)) + ")"
GEN = "(?:" + "|".join(sorted(GENITIVE, key=len, reverse=True)) + ")"
CARD_NUM = CARD + "(?: " + CARD + ")?"
GEN_NUM = GEN + "(?: " + GEN + ")?"
PATTERN = re.compile(
    r"\b(?P<before>без (?:(?P<quarter_to>четверти)|(?P<remaining>" + GEN_NUM + r")(?P<remaining_unit> минуты| минут)?) (?P<next>" + CARD_NUM + r"))\b"
    r"|\b(?P<special>(?P<quarter_half>четверть|половине) (?P<special_hour>" + ORD + r"))\b"
    r"|\b(?P<elapsed>(?P<minutes>" + CARD_NUM + r") (?P<minute_unit>минуту|минуты|минут) (?P<ordinal>" + ORD + r"))\b"
    r"|\b(?P<whole>(?:в|с|до) (?P<hour>" + CARD_NUM + r") (?P<hour_unit>часов|часа|час))\b"
    r"|\b(?P<one>(?:в|с|до) час)\b"
)




def scan(text):
    text = norm(text)
    found = []
    for m in PATTERN.finditer(text):
        canonical, problem = True, None
        kind = next(k for k in ("before", "special", "elapsed", "whole", "one") if m[k])
        if kind == "before":
            hour = (number(m["next"]) - 1) % 12
            remain = 15 if m["quarter_to"] else number(m["remaining"], GENITIVE)
            minute = 60 - remain
            prefix = text[:m.start()].rstrip()
            if re.search(r"\b(?:в|с|до)$", prefix):
                canonical, problem = False, "preposition_before_without"
            if not 1 <= remain <= 29 or not 1 <= number(m["next"]) <= 12:
                canonical, problem = False, "minutes_to_range"
            if not m["quarter_to"]:
                required_unit = None if remain == 10 else " минуты" if remain in (1, 21) else " минут"
                if m["remaining_unit"] != required_unit or remain == 15:
                    canonical, problem = False, "minutes_to_form_or_inflection"
                if m["next"] == "один":
                    canonical, problem = False, "one_hour_must_be_chas"
        elif kind == "special":
            hour = (ORDINAL[m["special_hour"]] - 1) % 12
            minute = 15 if m["quarter_half"] == "четверть" else 30
        elif kind == "elapsed":
            hour = (ORDINAL[m["ordinal"]] - 1) % 12
            minute = number(m["minutes"])
            unit = "минуту" if minute in (1, 21) else "минуты" if minute in (2, 3, 4, 22, 23, 24) else "минут"
            if not 1 <= minute <= 29 or minute == 15 or m["minute_unit"] != unit:
                canonical, problem = False, "elapsed_form_or_inflection"
            if minute in (1, 21) and not m["minutes"].endswith("одну"):
                canonical, problem = False, "elapsed_feminine_accusative"
            if minute in (2, 22) and not m["minutes"].endswith("две"):
                canonical, problem = False, "elapsed_feminine_two"
        else:
            raw_hour = 1 if kind == "one" else number(m["hour"])
            hour, minute = raw_hour % 12, 0
            if kind == "whole":
                expected_unit = "час" if raw_hour % 10 == 1 and raw_hour != 11 else "часа" if raw_hour % 10 in (2, 3, 4) and not 12 <= raw_hour <= 14 else "часов"
                if not 0 <= raw_hour <= 23 or m["hour_unit"] != expected_unit:
                    canonical, problem = False, "whole_hour_inflection"
        found.append({"phrase": m[0], "hour_mod_12": hour, "minute": minute,
                      "canonical": canonical, "problem": problem, "start": m.start(), "end": m.end()})
    return found

FULL = re.compile(
    r"\b(?:в|с|до) (?P<h>" + CARD_NUM + r") (?:часов|часа|час) "
    r"(?P<m>" + CARD_NUM + r") (?:минуту|минуты|минут)(?!\w)"
)
DIGITAL = re.compile(r"(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)")
WHOLE = re.compile(r"(?:в|с|до) (.+?) (?:часов|часа|час)$")




def expected_clocks(expected):
    if expected.get("intent") == "calendar_add":
        params = expected.get("params", {})
        # starts_at and time are alternative representations of the same start.
        start = params.get("starts_at", params.get("time"))
        return ([start[-5:]] if start else []) + (
            [params["ends_at"][-5:]] if params.get("ends_at") else []
        )
    return []

def clock_mentions(text):
    """Read full clock phrases before shorter overlapping whole-hour phrases."""
    text = norm(text)
    found = []
    for match in FULL.finditer(text):
        h, m = number(match["h"]), number(match["m"])
        if 0 <= h <= 23 and 0 <= m <= 59:
            found.append(dict(start=match.start(), end=match.end(), phrase=match[0],
                              hour=h, minute=m, ambiguous=h in range(1, 12), canonical=False))
    for match in DIGITAL.finditer(text):
        found.append(dict(start=match.start(), end=match.end(), phrase=match[0],
                          hour=int(match[1]), minute=int(match[2]), ambiguous=False, canonical=False))
    for clock in scan(text):
        if any(clock["start"] < x["end"] and x["start"] < clock["end"] for x in found):
            continue
        h = clock["hour_mod_12"]
        whole = WHOLE.fullmatch(clock["phrase"])
        if whole:
            h = number(whole[1])
        # Named 00/12/13..23 hours are explicit; unqualified 1..11 and relative
        # idioms use the twelve-hour circle allowed in the existing reply corpus.
        found.append(dict(start=clock["start"], end=clock["end"], phrase=clock["phrase"],
                          hour=h, minute=clock["minute"],
                          ambiguous=not whole or 1 <= h <= 11, canonical=clock["canonical"]))
    for clock in found:
        part = re.match(r"\s+(утра|дня|вечера|ночи)\b", text[clock["end"]:])
        if part:
            h = clock["hour"] % 12
            if part[1] in ("дня", "вечера"):
                h += 12
            elif part[1] == "ночи" and h >= 6:
                h += 12
            clock.update(hour=h, ambiguous=False, canonical=False)
    return sorted(found, key=lambda x: x["start"])

def without_title(reply, expected):
    params = expected.get("params", {})
    titles = [params.get("title")]
    for title in titles:
        if title:
            reply = re.sub(re.escape(title), "EVENT", reply, count=1, flags=re.I)
    return reply

def compare_clock_reply(reply, expected, clocks):
    mentions = clock_mentions(without_title(reply, expected))
    if not clocks:
        if expected.get("intent") == "calendar_add":
            return (False, False, mentions) if mentions else (None, None, mentions)
        return None, None, mentions
    params = expected.get("params", {})
    if (len(clocks) == 1 and len(mentions) == 2 and params.get("starts_at")
            and isinstance(params.get("duration_min"), int)):
        end = datetime.fromisoformat(params["starts_at"]) + timedelta(minutes=params["duration_min"])
        clocks = [*clocks, end.strftime("%H:%M")]
    meaning = len(mentions) == len(clocks)
    for actual, target in zip(mentions, clocks):
        h, m = map(int, target.split(":"))
        hour_ok = actual["hour"] % 12 == h % 12 if actual["ambiguous"] else actual["hour"] == h
        meaning = meaning and hour_ok and actual["minute"] == m
    style = bool(mentions) and len(mentions) == len(clocks) and all(x["canonical"] for x in mentions)
    return bool(meaning), bool(style), mentions
