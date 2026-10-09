package com.example.aiassistent1.domain.parser

import java.io.File
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarV1267DatasetTest {
    @Test fun `every retained target uses an action supported by the application parser`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "docs/calendar_sft_v12_67/manifest.json").isFile }
        val folder = File(root, "docs/calendar_sft_v12_67")
        val manifest = JSONObject(File(folder, "manifest.json").readText())
        val artifacts = manifest.getJSONObject("artifacts")
        val supported = setOf("chat", "calendar_add", "calendar_search", "calendar_sum")
        val parser = AssistantResponseParser(ZoneId.of("Europe/Samara"))
        var checked = 0
        for (split in artifacts.keys()) {
            var rows = 0
            File(folder, "$split.jsonl").forEachLine { line ->
                val messages = JSONObject(line).getJSONArray("messages")
                val raw = messages.getJSONObject(messages.length() - 1).getString("content")
                val expected = JSONObject(raw)
                assertTrue(supported.contains(expected.getString("intent")))
                val result = parser.parseResult(raw).getOrThrow()
                assertEquals(expected.getString("intent"), result.intent)
                rows++
                checked++
            }
            assertEquals(artifacts.getJSONObject(split).getInt("rows"), rows)
        }
        assertEquals(manifest.getInt("total_rows"), checked)
    }

    @Test fun `an action outside the contract is rejected before mapping`() {
        assertTrue(AssistantResponseParser().parseResult(
            """{"intent":"calendar_future_action","reply":"x","params":{}}""",
        ).isFailure)
    }
}
