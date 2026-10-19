package com.example.aiassistent1.presentation.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.calendar.core.domain.CalendarTime
import com.example.aiassistent1.domain.interfaces.CalendarTimeZoneRepository
import com.example.aiassistent1.domain.model.CalendarTimeZoneState
import com.example.aiassistent1.presentation.formatting.CalendarTimeZoneLabels
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal val LocalCalendarTimeZone = staticCompositionLocalOf { CalendarTimeZoneState() }
private val LocalCalendarTimeZones = staticCompositionLocalOf<CalendarTimeZoneRepository?> { null }
internal val LocalCalendarClock = staticCompositionLocalOf { Instant.now() }

@Composable
internal fun CalendarTimeZoneProvider(repository: CalendarTimeZoneRepository, content: @Composable () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Instant.now()) }
    DisposableEffect(context, lifecycle, repository) {
        fun refresh(readZone: Boolean) {
            now = Instant.now()
            if (readZone) scope.launch { repository.refreshDeviceZone() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh(intent?.action == Intent.ACTION_TIMEZONE_CHANGED)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh(true) }
        lifecycle.addObserver(observer)
        refresh(true)
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
    CompositionLocalProvider(LocalCalendarTimeZone provides state, LocalCalendarTimeZones provides repository,
        LocalCalendarClock provides now, content = content)
}

@Composable
internal fun CalendarTimeZoneAction() {
    val state = LocalCalendarTimeZone.current
    val repository = LocalCalendarTimeZones.current ?: return
    if (!state.showControl) return
    var expanded by remember { mutableStateOf(false) }
    BadgedBox(badge = { if (state.changePending) Badge() }) {
        FilledTonalIconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("calendar_time_zone_button").semantics {
                contentDescription = if (state.changePending) "Часовой пояс изменился. Выбрать часовой пояс" else "Выбрать часовой пояс"
            },
        ) { CalendarTimeZoneGlyph() }
    }
    if (expanded) CalendarTimeZonePicker(repository, state, onDismiss = { expanded = false })
}

