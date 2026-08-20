package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.calendar.core.domain.CalendarEvent
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
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var eventToEdit by remember { mutableStateOf<CalendarEvent?>(null) }
    var eventToDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    var isCreatingEvent by remember { mutableStateOf(false) }
    val selectedDayEvents = uiState.events.filter { it.localDate() == uiState.selectedDate }

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
            TopAppBar(
                title = {
                    Text(
                        text = "Мой календарь",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
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
                    eventDates = uiState.events.mapTo(mutableSetOf()) { it.localDate() },
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

    if (isCreatingEvent) {
        CalendarEventEditorDialog(
            date = uiState.selectedDate,
            event = null,
            onDismiss = { isCreatingEvent = false },
            onSave = { title, time, duration ->
                viewModel.createEvent(title, uiState.selectedDate, time, duration)
                isCreatingEvent = false
            },
        )
    }

    eventToEdit?.let { event ->
        CalendarEventEditorDialog(
            date = event.localDate(),
            event = event,
            onDismiss = { eventToEdit = null },
            onSave = { title, time, duration ->
                viewModel.updateEvent(event.id, title, event.localDate(), time, duration)
                eventToEdit = null
            },
        )
    }

    eventToDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { eventToDelete = null },
            title = { Text("Удалить событие?") },
            text = { Text("«${event.title}» будет удалено из локального календаря без возможности восстановления.") },
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
    eventDates: Set<LocalDate>,
    onDateSelected: (LocalDate) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp),
            ) {
                items(WEEKDAY_LABELS.size) { index ->
                    Text(
                        text = WEEKDAY_LABELS[index],
                        modifier = Modifier.padding(vertical = 6.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(firstDayOffset(month)) {
                    Spacer(modifier = Modifier.aspectRatio(1f))
                }
                items(month.lengthOfMonth()) { index ->
                    val date = month.atDay(index + 1)
                    MonthDayCell(
                        date = date,
                        isSelected = date == selectedDate,
                        hasEvents = date in eventDates,
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
    hasEvents: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
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
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(if (hasEvents) 5.dp else 0.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary),
        )
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
                AssistChip(
                    onClick = onEdit,
                    label = { Text(event.timeRange()) },
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
private fun CalendarEventEditorDialog(
    date: LocalDate,
    event: CalendarEvent?,
    onDismiss: () -> Unit,
    onSave: (title: String, startTime: LocalTime, durationMinutes: Int) -> Unit,
) {
    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var startTimeText by remember(event) { mutableStateOf(event?.localStartTimeText() ?: "09:00") }
    var durationText by remember(event) { mutableStateOf(event?.durationMinutes()?.toString() ?: "60") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "Новое событие" else "Изменить событие") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Дата: ${date.format(selectedDateFormatter())}")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = startTimeText,
                    onValueChange = { startTimeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Начало (ЧЧ:ММ)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Длительность, минут") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
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
                        val time = LocalTime.parse(startTimeText, TIME_FORMATTER)
                        val duration = durationText.toInt()
                        require(duration > 0) { "Длительность должна быть больше нуля." }
                        time to duration
                    }
                    parsed.onSuccess { (time, duration) -> onSave(title, time, duration) }
                        .onFailure { validationError = it.message ?: "Проверьте введённые данные." }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun CalendarEvent.localDate(): LocalDate =
    Instant.ofEpochMilli(startsAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun CalendarEvent.localStartTimeText(): String =
    Instant.ofEpochMilli(startsAtEpochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)

private fun CalendarEvent.timeRange(): String =
    "${localStartTimeText()}–${Instant.ofEpochMilli(endsAtEpochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)}"

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
