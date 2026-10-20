package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import com.example.aiassistent1.calendar.core.domain.CalendarTime
import com.example.aiassistent1.presentation.viewmodel.CalendarViewModel
import com.example.aiassistent1.presentation.viewmodel.VoiceModeNotice
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import com.example.aiassistent1.calendar.core.domain.CalendarTimeMode
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import com.example.aiassistent1.domain.model.CalendarInputTime
import com.example.aiassistent1.presentation.formatting.description
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onNavigateBack: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    voiceModeNotice: VoiceModeNotice? = null,
    conversationError: String? = null,
    onConversationErrorDismiss: () -> Unit = {},
    inputPanel: @Composable () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val calendarListState = rememberLazyListState()
    var eventToEdit by remember { mutableStateOf<CalendarEvent?>(null) }
    var eventToDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    var eventToChooseEditScope by remember { mutableStateOf<CalendarEvent?>(null) }
    var isCreatingEvent by remember { mutableStateOf(false) }
    var editorZone by remember { mutableStateOf(uiState.zoneId) }
    var editorDate by remember { mutableStateOf(uiState.selectedDate) }
    val selectedDayEvents = uiState.events.filter { it.localDate(uiState.zoneId) == uiState.selectedDate }
    val eventCounts = remember(uiState.events, uiState.zoneId) {
        uiState.events.groupingBy { it.localDate(uiState.zoneId) }.eachCount()
    }

    LaunchedEffect(uiState.focusedEventId, selectedDayEvents) {
        val eventId = uiState.focusedEventId ?: return@LaunchedEffect
        val eventIndex = selectedDayEvents.indexOfFirst { it.id == eventId }
        if (eventIndex >= 0) {
            calendarListState.scrollToItem(3 + eventIndex)
            viewModel.clearFocusedEvent(eventId)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    LaunchedEffect(conversationError) {
        conversationError?.let { message ->
            snackbarHostState.showSnackbar(message, actionLabel = "Закрыть", duration = SnackbarDuration.Indefinite)
            onConversationErrorDismiss()
        }
    }
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val today = LocalUserDateTimeFormatter.current.today
                    val dayOfWeek = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
                    val dateText = "сегодня $dayOfWeek ${today.dayOfMonth} число"
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(com.example.aiassistent1.presentation.formatting.CalendarTimeZoneLabels.label(
                            LocalCalendarTimeZone.current.zoneId, LocalCalendarClock.current),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("back_to_chat"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Вернуться в чат",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    CalendarTimeZoneAction()
                    TextButton(onClick = onOpenChat) {
                        Text("Чат")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = inputPanel,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editorZone = uiState.zoneId; editorDate = uiState.selectedDate; isCreatingEvent = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Событие") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refreshCalendar,
            modifier = Modifier.padding(paddingValues),
        ) {
            LazyColumn(
                state = calendarListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MonthNavigation(
                        month = uiState.visibleMonth,
                        onPrevious = viewModel::showPreviousMonth,
                        onNext = viewModel::showNextMonth,
                    )
                }
                item {
                    MonthGrid(
                        month = uiState.visibleMonth,
                        selectedDate = uiState.selectedDate,
                        eventCounts = eventCounts,
                        onDateSelected = viewModel::selectDate,
                    )
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = uiState.selectedDate.format(selectedDateFormatter()),
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (selectedDayEvents.isEmpty()) {
                    item { EmptyDayCard() }
                } else {
                    items(selectedDayEvents, key = CalendarEvent::id) { event ->
                        CalendarEventCard(
                            event = event,
                            onEdit = {
                                editorZone = uiState.zoneId
                                if (event.seriesId != null) eventToChooseEditScope = event
                                else { editorDate = event.localDate(editorZone); eventToEdit = event }
                            },
                            onDelete = { eventToDelete = event },
                        )
                    }
                }
            }
            VoiceModeNoticeCard(
                notice = voiceModeNotice,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            )
        }
    }

    if (isCreatingEvent) {
        CalendarEventEditorDialog(
            date = editorDate,
            event = null,
            onDismiss = { isCreatingEvent = false },
            onSave = { title, startTime, endTime, value, notes ->
                viewModel.createEvent(title, editorDate, startTime, endTime, value, notes, editorZone)
                isCreatingEvent = false
            },
            zoneId = editorZone,
            onZoneSelected = { editorZone = it },
            onSaveWithSchedule = { result ->
                viewModel.createEvent(result.title, editorDate, result.startTime, result.endTime, result.value, result.notes,
                    editorZone, result.scheduleInput, result.inputTimeZoneId?.let(ZoneId::of))
                isCreatingEvent = false
            },
        )
    }

    eventToEdit?.let { event ->
        CalendarEventEditorDialog(
            date = editorDate,
            event = event,
            onDismiss = { eventToEdit = null },
            onSave = { title, startTime, endTime, value, notes ->
                viewModel.updateEvent(event.id, title, editorDate, startTime, endTime, value, notes, editorZone, event)
                eventToEdit = null
            },
            zoneId = editorZone,
            onZoneSelected = { editorZone = it },
            onSaveWithSchedule = { result ->
                viewModel.updateEvent(event.id, result.title, editorDate, result.startTime, result.endTime, result.value,
                    result.notes, editorZone, event, result.scheduleInput)
                eventToEdit = null
            },
        )
    }

    eventToChooseEditScope?.let { event ->
        AlertDialog(onDismissRequest = { eventToChooseEditScope = null }, title = { Text("Изменить повторение?") },
            text = { Text("«${event.title}»: выберите область изменения.") },
            confirmButton = { TextButton(onClick = {
                eventToChooseEditScope = null; editorDate = event.localDate(editorZone); eventToEdit = event
            }) { Text("Только это событие") } },
            dismissButton = { Column {
                TextButton(onClick = {
                    eventToChooseEditScope = null
                    viewModel.loadSeriesForEdit(event) { series ->
                        editorZone = series.schedule?.zoneId?.let(ZoneId::of) ?: uiState.zoneId
                        editorDate = series.localDate(editorZone); eventToEdit = series
                    }
                }) { Text("Всю серию") }
                TextButton(onClick = { eventToChooseEditScope = null }) { Text("Отмена") }
            } })
    }

    eventToDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { eventToDelete = null },
            title = { Text("Удалить событие?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("«${event.title}» будет удалено из локального календаря без возможности восстановления.")
                    CalendarNotesText(event.notes)
                    if (event.seriesId != null) TextButton(onClick = {
                        viewModel.deleteEvent(event.seriesId!!); eventToDelete = null
                    }) { Text("Удалить всю серию", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEvent(event.id)
                        eventToDelete = null
                    },
                ) { Text(if (event.seriesId != null) "Только это событие" else "Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { eventToDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun MonthNavigation(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onPrevious) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Предыдущий месяц")
        }
        Text(
            text = month.format(monthFormatter()),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        FilledTonalIconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Следующий месяц")
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    eventCounts: Map<LocalDate, Int>,
    onDateSelected: (LocalDate) -> Unit,
) {
    val density = LocalDensity.current
    val dayLineHeight = MaterialTheme.typography.bodyMedium.lineHeight
    val countLineHeight = MaterialTheme.typography.labelSmall.lineHeight
    val dayHeight = with(density) { maxOf(36.dp, dayLineHeight.toDp() + 4.dp) }
    val countHeight = with(density) { maxOf(18.dp, countLineHeight.toDp() + 2.dp) }
    // Reserve space for day circle and the overlapping badge.
    val cellHeight = dayHeight + countHeight - 4.dp
    val leadingDays = firstDayOffset(month)
    val weekCount = (leadingDays + month.lengthOfMonth() + 6) / 7
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                WEEKDAY_LABELS.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellHeight * weekCount),
            ) {
                items(leadingDays) {
                    Spacer(modifier = Modifier.height(cellHeight))
                }
                items(month.lengthOfMonth()) { index ->
                    val date = month.atDay(index + 1)
                    MonthDayCell(
                        date = date,
                        isSelected = date == selectedDate,
                        eventCount = eventCounts[date] ?: 0,
                        dayHeight = dayHeight,
                        countHeight = countHeight,
                        cellHeight = cellHeight,
                        onClick = { onDateSelected(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthDayCell(
    date: LocalDate,
    isSelected: Boolean,
    eventCount: Int,
    dayHeight: Dp,
    countHeight: Dp,
    cellHeight: Dp,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val countColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    val badgeBgColor = if (isSelected) surfaceColor else MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .height(cellHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(3.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(dayHeight)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (eventCount > 0) {
            Box(
                modifier = Modifier
                    .padding(top = dayHeight - 8.dp)
                    .height(countHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = eventCount.toString(),
                    modifier = Modifier
                        .background(badgeBgColor, RoundedCornerShape(6.dp))
                        .border(1.dp, countColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = countColor,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun EmptyDayCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text = "На этот день событий нет. Нажмите «Событие», чтобы добавить первое.",
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CalendarEventCard(
    event: CalendarEvent,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                CalendarNotesText(event.notes)
                event.schedule?.let { Text(CalendarScheduleInput.from(it).description(), style = MaterialTheme.typography.bodySmall) }
                if (event.parentSeriesId != null) Text("Отдельное изменение серии", style = MaterialTheme.typography.bodySmall)
                AssistChip(
                    onClick = onEdit,
                    label = {
                        Text(if (event.schedule?.mode == CalendarTimeMode.FLOATING && event.hasEndTime) {
                            val zone = LocalCalendarTimeZone.current.zoneId
                            val start = event.localStart(zone)
                            val end = event.localEnd(zone)
                            if (start.toLocalDate() == end.toLocalDate()) "${start.format(TIME_FORMATTER)}–${end.format(TIME_FORMATTER)}"
                            else "${LocalUserDateTimeFormatter.current.dateTime(start)} — ${LocalUserDateTimeFormatter.current.dateTime(end)}"
                        } else if (event.hasEndTime) {
                            LocalUserDateTimeFormatter.current.range(event.startsAtEpochMillis, event.endsAtEpochMillis)
                        } else event.localStartTimeText(LocalCalendarTimeZone.current.zoneId))
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Изменить событие")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить событие")
            }
        }
    }
}

@Composable
internal fun CalendarEventEditorDialog(
    date: LocalDate,
    event: CalendarEvent?,
    onDismiss: () -> Unit,
    onSave: (title: String, startTime: LocalTime, endTime: LocalTime?, value: Long?, notes: String) -> Unit,
    zoneId: ZoneId = LocalCalendarTimeZone.current.zoneId,
    onZoneSelected: (ZoneId) -> Unit = {},
    onSaveWithSchedule: ((CalendarEventEditorResult) -> Unit)? = null,
) {
    var scheduleInput by remember(event) { mutableStateOf(CalendarScheduleInput.forEvent(event)) }
    val timeModeExpanded = remember(event, date) { mutableStateOf(false) }
    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var startTimeText by remember(event) { mutableStateOf(event?.localStartTimeText(zoneId) ?: "09:00") }
    var durationText by remember(event) {
        mutableStateOf(event?.let {
            if (!it.hasEndTime) "" else if (it.schedule?.mode == CalendarTimeMode.FLOATING)
                java.time.Duration.between(it.localStart(zoneId), it.localEnd(zoneId)).toMinutes().toString()
            else it.durationMinutes().toString()
        } ?: "60")
    }
    var endTimeText by remember(event) {
        mutableStateOf(event?.let { if (it.hasEndTime) it.localEndTimeText(zoneId) else "" } ?: "10:00")
    }
    var valueText by remember(event) { mutableStateOf(event?.value?.toString().orEmpty()) }
    var notesText by remember(event) { mutableStateOf(event?.notes.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val zoneState = LocalCalendarTimeZone.current
    val currentZone = zoneState.zoneId
    // Capture once. A phone/preference change must not reinterpret an open editor.
    var creationInputZone by remember(event, date) {
        mutableStateOf(zoneState.inputZoneId.takeIf { event == null && zoneState.isLoaded && zoneState.error == null })
    }
    LaunchedEffect(zoneState.isLoaded, zoneState.error, zoneState.inputZoneId) {
        if (event == null && creationInputZone == null && zoneState.isLoaded && zoneState.error == null) creationInputZone = zoneState.inputZoneId
    }
    val inputZone = if (event == null) creationInputZone ?: zoneId else zoneId
    var confirmedFor by remember(event, date) { mutableStateOf(zoneId.id) }
    val needsZoneConfirmation = scheduleInput.mode != CalendarTimeMode.FLOATING && zoneId != currentZone && confirmedFor != currentZone.id
    var durationZone by remember(event, date) { mutableStateOf(inputZone) }

    fun elapsedMinutes(start: LocalTime, end: LocalTime): Long = if (scheduleInput.mode == CalendarTimeMode.FLOATING)
        java.time.Duration.between(java.time.LocalDateTime.of(date, start), java.time.LocalDateTime.of(
            com.example.aiassistent1.presentation.viewmodel.resolveCalendarEndDate(date, start, end), end)).toMinutes()
    else CalendarTime.durationMinutes(
        java.time.LocalDateTime.of(date, start),
        java.time.LocalDateTime.of(com.example.aiassistent1.presentation.viewmodel.resolveCalendarEndDate(date, start, end), end),
        null, inputZone,
    )!!.toLong()
    fun endAfter(start: LocalTime, duration: Long): String {
        require(duration in 1..Int.MAX_VALUE.toLong())
        if (scheduleInput.mode == CalendarTimeMode.FLOATING) return java.time.LocalDateTime.of(date, start).plusMinutes(duration).format(TIME_FORMATTER)
        val epoch = CalendarTime.toEpochMillis(java.time.LocalDateTime.of(date, start), inputZone)
        return Instant.ofEpochMilli(Math.addExact(epoch, Math.multiplyExact(duration, 60_000L)))
            .atZone(inputZone).format(TIME_FORMATTER)
    }
    var durationMode by remember(event) { mutableStateOf(scheduleInput.mode) }
    LaunchedEffect(inputZone, scheduleInput.mode) {
        // A deliberate change of interpretation keeps the entered endpoints and recomputes elapsed time.
        if ((durationZone != inputZone || durationMode != scheduleInput.mode) && endTimeText.isNotBlank()) runCatching {
            elapsedMinutes(CalendarTime.time(startTimeText.trim()), CalendarTime.time(endTimeText.trim())).toString()
        }.onSuccess { durationText = it }
        durationZone = inputZone
        durationMode = scheduleInput.mode
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (event == null) "Дата: ${LocalUserDateTimeFormatter.current.eventEditorDate(date)}" else "Изменить событие")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CalendarScheduleControls(scheduleInput, { scheduleInput = it }, allowRepetition = event?.seriesId == null,
                    needsAttention = needsZoneConfirmation, expandedState = timeModeExpanded, fixedTimeContent = {
                        if (event == null) CalendarInputZoneControls(inputZone, enabled = zoneState.isLoaded,
                            onSelected = { creationInputZone = it })
                        CalendarScheduleTimeZone(zoneId, currentZone, needsZoneConfirmation,
                            runCatching {
                                CalendarInputTime.convert(java.time.LocalDateTime.of(date, CalendarTime.time(startTimeText.trim())), inputZone, zoneId)
                            }.getOrNull(),
                            enabled = true, onKeepMoment = { confirmedFor = currentZone.id },
                            onUseCurrent = { confirmedFor = currentZone.id; onZoneSelected(currentZone) },
                            preserveInputZone = event == null, inputZone = inputZone)
                    })
                if (event != null) Text("Дата: ${LocalUserDateTimeFormatter.current.date(date)}")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth().standardTextEditing(),
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = startTimeText,
                    onValueChange = { value ->
                        startTimeText = value
                        runCatching {
                            val start = LocalTime.parse(value, TIME_FORMATTER)
                            val duration = durationText.toLong()
                            require(duration > 0)
                            endAfter(start, duration)
                        }.onSuccess { endTimeText = it }
                    },
                    modifier = Modifier.fillMaxWidth().standardTextEditing(),
                    label = { Text("Начало (ЧЧ:ММ)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { value ->
                        durationText = value
                        if (value.isBlank()) endTimeText = ""
                        runCatching {
                            val start = LocalTime.parse(startTimeText, TIME_FORMATTER)
                            val duration = value.toLong()
                            require(duration > 0)
                            endAfter(start, duration)
                        }.onSuccess { endTimeText = it }
                    },
                    modifier = Modifier.fillMaxWidth().standardTextEditing(),
                    label = { Text("Длительность, минут") },
                    supportingText = if (durationText.isBlank()) { { Text("Не заполнено") } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endTimeText,
                    onValueChange = { value ->
                        endTimeText = value
                        if (value.isBlank()) durationText = ""
                        runCatching {
                            val start = LocalTime.parse(startTimeText, TIME_FORMATTER)
                            val end = LocalTime.parse(value, TIME_FORMATTER)
                            val minutes = elapsedMinutes(start, end)
                            require(minutes > 0)
                            minutes.toString()
                        }.onSuccess { durationText = it }
                    },
                    modifier = Modifier.fillMaxWidth().standardTextEditing(),
                    label = { Text("Окончание (ЧЧ:ММ)") },
                    supportingText = if (endTimeText.isBlank()) { { Text("Не заполнено") } } else null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    modifier = Modifier.fillMaxWidth().standardTextEditing(),
                    label = { Text("Ценность события") },
                    supportingText = if (valueText.isBlank()) { { Text("Не заполнено") } } else null,
                    singleLine = true,
                )
                if (event == null) CalendarInputTimePreview(
                    start = runCatching { java.time.LocalDateTime.of(date, CalendarTime.time(startTimeText.trim())) }.getOrNull(),
                    end = runCatching {
                        val start = CalendarTime.time(startTimeText.trim())
                        val end = CalendarTime.time(endTimeText.trim())
                        java.time.LocalDateTime.of(com.example.aiassistent1.presentation.viewmodel.resolveCalendarEndDate(date, start, end), end)
                    }.getOrNull(), source = inputZone, target = zoneId, mode = scheduleInput.mode,
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    modifier = Modifier.fillMaxWidth().standardTextEditing(),
                    label = { Text("Примечание") },
                    supportingText = if (notesText.isBlank()) { { Text("Не заполнено") } } else null,
                    minLines = 2,
                )
                validationError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = runCatching {
                        require(title.isNotBlank()) { "Введите название события." }
                        require(event != null || (zoneState.isLoaded && creationInputZone != null)) { "Дождитесь загрузки привычного часового пояса." }
                        val start = CalendarTime.time(startTimeText.trim())
                        val end = endTimeText.trim().takeIf { it.isNotEmpty() }?.let(CalendarTime::time)
                        require(!needsZoneConfirmation) { "Подтвердите часовой пояс события." }
                        val unchanged = event != null && scheduleInput.mode == (event.schedule?.mode ?: CalendarTimeMode.FIXED) && date == event.localDate(zoneId) &&
                            start.format(TIME_FORMATTER) == event.localStartTimeText(zoneId) &&
                            end?.format(TIME_FORMATTER) == event.localEndTimeText(zoneId).takeIf { event.hasEndTime }
                        if (scheduleInput.mode == CalendarTimeMode.FIXED && !unchanged)
                            CalendarTime.toEpochMillis(java.time.LocalDateTime.of(date, start), inputZone)
                        val duration = end?.let {
                            if (unchanged && scheduleInput.mode != CalendarTimeMode.FLOATING) event!!.durationMinutes() else elapsedMinutes(start, it)
                        }
                        if (duration == null) {
                            require(durationText.isBlank()) { "Проверьте длительность и окончание." }
                        } else {
                            require(durationText.trim().toLongOrNull() == duration) { "Проверьте длительность и окончание." }
                        }
                        val value = valueText.trim().takeIf { it.isNotEmpty() }?.let {
                            requireNotNull(it.toLongOrNull()) { "Ценность должна быть целым числом." }
                        }
                        scheduleInput.build(java.time.LocalDateTime.of(date, start), end?.let {
                            java.time.LocalDateTime.of(com.example.aiassistent1.presentation.viewmodel.resolveCalendarEndDate(date, start, it), it)
                        }, inputZone)
                        CalendarEventEditorResult(title, start, end, value, notesText, scheduleInput,
                            inputZone.id.takeIf { event == null })
                    }
                    parsed.onSuccess { result ->
                        if (onSaveWithSchedule != null) onSaveWithSchedule(result)
                        else onSave(result.title, result.startTime, result.endTime, result.value, result.notes)
                    }
                        .onFailure { validationError = it.message ?: "Проверьте введённые данные." }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

internal data class CalendarEventEditorResult(
    val title: String,
    val startTime: LocalTime,
    val endTime: LocalTime?,
    val value: Long?,
    val notes: String,
    val scheduleInput: CalendarScheduleInput,
    val inputTimeZoneId: String? = null,
)

private fun CalendarEvent.localDate(zoneId: ZoneId): LocalDate =
    localStart(zoneId).toLocalDate()

private fun CalendarEvent.localStartTimeText(zoneId: ZoneId): String =
    localStart(zoneId).format(TIME_FORMATTER)

private fun CalendarEvent.localEndTimeText(zoneId: ZoneId): String =
    localEnd(zoneId).format(TIME_FORMATTER)

private fun CalendarEvent.durationMinutes(): Long = (endsAtEpochMillis - startsAtEpochMillis) / 60_000L

private fun firstDayOffset(month: YearMonth): Int =
    month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value

private fun monthFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofPattern("LLLL yyyy", RUSSIAN_LOCALE)

private fun selectedDateFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM, EEEE", RUSSIAN_LOCALE)

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val RUSSIAN_LOCALE = Locale("ru")
private val WEEKDAY_LABELS = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT_STANDALONE, RUSSIAN_LOCALE) }
