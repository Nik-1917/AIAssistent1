package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.mapper.CalendarCommandMapper
import org.junit.Assert.*
import org.junit.Test

class CalendarDeleteEmptyChangesTest {
    private val parser = AssistantResponseParser()
    private fun parse(target: String, extra: String = "") = parser.parseResult(
        """{"intent":"calendar_delete","reply":"Удаление","params":{"target":$target$extra}}""",
    )

    @Test fun `empty changes preserve all existing delete targets and normalized hours`() {
        val mapper = CalendarCommandMapper()
        for (target in listOf(
            "{}",
            """{"query":"Прогулка"}""",
            """{"use_last_created":true}""",
            """{"query":"Прогулка","range_start":"2026-09-16T00:00","range_end":"2026-09-17T00:00"}""",
            """{"use_last_in_range":true,"range_start":"2026-09-16T00:00","range_end":"2026-09-17T00:00"}""",
            """{"query":"Прогулка","range_start":"2026-09-16T00:00","range_end":"2026-09-17T00:00","time_min":11,"time_max":12}""",
        )) {
            val original = parse(target).getOrThrow()
            val withEmptyChanges = parse(target, ""","changes":{}""").getOrThrow()
            assertEquals(target, original, withEmptyChanges)
            assertEquals(mapper.map(original.params!!).getOrThrow(), mapper.map(withEmptyChanges.params!!).getOrThrow())
        }
    }

    @Test fun `nonempty or nonobject changes are rejected`() {
        for (changes in listOf(
            """{"value":0}""", """{"title":"Прогулка"}""", """{"unknown":{}}""",
            "null", "false", "true", "0", "\"\"", "\"{}\"", "[]",
        )) {
            assertTrue(changes, parse("""{"use_last_created":true}""", ""","changes":$changes""").isFailure)
        }
    }

    @Test fun `empty changes do not bypass target validation or loosen other schemas`() {
        for (target in listOf(
            """{"use_last":true}""",
            """{"query":"Прогулка","use_last_created":true}""",
            """{"query":"Прогулка","time_min":11}""",
            """{"use_last_in_range":true}""",
        )) assertTrue(target, parse(target, ""","changes":{}""").isFailure)
        assertTrue(parse("{}", ""","changes":{},"unknown":{}""").isFailure)
        for (intent in listOf("calendar_add", "calendar_search", "calendar_sum", "chat")) {
            assertTrue(intent, parser.parseResult(
                """{"intent":"$intent","reply":"x","params":{"changes":{}}}""",
            ).isFailure)
        }
    }
}
