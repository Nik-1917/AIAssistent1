package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.calendar.core.domain.CalendarCommand
import com.example.aiassistent1.calendar.core.domain.CalendarTargetMode
import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class OrderCoverageExamplesTest {
    @Test
    fun `every new handwritten target is accepted without runtime changes`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "docs/calendar_sft_order_coverage/coverage_index.json").isFile }
        val folder = File(root, "docs/calendar_sft_order_coverage")
        val index = JSONObject(File(folder, "coverage_index.json").readText())
        val parser = AssistantResponseParser(ZoneId.of("Europe/Samara"))
        val mapper = CalendarCommandMapper(ZoneId.of("Europe/Samara"))
        var checked = 0
        for (split in listOf("train", "validation", "order_holdout")) {
            File(folder, "$split.jsonl").forEachLine { line ->
                val row = JSONObject(line)
                if (index.has(row.optString("case_id"))) {
                    val messages = row.getJSONArray("messages")
                    val expected = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                    val answer = parser.parseResult(expected.toString()).getOrThrow()
                    assertEquals(expected.getString("intent"), answer.intent)
                    val params = expected.getJSONObject("params")
                    val command = mapper.map(requireNotNull(answer.params)).getOrThrow()
                    when (val actual = answer.params) {
                        is CalendarAddParams -> {
                            assertTrue(command is CalendarCommand.Add)
                            assertEquals(params.textOrNull("title"), actual.title)
                            assertEquals(params.textOrNull("starts_at"), actual.startsAt)
                            assertEquals(params.textOrNull("ends_at"), actual.endsAt)
                            assertEquals(params.textOrNull("date"), actual.date)
                            assertEquals(if (params.has("value")) params.getLong("value") else null, actual.value)
                            if (params.has("duration_min")) assertEquals(params.getInt("duration_min"), actual.durationMin)
                            else if (!params.has("ends_at")) assertNull(actual.durationMin)
                        }
                        is CalendarUpdateParams -> {
                            val update = command as CalendarCommand.Update
                            val target = params.getJSONObject("target")
                            assertEquals(if (target.has("query")) CalendarTargetMode.BY_QUERY else CalendarTargetMode.LAST_CREATED, update.target!!.mode)
                            assertEquals(target.textOrNull("query"), actual.target.query)
                            assertEquals(params.getJSONObject("changes").textOrNull("time"), actual.changes.time)
                            assertEquals(params.getJSONObject("changes").textOrNull("date"), actual.changes.date)
                        }
                        else -> fail("Unexpected intent")
                    }
                    checked++
                }
            }
        }
        assertEquals(375, checked)
    }

    private fun JSONObject.textOrNull(key: String): String? = if (has(key)) getString(key) else null
}
