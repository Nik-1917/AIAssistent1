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
            "Документация: ссылка: сайт. адрес: эйч ти ти пи эс двоеточие двойной слэш example точка com слэш v два вопросительный знак a равно один и b равно два",
            SpeechTextNormalizer.normalize("**Документация:** [сайт](https://example.com/v2?a=1&b=2)"),
        )
    }

    @Test
    fun `speaks code separately and keeps its tokens`() {
        assertEquals(
            "Используй код: user Id равно get User открывающая скобка два закрывающая скобка",
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
    fun `keeps stars inside inline code as operators`() {
        assertEquals(
            "Формула код: a звёздочка b",
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
    fun `keeps a number inside code`() {
        assertEquals(
            "Значение код: version равно два",
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
            listOf("Пункт списка код: # заголовок"),
            SpeechTextChunker.split(SpeechTextNormalizer.normalize("* Пункт списка\n`# заголовок`")),
        )
    }
}
