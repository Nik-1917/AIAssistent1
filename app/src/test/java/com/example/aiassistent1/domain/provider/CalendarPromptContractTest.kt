package com.example.aiassistent1.domain.provider

import com.example.aiassistent1.domain.model.AssistantResponse
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.parser.CalendarDateContext
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CalendarPromptContractTest {
    private val parser = AssistantResponseParser()
    private val dateContext = CalendarDateContext(today = LocalDate.of(2026, 10, 7))
    private fun fixtures() = JSONArray(requireNotNull(javaClass.getResource("/calendar_prompt_contract_cases.json")).readText())

    @Test fun `contract evaluation expectations are supported by current parser`() {
        val cases = fixtures()
        val intents = mutableSetOf<String>()
        for (i in 0 until cases.length()) {
            val expected = cases.getJSONObject(i).getJSONObject("expected")
            parser.parseResult(expected.toString(), dateContext).getOrThrow()
            intents += expected.getString("intent")
        }
        val advertised = Regex("(?m)^(chat|calendar_\\w+):").findAll(SystemPromptProvider.CALENDAR_CONTRACT)
            .map { it.groupValues[1] }.toSet()
        assertEquals(advertised, intents)
    }

    /** Opt-in report: scores model responses without treating model errors as parser regressions. */
    @Test fun `score desktop prompt comparison using application parser`() {
        val directory = System.getenv("CALENDAR_PROMPT_EVALUATION")
        assumeTrue("Set CALENDAR_PROMPT_EVALUATION to a completed evaluation directory", directory != null)
        val folder = File(requireNotNull(directory))
        val results = JSONArray(File(folder, "results.json").readText())
        val cases = fixtures()
        assertEquals(cases.length() * 2, results.length())
        val expected = (0 until cases.length()).associate {
            val case = cases.getJSONObject(it)
            case.getString("id") to parser.parseResult(case.getJSONObject("expected").toString(), dateContext).getOrThrow()
        }
        val report = JSONArray()
        val seen = mutableSetOf<String>()
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            val id = result.getString("id")
            val variant = result.getString("variant")
            assertTrue(variant in setOf("old", "contract"))
            assertTrue(seen.add("$id/$variant"))
            val parsed = parser.parseResult(result.getString("output"), dateContext)
            val actual = parsed.getOrNull()
            val wanted = expected.getValue(id)
            val correct = actual != null && comparable(actual) == comparable(wanted)
            report.put(JSONObject().put("id", id).put("variant", variant)
                .put("parserAccepted", parsed.isSuccess).put("equivalentCommandAndParams", correct)
                .put("error", parsed.exceptionOrNull()?.message ?: JSONObject.NULL)
                .put("actual", actual?.toString() ?: JSONObject.NULL)
                .put("expected", wanted.toString()))
        }
        File(folder, "parser-report.json").writeText(report.toString(2))
    }

    // starts_at and date+time are equivalent; ends_at may supply the parsed duration.
    // Reply text is deliberately scored separately by a human, not as an exact string.
    private fun comparable(response: AssistantResponse): AssistantResponse {
        val params = response.params
        val normalized = if (params is CalendarAddParams) {
            val start = params.startsAt ?: if (params.date != null && params.time != null) "${params.date}T${params.time}" else null
            params.copy(startsAt = start, date = params.date.takeIf { start == null },
                time = params.time.takeIf { start == null },
                endsAt = params.endsAt.takeUnless { start != null && params.durationMin != null })
        } else params
        val intent = when (response.intent) {
            "chat_reply" -> "chat"
            else -> response.intent
        }
        return response.copy(intent = intent, reply = "", params = normalized)
    }
}
