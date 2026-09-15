package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Exercise the existing Android parser and mapper with the actual handwritten answers. */
class LastEventManualExamplesTest {
    private val parser = AssistantResponseParser()

    @Test
    fun `all manual answers select the intended kind of target and preserve changes`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "docs/calendar_assistant_last_event_manual.json").isFile }
        val register = JSONObject(File(root, "docs/calendar_assistant_last_event_manual.json").readText())
        val cases = register.getJSONArray("examples")
        assertEquals(42, cases.length())
        val mapper = CalendarCommandMapper(ZoneId.of("Europe/Samara"))
        for (index in 0 until cases.length()) {
            val example = cases.getJSONObject(index)
            val expected = example.getJSONObject("assistant")
            val response = parser.parseResult(expected.toString()).getOrThrow()
            assertEquals(example.getString("id"), "calendar_update", response.intent)
            val params = response.params as CalendarUpdateParams
            val command = mapper.map(params).getOrThrow() as CalendarCommand.Update
            val target = expected.getJSONObject("params").getJSONObject("target")
            val changes = expected.getJSONObject("params").getJSONObject("changes")
            val named = example.getString("group") == "named_event"
            assertEquals(if (named) CalendarTargetMode.BY_QUERY else CalendarTargetMode.LAST_CREATED, command.target!!.mode)
            assertEquals(!named, params.target.useLastCreated)
            assertFalse(params.target.useLastReferenced)
            assertEquals(target.textOrNull("query"), params.target.query)
            assertEquals(target.textOrNull("range_start"), params.target.rangeStart)
            assertEquals(target.textOrNull("range_end"), params.target.rangeEnd)
            assertEquals(changes.textOrNull("title"), params.changes.title)
            assertEquals(changes.textOrNull("date"), params.changes.date)
            assertEquals(changes.textOrNull("time"), params.changes.time)
            assertEquals(if (changes.has("duration_min")) changes.getInt("duration_min") else null, params.changes.durationMin)
            assertEquals(if (changes.has("value")) changes.getLong("value") else null, params.changes.value)
            assertEquals(changes.optBoolean("clear_value", false), params.changes.clearValue)
            assertEquals(changes.length() == 0, command.changes.isEmpty)
        }
    }

    @Test
    fun `invented last command and target aliases remain rejected`() {
        assertTrue(parser.parseResult("""{"intent":"calendar_last","reply":"Последнее событие.","params":{}}""").isFailure)
        for (key in listOf("use_last_match", "use_last_referenced")) {
            assertTrue(parser.parseResult("""{"intent":"calendar_update","reply":"Изменение.","params":{"target":{"$key":true},"changes":{"time":"16:30"}}}""").isFailure)
        }
        assertTrue(parser.parseResult("""{"intent":"calendar_update","reply":"Изменение.","params":{"target":{"query":"Репетиция","use_last_created":true},"changes":{"time":"16:30"}}}""").isFailure)
    }

    private fun JSONObject.textOrNull(key: String): String? = if (has(key)) getString(key) else null
}
