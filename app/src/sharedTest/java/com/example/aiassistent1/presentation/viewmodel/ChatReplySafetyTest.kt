package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.parser.AssistantResponseParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** The same regression cases run on JVM and on the physical Android device. */
class ChatReplySafetyTest {
    private val parser = AssistantResponseParser()

    @Test fun damagedAndArbitraryEnvelopesKeepTextAtEveryChunkSize() = runBlocking<Unit> {
        val text = "Король ходит на одну клетку."
        val cases = linkedMapOf(
            text to text,
            "{\"unknown\":\"$text\"}" to text,
            "{'любой ключ':'$text'}" to text,
            "{\"outer\":{\"unknown\":\"$text\"}}" to text,
            "[\"$text\"]" to text,
            "\"$text" to text,
            "{ответ: \"$text" to text,
            "{\"ответ\": $text" to text,
            "{\"ответ: $text" to text,
            "{$text" to text,
            "[$text" to text,
            "\"ответ\": \"$text" to text,
            "ответ: $text" to text,
            "answer = '$text" to text,
            "```json\n{\"unknown\":\"$text" to text,
            "```text\n$text\n```" to text,
            "<unknown>$text</unknown>" to text,
            "{\"one\":\"Первый\" \"two\":\"Второй\"" to "Первый\nВторой",
            "{\"one\":\"Первый\" \"reply\":\"Главный\"" to "Главный",
            "{\"response\":\"Главный\" \"emote\":\"✿✿✿✿✿" to "Главный",
            "{\"emoji\":\"Текст в любом поле\"" to "Текст в любом поле",
        )
        for ((raw, expected) in cases) {
            for (chunk in listOf(1, 2, 7, raw.length)) {
                val output = ChatReplyTextStream(parser).snapshots(raw.chunked(chunk).asFlow()) {
                    error("Readable text must not be retried: $raw")
                }.toList()
                assertEquals("chunk=$chunk raw=$raw", expected, output.last())
            }
        }
    }

    @Test fun partialValueIsVisibleBeforeClosingQuoteOrCompletion() = runBlocking<Unit> {
        val chunks = Channel<String>(Channel.UNLIMITED)
        val first = CompletableDeferred<String>()
        var last = ""
        val job = launch {
            ChatReplyTextStream(parser).snapshots(chunks.receiveAsFlow()).collect {
                last = it
                first.complete(it)
            }
        }
        chunks.send("{\"unknown\":\"Готовый текст")
        assertEquals("Готовый текст", withTimeout(3_000) { first.await() })
        assertTrue(job.isActive)
        chunks.send("\" \"reply\":\"Предпочтительный текст\"")
        chunks.close()
        job.join()
        assertEquals("Предпочтительный текст", last)
    }

    @Test fun fifthHiddenRepeatStopsAndJoinsProducerWithoutRetry() = runBlocking<Unit> {
        var symbols = 0
        var stops = 0
        var producerFinished = false
        val source = flow {
            try {
                emit("{\"response\":\"Готовый ответ\",\"emote\":\"")
                repeat(100) { symbols++; emit("✿") }
                fail("The sixth repeat must never be requested")
            } finally { producerFinished = true }
        }
        val result = ChatReplyTextStream(parser).snapshots(source, onRepetition = { stops++ }) {
            error("Repetition must not retry")
        }.toList()
        assertEquals("Готовый ответ", result.last())
        assertEquals(5, symbols)
        assertEquals(1, stops)
        assertTrue(producerFinished)
        assertTrue(result.none { it.contains("emote") || it.contains('✿') })
    }

    @Test fun singleChunkAndSplitUnicodeStopExactlyOnFifthOccurrence() = runBlocking<Unit> {
        for (unit in listOf("✿", "😀", "аб", "повтор фразы. ")) {
            for (chunk in listOf(1, 2, 1_000)) {
                var stopped = 0
                val raw = unit.repeat(9) + "Нельзя выводить"
                val result = ChatReplyTextStream(parser).snapshots(raw.chunked(chunk).asFlow(),
                    onRepetition = { stopped++ }).toList()
                assertEquals(unit.repeat(5), result.last())
                assertEquals(1, stopped)
            }
        }
    }

    @Test fun escapedMixedUnicodeAlsoCountsAsTheSameSymbol() {
        val guard = ChatRepetitionGuard()
        val raw = "✿\\u273f✿\\u273f✿EXTRA"
        val consumed = guard.accept(raw)
        assertTrue(guard.stopped)
        assertEquals("✿\\u273f✿\\u273f✿", raw.take(consumed))
        val emoji = ChatRepetitionGuard()
        val escaped = "\\uD83D\\uDE00".repeat(8)
        assertEquals("\\uD83D\\uDE00".repeat(5).length, emoji.accept(escaped))
        assertTrue(emoji.stopped)
    }

    @Test fun fourRepeatsDistinctListItemsAndFormattingDoNotStop() {
        for (raw in listOf("✿".repeat(4), "да ".repeat(4), "1. Первый\n2. Второй\n3. Третий\n4. Четвёртый\n5. Пятый",
            "     ", "\\u0020".repeat(8), "{[{}]}".repeat(8))) {
            val guard = ChatRepetitionGuard()
            assertEquals(raw, raw.length, guard.accept(raw))
            assertFalse(raw, guard.stopped)
        }
    }

    @Test fun repetitionWithNoReadableContentDoesNotStartSecondRequest() = runBlocking<Unit> {
        var retry = false
        val result = ChatReplyTextStream(parser).snapshots(flowOf("{\"role\":\"assistant\",\"emote\":\"✿✿✿✿✿")) {
            retry = true
            flowOf("Повтор")
        }.toList()
        assertFalse(retry)
        assertEquals(ChatReplyTextStream.REPETITION_STOP, result.last())
    }

    @Test fun partialEscapesNeverDisplayHalfAnEmojiAndProseBracesRemainLiteral() = runBlocking<Unit> {
        val raw = "{\"anything\":\"Скобки {так} и \\\"кавычки\\\" \\uD83D\\uDE00\"}"
        val snapshots = ChatReplyTextStream(parser).snapshots(raw.map(Char::toString).asFlow()).toList()
        assertEquals("Скобки {так} и \"кавычки\" 😀", snapshots.last())
        assertTrue(snapshots.none { it.lastOrNull()?.isHighSurrogate() == true })
    }

    @Test fun malformedCalendarExecutionRemainsRejected() {
        val raw = "{\"intent\":\"calendar_add\" \"reply\":\"Готово\" \"params\":{}}"
        assertTrue(parser.parseChatReply(raw).isSuccess)
        assertTrue(parser.parseResult(raw).isFailure)
    }
}
