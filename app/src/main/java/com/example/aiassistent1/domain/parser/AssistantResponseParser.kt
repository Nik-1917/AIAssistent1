package com.example.aiassistent1.domain.parser

import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.calendar.core.domain.CalendarTime

/** V12.4 wire contract. Missing optional fields differ from invalid supplied fields. */
class AssistantResponseParser(private val zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    fun parse(text: String): AssistantResponse? = parseResult(text).getOrNull()

    fun parseResult(text: String): Result<AssistantResponse> = runCatching {
        val json = Fields(StrictCommandJson.read(text), "$", setOf("intent", "reply", "params"))
        val intent = json.string("intent", required = true)!!
        val reply = json.string("reply", required = true)!!
        val raw = json.objectValue("params")
        val params: AssistantParams? = when (intent) {
            "chat", "chat_reply" -> { Fields(raw, "$.params", emptySet()); null }
            "calendar_add" -> {
                val p = Fields(raw, "$.params", setOf("title", "starts_at", "ends_at", "date", "time", "duration_min", "value", "date_value", "notes"))
                val startValue = p.string("starts_at")
                val start = startValue?.takeIf { p.isDateTime(it) }
                val shorthandTime = startValue?.takeIf { !p.isDateTime(it) }?.also {
                    require(p.isTime(it)) { "$.params.starts_at: требуется дата-время или время HH:MM" }
                }
                val date = p.date("date")
                val suppliedTime = p.time("time")
                require(shorthandTime == null || suppliedTime == null || shorthandTime == suppliedTime) {
                    "$.params: starts_at и time не совпадают"
                }
                val time = suppliedTime ?: shorthandTime
                val fullStart = start?.let(CalendarTime::dateTime)
                require(fullStart == null || date == null || CalendarTime.date(date) == fullStart.toLocalDate()) {
                    "$.params: date не совпадает с датой starts_at"
                }
                require(fullStart == null || time == null || CalendarTime.time(time) == fullStart.toLocalTime()) {
                    "$.params: time не совпадает со временем starts_at"
                }
                val end = p.dateTime("ends_at")
                val localStart = fullStart
                    ?: if (date != null && time != null) CalendarTime.dateTime("${date}T$time") else null
                val duration = CalendarTime.durationMinutes(localStart, end?.let(CalendarTime::dateTime), p.duration(), zoneId)
                CalendarAddParams(p.string("title"), start, duration,
                    date.takeIf { fullStart == null }, time.takeIf { fullStart == null },
                    p.eventValue(), p.notes(), end)
            }
            "calendar_search", "calendar_sum" -> {
                val p = Fields(raw, "$.params", setOf("query", "range_start", "range_end"))
                val (start, end) = if (intent == "calendar_search") p.searchRange() else p.range()
                val query = p.string("query", allowEmpty = intent == "calendar_search")
                if (intent == "calendar_search") CalendarSearchParams(query, start, end)
                else CalendarSumParams(query, start, end)
            }
            "calendar_update" -> {
                val p = Fields(raw, "$.params", setOf("target", "changes"))
                val t = Fields(p.objectValue("target"), "$.params.target", setOf("query", "range_start", "range_end", "use_last_created"))
                val c = Fields(p.objectValue("changes"), "$.params.changes", setOf("title", "date", "time", "duration_min", "value", "date_value", "clear_value", "notes"))
                val query = t.string("query")
                val last = t.flag("use_last_created")
                val (start, end) = t.range()
                require(!(query != null && last)) { "$.params.target: конфликт способов выбора события" }
                require(start == null || query != null) { "$.params.target: период требует query" }
                val value = c.eventValue()
                val clear = c.flag("clear_value")
                require(value == null || !clear) { "$.params.changes: value несовместим с clear_value" }
                CalendarUpdateParams(
                    CalendarUpdateTargetParams(query, start, end, last),
                    CalendarUpdateChangesParams(c.string("title"), c.date("date"), c.time("time"), c.duration(), value, clear, c.notes()),
                )
            }
            "calendar_delete" -> {
                val p = Fields(raw, "$.params", setOf("target"))
                val t = Fields(p.objectValue("target"), "$.params.target", setOf("query", "range_start", "range_end", "use_last_created", "use_last_in_range"))
                val query = t.string("query")
                val last = t.flag("use_last_created")
                val inRange = t.flag("use_last_in_range")
                val (start, end) = t.range()
                require(listOf(query != null, last, inRange).count { it } <= 1) { "$.params.target: конфликт способов выбора события" }
                require(start == null || query != null || inRange) { "$.params.target: период требует query или use_last_in_range" }
                require(!inRange || start != null) { "$.params.target: use_last_in_range требует период" }
                CalendarDeleteParams(CalendarDeleteTargetParams(query, start, end, last, inRange))
            }
            else -> error("$.intent: неподдерживаемая команда $intent")
        }
        AssistantResponse(intent, reply, params)
    }
}

