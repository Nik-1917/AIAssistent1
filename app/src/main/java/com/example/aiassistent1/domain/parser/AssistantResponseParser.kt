package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.AssistantResponse
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarSearchParams
import org.json.JSONObject

class AssistantResponseParser {
    fun parse(text: String): AssistantResponse? {
        return try {
            val jsonString = extractJson(text) ?: return null
            val json = JSONObject(jsonString)
            val intent = json.getString("intent")
            val reply = json.getString("reply")
            val paramsJson = json.optJSONObject("params")

            val params = when (intent) {
                "calendar_search" -> {
                    CalendarSearchParams(
                        query = paramsJson?.optString("query") ?: "",
                        days = paramsJson?.optInt("days", 7) ?: 7
                    )
                }
                "calendar_add" -> {
                    CalendarAddParams(
                        title = paramsJson?.optString("title") ?: "Событие",
                        date = paramsJson?.optString("date") ?: "",
                        duration_min = paramsJson?.optInt("duration_min", 60) ?: 60
                    )
                }
                else -> null
            }

            AssistantResponse(intent, reply, params)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }
}
