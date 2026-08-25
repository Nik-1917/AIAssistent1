package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.AssistantResponse
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.CalendarDeleteParams
import com.example.aiassistent1.domain.model.CalendarDeleteTargetParams
import com.example.aiassistent1.domain.model.CalendarSearchParams
import com.example.aiassistent1.domain.model.CalendarUpdateChangesParams
import com.example.aiassistent1.domain.model.CalendarUpdateParams
import com.example.aiassistent1.domain.model.CalendarUpdateTargetParams
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
                        rangeStart = paramsJson?.optionalNonBlankString("range_start"),
                        rangeEnd = paramsJson?.optionalNonBlankString("range_end"),
                    )
                }
                "calendar_add" -> {
                    CalendarAddParams(
                        title = paramsJson?.optionalNonBlankString("title"),
                        startsAt = paramsJson?.optionalNonBlankString("starts_at")
                            ?: paramsJson?.optionalNonBlankString("date"),
                        durationMin = paramsJson?.optionalPositiveInt("duration_min"),
                    )
                }
                "calendar_update" -> {
                    val targetJson = paramsJson?.optJSONObject("target")
                    val changesJson = paramsJson?.optJSONObject("changes")
                    CalendarUpdateParams(
                        target = CalendarUpdateTargetParams(
                            query = targetJson?.optionalNonBlankString("query"),
                            rangeStart = targetJson?.optionalNonBlankString("range_start"),
                            rangeEnd = targetJson?.optionalNonBlankString("range_end"),
                            useLastCreated = targetJson?.optBoolean("use_last_created", false) == true,
                            useLastReferenced = targetJson?.optBoolean("use_last_referenced", false) == true,
                        ),
                        changes = CalendarUpdateChangesParams(
                            title = changesJson?.optionalNonBlankString("title"),
                            date = changesJson?.optionalNonBlankString("date"),
                            time = changesJson?.optionalNonBlankString("time"),
                            durationMin = changesJson?.optionalPositiveInt("duration_min"),
                        ),
                    )
                }
                "calendar_delete" -> {
                    val targetJson = paramsJson?.optJSONObject("target")
                    CalendarDeleteParams(
                        target = CalendarDeleteTargetParams(
                            query = targetJson?.optionalNonBlankString("query"),
                            rangeStart = targetJson?.optionalNonBlankString("range_start"),
                            rangeEnd = targetJson?.optionalNonBlankString("range_end"),
                            useLastCreated = targetJson?.optBoolean("use_last_created", false) == true,
                            useLastInRange = targetJson?.optBoolean("use_last_in_range", false) == true,
                        ),
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

private fun JSONObject.optionalNonBlankString(name: String): String? =
    takeIf { has(name) && !isNull(name) }
        ?.optString(name)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun JSONObject.optionalPositiveInt(name: String): Int? =
    takeIf { has(name) && !isNull(name) }
        ?.optInt(name, 0)
        ?.takeIf { it > 0 }
