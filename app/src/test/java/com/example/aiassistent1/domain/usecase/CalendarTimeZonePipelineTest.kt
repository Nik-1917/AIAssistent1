package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

class CalendarTimeZonePipelineTest {
    private val samara = ZoneId.of("Europe/Samara")
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test fun `one request keeps local prompt and UTC interpretation after phone timezone changes`() = runTest {
        val context = CalendarTimeContext(samara, Instant.parse("2026-10-20T11:00:00Z"))
        val engine = RecordingEngine()
        val text = StringBuilder()
        SendMessageUseCase(engine, SystemPromptProvider())(
            listOf(ChatMessage(role = MessageRole.USER, content = "Встреча сегодня в 15:00")),
            useSystemPrompt = true, localDateTime = context.localDateTime,
        ).getOrThrow().collect { text.append(it) }
        val prompt = engine.messages.first().content
        assertTrue(prompt.startsWith("cегодня 2026-10-20 15:00"))
        assertFalse(prompt.contains("Europe/"))
        assertFalse(prompt.contains("UTC"))
        assertFalse(prompt.contains("+04:00"))

        val parsed = AssistantResponseParser(moscow).parseResult(text.toString(), timeZone = context.zoneId).getOrThrow()
        val repository = RecordingRepository()
        val command = CalendarCommandMapper(moscow).map(parsed.params!!, context.zoneId).getOrThrow()
        val executor = CalendarCommandExecutor(repository, moscow)
        executor.execute(command, "request", confirmed = true, timeZone = context.zoneId).getOrThrow()
        val saved = repository.created.single()
        assertEquals(context.instant.toEpochMilli(), saved.startsAtEpochMillis)
        assertEquals(0L, saved.value)
        assertEquals("  Документы  ", saved.notes)
        executor.execute(command, "request", confirmed = true, timeZone = moscow).getOrThrow()
        assertEquals(1, repository.created.size) // A retry consults the receipt before interpreting time.
        assertEquals(saved, repository.created.single())
    }

    @Test fun `day search and sum use selected zone boundaries without mutating stored events`() = runTest {
        val epoch = Instant.parse("2026-10-19T20:30:00Z").toEpochMilli()
        val original = CalendarEvent("existing", "Встреча", epoch, epoch + 600_000L, 1, 2, value = 17, notes = "  12500  ")
        val repository = RecordingRepository(listOf(original))
        val mapper = CalendarCommandMapper()
        val executor = CalendarCommandExecutor(repository)
        val params = CalendarSumParams("", "2026-10-20T00:00", "2026-10-21T00:00")
        val inSamara = executor.execute(mapper.map(params, samara).getOrThrow(), "sum-samara", timeZone = samara)
            .getOrThrow() as CalendarCommandResult.Sum
        val inMoscow = executor.execute(mapper.map(params, moscow).getOrThrow(), "sum-moscow", timeZone = moscow)
            .getOrThrow() as CalendarCommandResult.Sum
        assertEquals(java.math.BigInteger.valueOf(17), inSamara.total)
        assertEquals(java.math.BigInteger.ZERO, inMoscow.total)
        assertEquals(listOf(original), repository.events)
        assertTrue(repository.created.isEmpty())
    }

    @Test fun `default helpers observe a later system zone instead of retaining construction-time zone`() = runTest {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(samara))
            val mapper = CalendarCommandMapper()
            val parser = AssistantResponseParser()
            val repository = RecordingRepository()
            val executor = CalendarCommandExecutor(repository)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val json = """{"intent":"calendar_add","reply":"Встреча","params":{"title":"Встреча","starts_at":"2026-03-29T01:30","ends_at":"2026-03-29T03:30","duration_min":60}}"""
            assertTrue(parser.parseResult(json).isSuccess)
            val expected = Instant.parse("2026-03-28T23:00:00Z").toEpochMilli()
            assertEquals(expected, mapper.range("2026-03-29T00:00", "2026-03-30T00:00")!!.start)
            executor.execute(CalendarCommand.Add("Встреча", LocalDate.of(2026, 3, 29), LocalTime.of(1, 30), 60),
                "default", confirmed = true).getOrThrow()
            assertEquals(Instant.parse("2026-03-29T00:30:00Z").toEpochMilli(), repository.created.single().startsAtEpochMillis)
        } finally { TimeZone.setDefault(previous) }
    }

    private class RecordingEngine : LLMEngine {
        var messages = emptyList<ChatMessage>()
        override val state = MutableStateFlow<ModelState>(ModelState.Ready)
        override suspend fun ensureLoaded() = Result.success(Unit)
        override fun generate(messages: List<ChatMessage>) = flowOf(
            """{"intent":"calendar_add","reply":"Новое событие: Встреча.","params":{"title":"Встреча","starts_at":"2026-10-20T15:00","value":0,"notes":"  Документы  "}}""",
        ).also { this.messages = messages }
        override fun cancelGeneration() = Unit
        override fun updateParams(params: GenerationParams) = Unit
        override fun unload() = Unit
        override fun close() = Unit
    }

    private class RecordingRepository(val events: List<CalendarEvent> = emptyList()) : CalendarEventRepository {
        val created = mutableListOf<CalendarEventDraft>()
        val receipts = mutableMapOf<String, CalendarReceipt>()
        override suspend fun getReceipt(requestId: String) = Result.success(receipts[requestId])
        override suspend fun commit(requestId: String, mutation: CalendarMutation): Result<CalendarReceipt> {
            val draft = (mutation as CalendarMutation.Create).draft
            created.add(draft)
            val receipt = CalendarReceipt(requestId, "calendar_add", "new", draft.title, draft.notes)
            receipts[requestId] = receipt
            return Result.success(receipt)
        }
        override suspend fun search(query: String, rangeStartEpochMillis: Long, rangeEndEpochMillis: Long) = Result.success(
            events.filter { it.startsAtEpochMillis < rangeEndEpochMillis && it.endsAtEpochMillis > rangeStartEpochMillis },
        )
        override fun observeInRange(rangeStartEpochMillis: Long, rangeEndEpochMillis: Long) = flowOf(events)
        override suspend fun create(draft: CalendarEventDraft): Result<CalendarEvent> = error("Not used")
        override suspend fun getById(id: String): Result<CalendarEvent?> = error("Not used")
        override suspend fun update(update: CalendarEventUpdate): Result<CalendarEvent> = error("Not used")
        override suspend fun delete(id: String): Result<Unit> = error("Not used")
    }
}