/** Original vector artwork: meridians of a globe and an inset clock. */
@Composable
private fun CalendarTimeZoneGlyph() {
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    val face = MaterialTheme.colorScheme.secondaryContainer
    Canvas(Modifier.size(28.dp)) {
        val unit = size.minDimension / 28f
        val globe = Offset(12f * unit, 12f * unit)
        val stroke = Stroke(1.6f * unit)
        drawCircle(ink, 10f * unit, globe, style = stroke)
        drawOval(ink, Offset(7f * unit, 2f * unit), Size(10f * unit, 20f * unit), style = stroke)
        drawLine(ink, Offset(2f * unit, 12f * unit), Offset(22f * unit, 12f * unit), 1.4f * unit)
        drawArc(ink, 0f, 180f, false, Offset(3f * unit, 4f * unit), Size(18f * unit, 8f * unit), style = stroke)
        val clock = Offset(20f * unit, 20f * unit)
        drawCircle(face, 7.5f * unit, clock)
        drawCircle(ink, 6f * unit, clock, style = stroke)
        drawLine(ink, clock, Offset(20f * unit, 16f * unit), 1.7f * unit, StrokeCap.Round)
        drawLine(ink, clock, Offset(23f * unit, 21f * unit), 1.7f * unit, StrokeCap.Round)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTimeZonePicker(repository: CalendarTimeZoneRepository, state: CalendarTimeZoneState, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val now = LocalCalendarClock.current
    val options = remember(now, state.zoneId) {
        ZoneId.getAvailableZoneIds().map(ZoneId::of).sortedWith(
            compareBy<ZoneId> { it != state.zoneId }.thenBy { it.rules.getOffset(now).totalSeconds }
                .thenBy { CalendarTimeZoneLabels.name(it, now) }.thenBy { it.id },
        )
    }
    val filtered = remember(options, query) {
        val needle = query.trim()
        options.filter { needle.isEmpty() || it.id.contains(needle, true) || CalendarTimeZoneLabels.label(it, now).contains(needle, true) }
    }
    fun select(zoneId: String?) {
        scope.launch {
            saving = true
            try {
                repository.selectZone(zoneId)
                if (repository.state.value.error == null) onDismiss()
            } finally { saving = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Часовой пояс календаря", style = MaterialTheme.typography.headlineSmall)
            if (state.changePending) {
                val previous = state.previousDeviceZoneId?.let(ZoneId::of)?.let { CalendarTimeZoneLabels.name(it, now) }
                Text("На телефоне: ${previous?.let { "$it → " }.orEmpty()}${CalendarTimeZoneLabels.label(ZoneId.of(state.deviceZoneId), now)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("Время событий и календарные расчёты обновляются автоматически.", style = MaterialTheme.typography.bodySmall)
            Text("Смещение показано на сегодня. Для событий учитывается выбранная дата.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Город, пояс или UTC") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("calendar_time_zone_search"))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                item(key = "device") {
                    TimeZoneRow("Часовой пояс телефона", CalendarTimeZoneLabels.label(ZoneId.of(state.deviceZoneId), now),
                        state.selectedZoneId == null, !saving, Modifier.testTag("calendar_time_zone_device")) { select(null) }
                    HorizontalDivider()
                }
                if (filtered.isEmpty()) item { Text("Часовой пояс не найден", Modifier.padding(vertical = 16.dp)) }
                items(filtered, key = { it.id }) { zone ->
                    TimeZoneRow(CalendarTimeZoneLabels.name(zone, now), "${CalendarTimeZoneLabels.offset(zone, now)} · ${zone.id}",
                        state.selectedZoneId == zone.id, !saving, Modifier.testTag("calendar_time_zone_option_${zone.id}")) { select(zone.id) }
                }
            }
        }
    }
}

@Composable
private fun TimeZoneRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
internal fun CalendarScheduleTimeZone(
    sourceZone: ZoneId?, currentZone: ZoneId, needsConfirmation: Boolean, localStart: LocalDateTime?,
    enabled: Boolean, onKeepMoment: () -> Unit, onUseCurrent: () -> Unit,
) {
    val now = LocalCalendarClock.current
    val eventInstant = runCatching {
        if (sourceZone == null || localStart == null) now
        else Instant.ofEpochMilli(CalendarTime.toEpochMillis(localStart, sourceZone))
    }.getOrDefault(now)
    if (!needsConfirmation) {
        Text("Пояс события: ${CalendarTimeZoneLabels.label(sourceZone ?: currentZone, eventInstant)}", style = MaterialTheme.typography.bodySmall)
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (sourceZone == null) "Подтвердите часовой пояс" else "Часовой пояс изменился", style = MaterialTheme.typography.titleSmall)
            if (sourceZone == null) Text("Исходный пояс черновика не сохранён. Подтвердите пояс для введённого времени.")
            else {
                Text("Введённое время: ${CalendarTimeZoneLabels.label(sourceZone, eventInstant)}")
                runCatching {
                    localStart?.let { start ->
                        val instant = Instant.ofEpochMilli(CalendarTime.toEpochMillis(start, sourceZone))
                        LocalDateTime.ofInstant(instant, currentZone)
                    }
                }.getOrNull()?.let { converted ->
                    Text("Тот же момент: ${LocalUserDateTimeFormatter.current.dateTime(converted)} · ${CalendarTimeZoneLabels.name(currentZone, now)}")
                }
                TextButton(onClick = onKeepMoment, enabled = enabled) { Text("Сохранить прежний момент") }
            }
            TextButton(onClick = onUseCurrent, enabled = enabled) {
                Text("Считать введённое время по поясу: ${CalendarTimeZoneLabels.name(currentZone, now)}")
            }
        }
    }
}
