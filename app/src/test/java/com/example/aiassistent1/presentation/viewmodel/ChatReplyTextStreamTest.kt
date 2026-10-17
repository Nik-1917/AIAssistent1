package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.parser.AssistantResponseParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatReplyTextStreamTest {
    private val parser = AssistantResponseParser()

    @Test fun `recovered text across arbitrary token boundaries never triggers retry`() = runTest {
        for (raw in listOf("{\"unknown\":{\"text\":\"Ответ\"}}", "[\"Ответ\"]", "\"Ответ\"",
            "[1,{\"text\":\"Ответ\"}]", "[false,\"Ответ\"]", "```json\n{\"answer\":\"Ответ\"}\n```", "Ответ")) {
            val actual = ChatReplyTextStream(parser).display(flowOf(*raw.map(Char::toString).toTypedArray())) {
                error("Readable response must not be generated again")
            }.toList().joinToString("")
            assertEquals(raw, "Ответ", actual)
        }
    }

    @Test fun `no text retries once then emits existing error with both raw diagnostics`() = runTest {
        val diagnostics = mutableListOf<String>()
        var retries = 0
        val stream = ChatReplyTextStream(parser) { raw, _ -> diagnostics += raw }
        val actual = stream.display(flowOf("{}")) { retries++; flowOf("{\"role\":\"assistant\"}") }.toList()
        assertEquals(1, retries)
        assertEquals(listOf("{}", "{\"role\":\"assistant\"}"), diagnostics)
        assertEquals(listOf("Не удалось разобрать ответ модели. Попробуйте изменить запрос."), actual)
    }

    @Test fun `empty and whitespace-only generations also retry`() = runTest {
        for (raw in listOf("", "  ", "\uFEFF")) {
            var retries = 0
            assertEquals(listOf("Ответ"), ChatReplyTextStream(parser).display(flowOf(raw)) {
                retries++; flowOf("Ответ")
            }.toList())
            assertEquals(1, retries)
        }
    }

    @Test fun `cancel during structured generation neither retries nor emits a fallback`() = runTest {
        var retries = 0
        val output = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            ChatReplyTextStream(parser).display(flow { emit("{\"answer\":"); started.complete(Unit); awaitCancellation() }) {
                retries++; flowOf("Не должно появиться")
            }.toList(output)
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(0, retries)
        assertTrue(output.isEmpty())
    }

    @Test fun `cancel during retry cancels its flow without a third attempt or error message`() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val output = mutableListOf<String>()
        val job = launch {
            ChatReplyTextStream(parser).display(flowOf("{}")) {
                flow {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { stopped.complete(Unit) }
                }
            }.toList(output)
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertTrue(output.isEmpty())
    }

    @Test fun `engine errors propagate without response retries`() = runTest {
        val failure = IllegalStateException("Ошибка модели")
        val result = runCatching {
            ChatReplyTextStream(parser).display(flow { throw failure }) { error("No retry") }.toList()
        }
        assertSame(failure, result.exceptionOrNull())
    }
}
