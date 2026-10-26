package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import com.example.aiassistent1.presentation.formatting.description
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.SavedCalendarEventCardState

/** Existing card link, current event data. Draft fields remain an immutable creation snapshot. */
@Composable
internal fun SavedCalendarEventCard(
    draft: CalendarEventDraftUiState,
    state: SavedCalendarEventCardState,
    onOpenEvent: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = (state as? SavedCalendarEventCardState.Available)?.event
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (event != null) Icons.Default.CheckCircle else Icons.Default.CalendarMonth, contentDescription = null)
                Text(event?.title ?: draft.title.orEmpty().ifBlank { "Событие" }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when (state) {
                    SavedCalendarEventCardState.Loading -> "Обновление…"
                    is SavedCalendarEventCardState.Available -> "Создано"
                    SavedCalendarEventCardState.Missing -> "Не найдено"
                    SavedCalendarEventCardState.Failed -> "Не обновлено"
                }, style = MaterialTheme.typography.labelMedium)
            }
            when (state) {
                SavedCalendarEventCardState.Loading -> Text("Загрузка данных события…", style = MaterialTheme.typography.bodySmall)
                SavedCalendarEventCardState.Missing -> Text("Событие не найдено в календаре.", style = MaterialTheme.typography.bodySmall)
                SavedCalendarEventCardState.Failed -> {
                    Text("Не удалось загрузить актуальные данные события.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Повторить") }
                }
                is SavedCalendarEventCardState.Available -> {
                    val current = state.event
                    val zone = LocalCalendarTimeZone.current.zoneId
                    val formatter = LocalUserDateTimeFormatter.current
                    val start = current.localStart(zone)
                    val end = current.localEnd(zone)
                    Text(formatter.dateTime(start))
                    current.schedule?.let {
                        Text(CalendarScheduleInput.from(it).description(), style = MaterialTheme.typography.bodySmall)
                        if (it.isRepeating && it.excludedDates.isNotEmpty())
                            Text("Есть отдельные изменения повторений", style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.parentSeriesId != null) Text("Отдельное изменение серии", style = MaterialTheme.typography.bodySmall)
                    if (current.hasEndTime) {
                        Text("Окончание: " + (if (start.toLocalDate() == end.toLocalDate()) formatter.time(end.toLocalTime())
                            else formatter.dateTime(end)), style = MaterialTheme.typography.bodySmall)
                    }
                    CalendarNotesText(current.notes)
                    val duration = if (!current.hasEndTime) "Не заполнено" else {
                        val minutes = if (current.schedule?.mode == CalendarTimeMode.FLOATING) java.time.Duration.between(start, end).toMinutes()
                            else (current.endsAtEpochMillis - current.startsAtEpochMillis) / 60_000L
                        "$minutes мин"
                    }
                    Text("Длительность: $duration · Ценность: ${current.value?.toString() ?: "Не заполнено"}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { draft.savedEventId?.let(onOpenEvent) }) { Text("Перейти") }
                }
            }
        }
    }
}