private class Fields(private val values: Map<String, Any>, private val path: String, allowed: Set<String>) {
    init { require(values.keys.all { it in allowed }) { "$path: неизвестные поля ${values.keys - allowed}" } }

    fun string(key: String, required: Boolean = false, allowEmpty: Boolean = false): String? {
        val value = values[key]
        if (value == null && !required) return null
        require(value is String && (allowEmpty || value.isNotBlank())) { "$path.$key: требуется строка допустимой длины" }
        return value
    }
    // Blank notes are absent; meaningful text is never trimmed or rewritten.
    fun notes(): String? = string("notes", allowEmpty = true)?.takeIf { it.isNotBlank() }
    @Suppress("UNCHECKED_CAST")
    fun objectValue(key: String): Map<String, Any> {
        require(values[key] is Map<*, *>) { "$path.$key: требуется объект" }
        return values.getValue(key) as Map<String, Any>
    }
    fun integer(key: String): Long? {
        val value = values[key] ?: return null
        require(value is Long) { "$path.$key: требуется целое число Long" }
        return value
    }
    /** Normalize the wire alias before mapping, validation and persistence. */
    fun eventValue(): Long? {
        val value = integer("value")
        val alias = integer("date_value")
        require(value == null || alias == null || value == alias) {
            "$path: value и date_value должны совпадать"
        }
        return value ?: alias
    }
    fun duration(): Int? = integer("duration_min")?.also {
        require(it in 1..Int.MAX_VALUE.toLong()) { "$path.duration_min: число вне диапазона 1..${Int.MAX_VALUE}" }
    }?.toInt()
    fun flag(key: String): Boolean {
        if (key !in values) return false
        require(values[key] == true) { "$path.$key: при наличии должно быть true" }
        return true
    }
    private fun temporal(key: String, validate: (String) -> Any): String? = string(key)?.also {
        try { validate(it) } catch (error: Exception) {
            throw IllegalArgumentException("$path.$key: некорректная дата или время", error)
        }
    }
    fun date(key: String) = temporal(key, CalendarTime::date)
    fun time(key: String) = temporal(key, CalendarTime::time)
    fun dateTime(key: String) = temporal(key, CalendarTime::dateTime)
    fun isDateTime(value: String): Boolean = runCatching { CalendarTime.dateTime(value) }.isSuccess
    fun isTime(value: String): Boolean = runCatching { CalendarTime.time(value) }.isSuccess
    fun range(): Pair<String?, String?> {
        val start = dateTime("range_start")
        val end = dateTime("range_end")
        require((start == null) == (end == null)) { "$path: обе границы периода должны быть указаны вместе" }
        require(start == null || start < end!!) { "$path: начало периода должно предшествовать концу" }
        return start to end
    }
    /** A lone start is an exact-moment search, represented as a one-minute interval. */
    fun searchRange(): Pair<String?, String?> {
        val start = dateTime("range_start")
        val end = dateTime("range_end")
        if (start == null && end == null) return null to null
        if (start == null) {
            require(end != null) { "$path: некорректный период" }
            error("$path: range_end требует range_start")
        }
        if (end != null) {
            require(start < end) { "$path: начало периода должно предшествовать концу" }
            return start to end
        }
        val exactEnd = CalendarTime.dateTime(start).plusMinutes(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        return start to exactEnd
    }
}
