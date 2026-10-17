package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatResponseWriterTest {
    @Test
    fun `actual device response format displays and persists only text across token boundaries`() = runTest {
        val raw = """{"response":"Привет! Сегодня четверг, 15 октября 2026 года.","emoji":"✨"}"""
        val expected = "Привет! Сегодня четверг, 15 октября 2026 года."
        for (source in listOf(raw, "```json\n$raw\n```")) {
            val session = Session()
            val deltas = Channel<String>(Channel.UNLIMITED)
            val generation = launch {
                session.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(deltas.receiveAsFlow()))
            }
            for (character in source) deltas.send(character.toString())
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(expected, session.visible.content)
            assertEquals(listOf(expected), session.writes.map { it.content }.distinct())
            deltas.close()
            generation.join()
            assertEquals(expected, session.visible.content)
            assertEquals(listOf(expected), session.writes.map { it.content }.distinct())
        }
    }

    @Test
    fun `chat JSON is never shown or checkpointed and only reply is saved`() = runTest {
        val session = Session()
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch {
            session.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(deltas.receiveAsFlow()))
        }
        deltas.send("{\"intent\":\"chat\",\"reply\":\"Привет")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Привет", session.visible.content)
        assertEquals(listOf("Привет"), session.writes.map { it.content })

        deltas.send("!\",\"params\":{}}")
        deltas.close()
        generation.join()
        assertEquals("Привет!", session.visible.content)
        assertEquals(listOf("Привет", "Привет!"), session.writes.map { it.content })
    }

    @Test
    fun `plain chat text still streams immediately and fenced JSON shows only reply`() = runTest {
        val plain = Session()
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch {
            plain.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(deltas.receiveAsFlow()))
        }
        deltas.send("[1] Привет")
        runCurrent()
        assertEquals("[1] Привет", plain.visible.content)
        deltas.send("! (текст)")
        deltas.close()
        generation.join()
        assertEquals("[1] Привет! (текст)", plain.writes.single().content)

        val fenced = Session()
        fenced.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(flowOf(
            "```json\n{\"intent\":\"chat\",\"reply\":\"Готово.\",\"params\":{}}\n```",
        )))
        assertEquals("Готово.", fenced.writes.single().content)
    }

    @Test
    fun `chat metadata does not replace a readable model reply with an error`() = runTest {
        val session = Session()
        session.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(flowOf(
            "{\"intent\":\"calendar_add\",\"reply\":\"Текст модели.\",\"params\":{\"title\":\"Событие\"}}",
        )))
        assertEquals("Текст модели.", session.visible.content)
        assertEquals(session.visible, session.writes.single())
    }

    @Test
    fun `fenced and truncated chat JSON produces only available reply text`() = runTest {
        for (raw in listOf(
            "\uFEFF{\"reply\":\"Текст\",\"params\":null}",
            "```json {\"reply\":\"Текст\"}```",
            "```json\n{\"params\":[{\"reply\":\"Лишнее\"}],\"reply\":\"Текст\"",
            "{\"intent\":\"chat\",\"reply\":\"Текст",
        )) {
            val session = Session()
            session.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(
                flowOf(*raw.map { it.toString() }.toTypedArray()),
            ))
            assertEquals(raw, "Текст", session.visible.content)
            assertEquals(listOf("Текст"), session.writes.map { it.content })
        }
    }

    @Test
    fun `structured chat without top level reply preserves nested text`() = runTest {
        val session = Session()
        session.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(flowOf(
            "{\"intent\":\"chat\",\"params\":{\"reply\":\"Данные\"}}",
        )))
        assertEquals("Данные", session.visible.content)
        assertEquals(session.visible, session.writes.single())
    }

    @Test
    fun `retry persists only its final readable answer under one message id`() = runTest {
        val session = Session()
        var retries = 0
        session.generateSnapshots(ChatReplyTextStream(AssistantResponseParser()).snapshots(flowOf("{\"role\":\"assistant\"}")) {
            retries++
            assertTrue(session.writes.isEmpty())
            assertEquals("", session.visible.content)
            flowOf("{\"unknown\":{\"answer\":\"Восстановленный ответ\"}}")
        })
        assertEquals(1, retries)
        assertEquals("Восстановленный ответ", session.visible.content)
        assertEquals(listOf(session.visible), session.writes)
    }

    @Test
    fun `parsed creation persists only reply while structured notes reach the calendar command`() = runTest {
        val raw = """{"intent":"calendar_add","reply":"Новое событие: Встреча с Анной.","params":{"title":"Встреча с Анной","starts_at":"2026-10-12T15:00","notes":"  Взять документы.\nАкт от 2027-09-01 в 09:30.  "}}"""
        val parsed = AssistantResponseParser().parseResult(raw).getOrThrow()
        val params = parsed.params as CalendarAddParams
        val command = CalendarCommandMapper().map(params).getOrThrow() as CalendarCommand.Add
        val session = Session(chatId = "calendar")
        session.generate(flowOf(raw)) { it.copy(content = parsed.reply) }
        assertEquals("Новое событие: Встреча с Анной.", session.visible.content)
        assertEquals(session.visible, session.writes.single())
        assertEquals("  Взять документы.\nАкт от 2027-09-01 в 09:30.  ", command.notes)
        assertEquals(params.notes, command.notes)
        assertNull(command.value)
    }

    @Test
    fun `frequent deltas update live text immediately but only changed checkpoints are written`() = runTest {
        val session = Session()
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch { session.generate(deltas.receiveAsFlow()) }
        repeat(100) { deltas.send("я") }
        runCurrent()

        assertEquals("я".repeat(100), session.visible.content)
        assertTrue(session.writes.isEmpty())
        advanceTimeBy(999)
        runCurrent()
        assertTrue(session.writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(session.visible.copy(isInterrupted = true)), session.writes)
        assertFalse(session.visible.isInterrupted)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, session.writes.size)
        deltas.send(" конец")
        deltas.close()
        generation.join()
        assertEquals(2, session.writes.size)
        assertEquals("я".repeat(100) + " конец", session.writes.last().content)
        assertFalse(session.writes.last().isInterrupted)
        assertEquals(1, session.writes.map { it.id }.distinct().size)
    }

    @Test
    fun `a short answer including unicode and whitespace is stored intact once`() = runTest {
        val session = Session()
        val parts = listOf("При", "вет", " 👩🏽‍💻", "\n  ", "🙂", "!")
        session.generate(flowOf(*parts.toTypedArray()))
        assertEquals(listOf(session.visible), session.writes)
        assertEquals(parts.joinToString(""), session.writes.single().content)
        assertFalse(session.writes.single().isInterrupted)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, session.writes.size)
    }

    @Test
    fun `a paused stream still saves its latest fragment without waiting for another delta`() = runTest {
        val session = Session()
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch { session.generate(deltas.receiveAsFlow()) }
        runCurrent()
        advanceTimeBy(900)
        deltas.send("Часть ответа")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("Часть ответа", session.writes.single().content)
        assertTrue(session.writes.single().isInterrupted)
        generation.cancelAndJoin()
    }

    @Test
    fun `cancellation before the first checkpoint saves every collected fragment as interrupted`() = runTest {
        val session = Session()
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch { session.generate(deltas.receiveAsFlow()) }
        deltas.send("Начало")
        deltas.send(" и хвост")
        runCurrent()
        generation.cancelAndJoin()

        assertEquals("Начало и хвост", session.writes.single().content)
        assertTrue(session.writes.single().isInterrupted)
        assertEquals(session.visible, session.writes.single())
    }

    @Test
    fun `a stream error also saves the unsaved tail and preserves the original failure`() = runTest {
        val session = Session()
        val failure = IllegalStateException("engine failed")
        val result = runCatching {
            session.generate(flow {
                emit("Ответ до ошибки")
                throw failure
            })
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals("Ответ до ошибки", session.writes.single().content)
        assertTrue(session.writes.single().isInterrupted)
    }

    @Test
    fun `slow storage never blocks live deltas and cancellation waits before saving the tail`() = runTest {
        val storageGate = CompletableDeferred<Unit>()
        var savesStarted = 0
        val session = Session(beforeSave = { if (++savesStarted == 1) storageGate.await() })
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch { session.generate(deltas.receiveAsFlow()) }
        deltas.send("Начало")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, savesStarted)

        deltas.send(" и свежий хвост")
        runCurrent()
        assertEquals("Начало и свежий хвост", session.visible.content)
        assertTrue(session.writes.isEmpty())
        generation.cancel()
        runCurrent()
        assertFalse(generation.isCompleted)
        storageGate.complete(Unit)
        generation.join()

        assertEquals(listOf("Начало", "Начало и свежий хвост"), session.writes.map { it.content })
        assertTrue(session.writes.all { it.isInterrupted })
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, session.writes.size)
    }

    @Test
    fun `raw JSON checkpoint finishes before the calendar reply and cannot overwrite it later`() = runTest {
        val storageGate = CompletableDeferred<Unit>()
        val session = Session(chatId = "calendar", beforeSave = { if (it.isInterrupted) storageGate.await() })
        val deltas = Channel<String>(Channel.UNLIMITED)
        var commandsStarted = 0
        val generation = launch {
            session.generate(deltas.receiveAsFlow()) { it.copy(content = "Событие подготовлено.") }
            commandsStarted++
        }
        deltas.send("{\"reply\":\"Событие подготовлено.\"}")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        deltas.close()
        runCurrent()
        assertEquals(0, commandsStarted)
        assertTrue(session.writes.isEmpty())

        storageGate.complete(Unit)
        generation.join()
        assertEquals(1, commandsStarted)
        assertEquals(2, session.writes.size)
        assertTrue(session.writes.first().isInterrupted)
        assertEquals("Событие подготовлено.", session.writes.last().content)
        assertFalse(session.writes.last().isInterrupted)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, session.writes.size)
    }

    @Test
    fun `stop during final persistence preserves the interruption flag and prevents subsequent actions`() = runTest {
        val storageGate = CompletableDeferred<Unit>()
        val session = Session(beforeSave = { storageGate.await() })
        var actionsStarted = false
        val generation = launch {
            session.generate(flowOf("Полный ответ"))
            actionsStarted = true
        }
        runCurrent()
        generation.cancel()
        runCurrent()
        storageGate.complete(Unit)
        generation.join()

        assertFalse(actionsStarted)
        assertEquals(listOf("Полный ответ", "Полный ответ"), session.writes.map { it.content })
        assertEquals(listOf(false, true), session.writes.map { it.isInterrupted })
        assertTrue(session.visible.isInterrupted)
    }

    @Test
    fun `checkpoint failure stops collection and final cleanup attempts to save the latest text`() = runTest {
        var attempts = 0
        val storageFailure = IllegalStateException("disk unavailable")
        val session = Session(beforeSave = { if (++attempts == 1) throw storageFailure })
        val deltas = Channel<String>(Channel.UNLIMITED)
        var failure: Throwable? = null
        val generation = launch { failure = runCatching { session.generate(deltas.receiveAsFlow()) }.exceptionOrNull() }
        deltas.send("Текст до ошибки записи")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        generation.join()

        assertTrue(failure is IllegalStateException)
        assertEquals(storageFailure.message, failure?.message)
        assertEquals(2, attempts)
        assertEquals("Текст до ошибки записи", session.writes.single().content)
        assertTrue(session.writes.single().isInterrupted)
    }

    @Test
    fun `permanent storage failure is reported while collected text remains available`() = runTest {
        val storageFailure = IllegalStateException("disk full")
        val session = Session(beforeSave = { throw storageFailure })
        val failure = runCatching { session.generate(flowOf("Несохранённый ответ")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(storageFailure.message, failure?.message)
        assertEquals("Несохранённый ответ", session.visible.content)
        assertTrue(session.visible.isInterrupted)
        assertTrue(session.writes.isEmpty())
    }

    @Test
    fun `empty and whitespace-only streams never create history rows on completion or cancellation`() = runTest {
        val completed = Session()
        completed.generate(flowOf("", " ", "\n"))
        assertTrue(completed.writes.isEmpty())

        val cancelled = Session()
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch { cancelled.generate(deltas.receiveAsFlow()) }
        deltas.send(" \n")
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        generation.cancelAndJoin()
        assertTrue(cancelled.writes.isEmpty())
    }

    @Test
    fun `clearing after generation join cannot be undone by a late checkpoint`() = runTest {
        val storageGate = CompletableDeferred<Unit>()
        var attempts = 0
        val session = Session(beforeSave = { if (++attempts == 1) storageGate.await() })
        val deltas = Channel<String>(Channel.UNLIMITED)
        val generation = launch { session.generate(deltas.receiveAsFlow()) }
        deltas.send("Ответ")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        generation.cancel()
        val clear = launch {
            generation.join()
            session.writes.clear()
        }
        runCurrent()
        assertFalse(clear.isCompleted)
        storageGate.complete(Unit)
        clear.join()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(session.writes.isEmpty())
    }

    private class Session(
        chatId: String = "general",
        beforeSave: suspend (ChatMessage) -> Unit = {},
    ) {
        var visible = ChatMessage("response", MessageRole.ASSISTANT, "", 200L, false, chatId)
            private set
        val writes = mutableListOf<ChatMessage>()
        private val writer = ChatResponseWriter(
            initialMessage = visible,
            saveMessage = { beforeSave(it); writes += it },
            onMessageChanged = { message, _ -> visible = message },
        )

        suspend fun generateSnapshots(response: Flow<String>) {
            try {
                writer.collectSnapshots(response)
                writer.complete(visible)
            } finally {
                writer.interrupt()
            }
        }

        suspend fun generate(response: Flow<String>, transform: (ChatMessage) -> ChatMessage = { it }) {
            try {
                writer.collect(response)
                writer.complete(transform(visible))
            } finally {
                writer.interrupt()
            }
        }
    }
}
