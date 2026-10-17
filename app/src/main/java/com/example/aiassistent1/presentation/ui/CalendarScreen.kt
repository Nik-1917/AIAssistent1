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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
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
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val calendarListState = rememberLazyListState()
    var eventToEdit by remember { mutableStateOf<CalendarEvent?>(null) }
    var eventToDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    var isCreatingEvent by remember { mutableStateOf(false) }
    val selectedDayEvents = uiState.events.filter { it.localDate() == uiState.selectedDate }
    val eventCounts = remember(uiState.events) {
        uiState.events.groupingBy { it.localDate() }.eachCount()
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
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
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
                    TextButton(onClick = onOpenChat) {
                        Text("Чат")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { isCreatingEvent = true },
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
                            onEdit = { eventToEdit = event },
                            onDelete = { eventToDelete = event },
                        )
                    }
                }
            }
        }
    }

    if (isCreatingEvent) {
        CalendarEventEditorDialog(
            date = uiState.selectedDate,
            event = null,
            onDismiss = { isCreatingEvent = false },
            onSave = { title, startTime, endTime, value, notes ->
                viewModel.createEvent(title, uiState.selectedDate, startTime, endTime, value, notes)
                isCreatingEvent = false
            },
        )
    }

    eventToEdit?.let { event ->
        CalendarEventEditorDialog(
            date = event.localDate(),
            event = event,
            onDismiss = { eventToEdit = null },
            onSave = { title, startTime, endTime, value, notes ->
                viewModel.updateEvent(event.id, title, event.localDate(), startTime, endTime, value, notes)
                eventToEdit = null
            },
        )
    }

    eventToDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { eventToDelete = null },
            title = { Text("Удалить событие?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("«${event.title}» будет удалено из локального календаря без возможности восстановления.")
                    CalendarNotesText(event.notes)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEvent(event.id)
                        eventToDelete = null
                    },
                ) { Text("Удалить") }
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
                AssistChip(
                    onClick = onEdit,
                    label = {
                        Text(if (event.hasEndTime) {
                            LocalUserDateTimeFormatter.current.range(event.startsAtEpochMillis, event.endsAtEpochMillis)
                        } else event.localStartTimeText())
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
) {
    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var startTimeText by remember(event) { mutableStateOf(event?.localStartTimeText() ?: "09:00") }
    var durationText by remember(event) {
        mutableStateOf(event?.let { if (it.hasEndTime) it.durationMinutes().toString() else "" } ?: "60")
    }
    var endTimeText by remember(event) {
        mutableStateOf(event?.let { if (it.hasEndTime) it.localEndTimeText() else "" } ?: "10:00")
    }
    var valueText by remember(event) { mutableStateOf(event?.value?.toString().orEmpty()) }
    var notesText by remember(event) { mutableStateOf(event?.notes.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }

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
                if (event != null) Text("Дата: ${LocalUserDateTimeFormatter.current.date(date)}")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
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
                            start.plusMinutes(duration).format(TIME_FORMATTER)
                        }.onSuccess { endTimeText = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                            start.plusMinutes(duration).format(TIME_FORMATTER)
                        }.onSuccess { endTimeText = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                            val minutes = java.time.Duration.between(start, end).toMinutes()
                                .let { if (it <= 0) it + 24 * 60 else it }
                            require(minutes > 0)
                            minutes.toString()
                        }.onSuccess { durationText = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Окончание (ЧЧ:ММ)") },
                    supportingText = if (endTimeText.isBlank()) { { Text("Не заполнено") } } else null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ценность события") },
                    supportingText = if (valueText.isBlank()) { { Text("Не заполнено") } } else null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    modifier = Modifier.fillMaxWidth(),
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
                        val start = CalendarTime.time(startTimeText.trim())
                        val end = endTimeText.trim().takeIf { it.isNotEmpty() }?.let(CalendarTime::time)
                        val duration = end?.let {
                            java.time.Duration.between(start, it).toMinutes()
                                .let { minutes -> if (minutes <= 0) minutes + 24 * 60 else minutes }
                        }
                        if (duration == null) {
                            require(durationText.isBlank()) { "Проверьте длительность и окончание." }
                        } else {
                            require(durationText.trim().toLongOrNull() == duration) { "Проверьте длительность и окончание." }
                        }
                        val value = valueText.trim().takeIf { it.isNotEmpty() }?.let {
                            requireNotNull(it.toLongOrNull()) { "Ценность должна быть целым числом." }
                        }
                        CalendarEventEditorResult(title, start, end, value, notesText)
                    }
                    parsed.onSuccess { (savedTitle, start, end, value, notes) -> onSave(savedTitle, start, end, value, notes) }
                        .onFailure { validationError = it.message ?: "Проверьте введённые данные." }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private data class CalendarEventEditorResult(
    val title: String,
    val startTime: LocalTime,
    val endTime: LocalTime?,
    val value: Long?,
    val notes: String,
)

private fun CalendarEvent.localDate(): LocalDate =
    Instant.ofEpochMilli(startsAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun CalendarEvent.localStartTimeText(): String =
    Instant.ofEpochMilli(startsAtEpochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)

private fun CalendarEvent.localEndTimeText(): String =
    Instant.ofEpochMilli(endsAtEpochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)

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
