package com.example.aiassistent1.domain.formatter

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {
    @Test
    fun `normalizes russian integers fractions percentages and ranges`() {
        assertEquals(
            "минус двенадцать целых пять десятых процента, от три до пять.",
            SpeechTextNormalizer.normalize("-12,5%, от 3 до 5."),
        )
    }

    @Test
    fun `normalizes unambiguous dates`() {
        assertEquals(
            "Встреча двадцать первое августа две тысячи двадцать шестого года.",
            SpeechTextNormalizer.normalize("Встреча 2026-08-21."),
        )
        assertEquals(
            "Платёж пятое января две тысячи двадцать шестого года.",
            SpeechTextNormalizer.normalize("Платёж 05.01.2026."),
        )
    }

    @Test
    fun `expands abbreviations and uppercase abbreviations`() {
        assertEquals(
            "город Самара, улица Мира, дом семь. эм че эс, так далее",
            SpeechTextNormalizer.normalize("г. Самара, ул. Мира, д. 7. МЧС, т. д."),
        )
    }

    @Test
    fun `speaks markdown links and urls without losing their address`() {
        assertEquals(
            listOf(
                "Документация:",
                "ссылка: сайт.",
                "адрес: эйч ти ти пи эс двоеточие двойной слэш example точка com слэш v два " +
                    "вопросительный знак a равно один и b равно два",
            ),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("**Документация:** [сайт](https://example.com/v2?a=1&b=2)"),
            ),
        )
    }

    @Test
    fun `removes inline code from speech`() {
        assertEquals(
            "Используй",
            SpeechTextNormalizer.normalize("Используй `userId = getUser(2)`"),
        )
    }

    @Test
    fun `leaves invalid dates unchanged`() {
        assertEquals("Дата 31.02.2026", SpeechTextNormalizer.normalize("Дата 31.02.2026"))
    }

    @Test
    fun `does not use an empty line as a pause`() {
        assertEquals(
            "Первый абзац Второй абзац",
            SpeechTextNormalizer.normalize("Первый абзац\n\nВторой абзац"),
        )
    }

    @Test
    fun `keeps sentence punctuation unchanged`() {
        assertEquals(
            "Первый абзац. Второй абзац",
            SpeechTextNormalizer.normalize("Первый абзац. Второй абзац"),
        )
    }

    @Test
    fun `isolates an atx markdown heading from surrounding text`() {
        assertEquals(
            listOf("Введение", "Заголовок", "Описание."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Введение\n\n## Заголовок\nОписание."),
            ),
        )
    }

    @Test
    fun `keeps other sentence endings unchanged`() {
        assertEquals(
            "Готово! Продолжить? Да… Начинаем",
            SpeechTextNormalizer.normalize("Готово! Продолжить? Да… Начинаем"),
        )
    }

    @Test
    fun `does not add a pause after punctuation at the end of text`() {
        assertEquals("Готово.", SpeechTextNormalizer.normalize("Готово."))
    }

    @Test
    fun `removes markdown emphasis stars`() {
        assertEquals(
            "Перед важно после",
            SpeechTextNormalizer.normalize("Перед **важно** после"),
        )
    }

    @Test
    fun `removes a run of stars`() {
        assertEquals(
            "Первая часть вторая часть",
            SpeechTextNormalizer.normalize("Первая часть *** вторая часть"),
        )
    }

    @Test
    fun `removes starred list markers`() {
        assertEquals(
            "Первый пункт Второй пункт",
            SpeechTextNormalizer.normalize("* Первый пункт\n* Второй пункт"),
        )
    }

    @Test
    fun `does not pronounce operators inside inline code`() {
        assertEquals(
            "Формула",
            SpeechTextNormalizer.normalize("Формула `a * b`"),
        )
    }

    @Test
    fun `removes numbered list markers and keeps item boundaries`() {
        assertEquals(
            "Первый пункт. Второй пункт. Третий пункт",
            SpeechTextNormalizer.normalize("1. Первый пункт\n2) Второй пункт\n3 — Третий пункт"),
        )
    }

    @Test
    fun `keeps numbers inside ordinary text`() {
        assertEquals(
            "Купи два яблока, версия две тысячи двадцать шесть",
            SpeechTextNormalizer.normalize("Купи 2 яблока, версия 2026"),
        )
    }

    @Test
    fun `removes a number together with inline code`() {
        assertEquals(
            "Значение",
            SpeechTextNormalizer.normalize("Значение `version = 2`"),
        )
    }

    @Test
    fun `isolates headings wrapped in one two or three stars`() {
        listOf("* Один *", "** Два **", "*** Три ***").forEach { heading ->
            assertEquals(
                listOf(heading.filter(Char::isLetter), "Описание."),
                SpeechTextChunker.split(SpeechTextNormalizer.normalize("$heading\nОписание.")),
            )
        }
    }

    @Test
    fun `isolates headings with one two or three hashes`() {
        listOf("# Один #", "## Два ##", "### Три ###").forEach { heading ->
            assertEquals(
                listOf(heading.filter(Char::isLetter), "Описание."),
                SpeechTextChunker.split(SpeechTextNormalizer.normalize("$heading\nОписание.")),
            )
        }
    }

    @Test
    fun `isolates compact starred headings`() {
        assertEquals(
            listOf("Заголовок", "Описание."),
            SpeechTextChunker.split(SpeechTextNormalizer.normalize("**Заголовок**\nОписание.")),
        )
    }

    @Test
    fun `isolates a numbered starred heading from text on the same line`() {
        assertEquals(
            listOf(
                "Параметры модели",
                "(Hyperparameters):",
                "В дополнение к вышеуказанным, параметры модели влияют на производительность.",
            ),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize(
                    "7. **Параметры модели (Hyperparameters):** В дополнение к вышеуказанным, " +
                        "параметры модели влияют на производительность.",
                ),
            ),
        )
    }

    @Test
    fun `isolates leading headings with one two or three stars before body text`() {
        listOf(
            "1. *Один* Описание." to "Один",
            "2) **Два** Описание." to "Два",
            "3 — ***Три*** Описание." to "Три",
        ).forEach { (source, title) ->
            assertEquals(
                listOf(title, "Описание."),
                SpeechTextChunker.split(SpeechTextNormalizer.normalize(source)),
            )
        }
    }

    @Test
    fun `isolates starred headings without whitespace after closing stars`() {
        listOf(
            "*Один*Описание." to listOf("Один", "Описание."),
            "**Два**Описание." to listOf("Два", "Описание."),
            "***Три***Описание." to listOf("Три", "Описание."),
            "7. **Заголовок:**Продолжение." to listOf("Заголовок:", "Продолжение."),
        ).forEach { (source, expected) ->
            assertEquals(
                expected,
                SpeechTextChunker.split(SpeechTextNormalizer.normalize(source)),
            )
        }
    }

    @Test
    fun `isolates a setext markdown heading`() {
        assertEquals(
            listOf("Заголовок", "Описание."),
            SpeechTextChunker.split(SpeechTextNormalizer.normalize("Заголовок\n---\nОписание.")),
        )
    }

    @Test
    fun `does not isolate inline emphasis as a heading`() {
        assertEquals(
            listOf("Перед важно после."),
            SpeechTextChunker.split(SpeechTextNormalizer.normalize("Перед **важно** после.")),
        )
    }

    @Test
    fun `does not treat list marker or code as a heading`() {
        assertEquals(
            listOf("Пункт списка"),
            SpeechTextChunker.split(SpeechTextNormalizer.normalize("* Пункт списка\n`# заголовок`")),
        )
    }

    @Test
    fun `groups adjacent english words in ordinary text`() {
        assertEquals(
            listOf("Это", "simple English", "текст."),
            SpeechTextChunker.split(SpeechTextNormalizer.normalize("Это simple English текст.")),
        )
    }

    @Test
    fun `separates english phrases at punctuation and russian words`() {
        assertEquals(
            listOf("Это", "machine learning,", "затем", "neural network", "пример."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Это machine learning, затем neural network пример."),
            ),
        )
    }

    @Test
    fun `keeps punctuation attached to an isolated english word`() {
        assertEquals(
            listOf("Параметры", "(Hyperparameters):", "описание."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Параметры (Hyperparameters): описание."),
            ),
        )
    }

    @Test
    fun `isolates english words in markdown link title but not in url`() {
        assertEquals(
            listOf(
                "ссылка:",
                "Open docs.",
                "адрес: эйч ти ти пи эс двоеточие двойной слэш example точка com слэш guide",
            ),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("[Open docs](https://example.com/guide)"),
            ),
        )
    }

    @Test
    fun `does not split english identifiers inside code`() {
        assertEquals(
            "",
            SpeechTextNormalizer.normalize("`getUserName()`"),
        )
    }

    @Test
    fun `removes fenced backtick and tilde code blocks`() {
        assertEquals(
            "До После",
            SpeechTextNormalizer.normalize(
                "До\n```kotlin\nval value = 10\n```\n~~~json\n{ \"key\": 1 }\n~~~\nПосле",
            ),
        )
    }

    @Test
    fun `removes balanced and nested curly blocks`() {
        assertEquals(
            "До после",
            SpeechTextNormalizer.normalize("До { внешний { вложенный } блок } после"),
        )
    }

    @Test
    fun `leaves unmatched curly brace text unchanged`() {
        assertEquals(
            "До { незакрытый блок после",
            SpeechTextNormalizer.normalize("До { незакрытый блок после"),
        )
    }

    @Test
    fun `returns blank when message contains only excluded code`() {
        assertEquals("", SpeechTextNormalizer.normalize("```kotlin\nval value = 10\n```"))
        assertEquals("", SpeechTextNormalizer.normalize("{ \"key\": \"value\" }"))
    }

    @Test
    fun `isolates russian text inside parentheses`() {
        assertEquals(
            listOf("Основной текст", "(важное уточнение)", "продолжение."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Основной текст (важное уточнение) продолжение."),
            ),
        )
    }

    @Test
    fun `keeps a nested parenthetical block together`() {
        assertEquals(
            listOf("Текст", "(параметр (расширенный режим))", "продолжение."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Текст (параметр (расширенный режим)) продолжение."),
            ),
        )
    }

    @Test
    fun `keeps an english phrase together inside parentheses`() {
        assertEquals(
            listOf("Метод", "(machine learning),", "работает."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Метод (machine learning), работает."),
            ),
        )
    }

    @Test
    fun `isolates multiple parenthetical blocks`() {
        assertEquals(
            listOf("Текст", "(первое)", "между", "(второе)", "дальше."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Текст (первое) между (второе) дальше."),
            ),
        )
    }

    @Test
    fun `leaves unmatched parentheses in surrounding text`() {
        assertEquals(
            listOf("Текст (незакрытое продолжение."),
            SpeechTextChunker.split(
                SpeechTextNormalizer.normalize("Текст (незакрытое продолжение."),
            ),
        )
    }
}
