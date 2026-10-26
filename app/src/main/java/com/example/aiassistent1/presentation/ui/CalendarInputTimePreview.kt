package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.calendar.core.domain.CalendarTime
import com.example.aiassistent1.domain.model.CalendarInputTime
import com.example.aiassistent1.presentation.formatting.CalendarTimeZoneLabels
import com.example.aiassistent1.presentation.formatting.CalendarInputTimePresentation
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId

/** Show only a useful conversion. Validation messages remain visible even without a card. */
@Composable
internal fun CalendarInputTimePreview(start: LocalDateTime?, end: LocalDateTime?, source: ZoneId, target: ZoneId, mode: CalendarTimeMode) {
    if (start == null || mode == CalendarTimeMode.FLOATING) return
    val converted = runCatching {
        val instant = Instant.ofEpochMilli(CalendarTime.toEpochMillis(start, source))
        CalendarInputTime.convert(start, source, target) to instant
    }
    val convertedEnd = end?.let { endpoint -> runCatching {
        CalendarInputTime.convert(endpoint, source, target) to Instant.ofEpochMilli(CalendarTime.toEpochMillis(endpoint, source))
    } }
    if (CalendarInputTimePresentation.showsConversion(mode, source, target)) {
        converted.onSuccess { (value, instant) ->
            Card(Modifier.fillMaxWidth().testTag("calendar_input_time_preview"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(CalendarInputTimePresentation.conversionText(start, value,
                        CalendarTimeZoneLabels.name(source, instant), CalendarTimeZoneLabels.name(target, instant)),
                        style = MaterialTheme.typography.bodySmall)
                    if (end != null) convertedEnd?.onSuccess { (endpoint, endInstant) ->
                        Text("Окончание: " + CalendarInputTimePresentation.conversionText(end, endpoint,
                            CalendarTimeZoneLabels.name(source, endInstant), CalendarTimeZoneLabels.name(target, endInstant)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    converted.exceptionOrNull()?.let {
        Text(it.message ?: "Проверьте введённое время.", Modifier.testTag("calendar_input_time_error"),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    convertedEnd?.exceptionOrNull()?.let {
        Text(it.message ?: "Проверьте окончание события.", Modifier.testTag("calendar_input_end_time_error"),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
