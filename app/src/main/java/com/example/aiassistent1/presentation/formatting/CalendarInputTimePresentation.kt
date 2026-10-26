package com.example.aiassistent1.presentation.formatting

import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation only: never changes the entered values, zone policy or saved timestamps. */
internal object CalendarInputTimePresentation {
    // Aliases with identical rules need no explanation. Equal offsets today are insufficient.
    fun sameZone(first: ZoneId, second: ZoneId): Boolean = first == second || first.rules == second.rules

    fun showsConversion(mode: CalendarTimeMode, source: ZoneId, target: ZoneId): Boolean =
        mode == CalendarTimeMode.FIXED && !sameZone(source, target)

    fun conversionText(start: LocalDateTime, converted: LocalDateTime, sourceName: String, targetName: String): String {
        val datePattern = when {
            start.toLocalDate() == converted.toLocalDate() -> null
            start.year != converted.year -> fullDate
            else -> shortDate
        }
        fun value(time: LocalDateTime): String =
            (datePattern?.let { time.format(it) + " " }.orEmpty()) + time.format(clock)
        return "${value(start)} $sourceName → ${value(converted)} $targetName"
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val shortDate = DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT)
    private val fullDate = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT)
}
