package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.calendar.core.domain.*
import com.example.aiassistent1.domain.model.CalendarScheduleInput
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

private val WEEKDAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScheduleControls(
    value: CalendarScheduleInput,
    onChange: (CalendarScheduleInput) -> Unit,
    enabled: Boolean = true,
    allowRepetition: Boolean = true,
    needsAttention: Boolean = false,
    expandedState: MutableState<Boolean> = remember { mutableStateOf(false) },
    fixedTimeContent: @Composable () -> Unit = {},
) {
    // Not saveable: every new opening starts collapsed; entered values belong to the editor.
    var expanded by expandedState
    var menu by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().testTag("calendar_time_mode_toggle")
            .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
            .clickable(role = Role.Button) { expanded = !expanded; if (!expanded) { menu = false; dateTarget = null } }
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Режим времени", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(if (expanded) "▴" else "▾")
        }
        if (needsAttention) Text("Требуется подтверждение пояса. Откройте «Режим времени».", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = value.mode == CalendarTimeMode.FIXED, enabled = enabled,
                    onClick = { onChange(value.copy(mode = CalendarTimeMode.FIXED)) }, label = { Text("Фиксированный момент") })
                if (value.mode == CalendarTimeMode.FIXED) {
                    Text("Для встреч и звонков. При создании вводите привычное время по текущему поясу телефона или выбранному домашнему поясу — оно пересчитывается в пояс календаря. По умолчанию используется пояс телефона. Изменение пояса календаря меняет отображение всех существующих и будущих событий. Сохранённый момент события остаётся прежним.",
                        style = MaterialTheme.typography.bodySmall)
                }
                FilterChip(selected = value.mode == CalendarTimeMode.FLOATING, enabled = enabled,
                    onClick = { onChange(value.copy(mode = CalendarTimeMode.FLOATING)) }, label = { Text("Всегда по местному времени") })
                if (value.mode == CalendarTimeMode.FLOATING) {
                    Text("Для привычек и распорядка. Указанные 09:00 остаются 09:00 при смене пояса календаря. Сохраняется время на часах, а соответствующий момент меняется.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (allowRepetition) {
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = enabled) { Text("Повторение: " + when (value.repeat) {
                        CalendarRepeat.NONE -> "Один раз"
                        CalendarRepeat.DATES -> "В выбранные даты"
                        CalendarRepeat.DAILY -> "Каждый день"
                        CalendarRepeat.WEEKLY -> "По дням недели"
                        CalendarRepeat.DAYS -> "Каждые N дней"
                        CalendarRepeat.WEEKS -> "Каждые N недель"
                        CalendarRepeat.MONTHS -> "Каждые N месяцев"
                    }) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        CalendarRepeat.entries.forEach { repeat ->
                            DropdownMenuItem(text = { Text(when (repeat) {
                                CalendarRepeat.NONE -> "Один раз"
                                CalendarRepeat.DATES -> "В выбранные даты"
                                CalendarRepeat.DAILY -> "Каждый день"
                                CalendarRepeat.WEEKLY -> "По дням недели"
                                CalendarRepeat.DAYS -> "Каждые N дней"
                                CalendarRepeat.WEEKS -> "Каждые N недель"
                                CalendarRepeat.MONTHS -> "Каждые N месяцев"
                            }) }, onClick = { menu = false; onChange(value.copy(repeat = repeat)) })
                        }
                    }
                }
                Text(when (value.repeat) {
                    CalendarRepeat.NONE -> "Событие произойдёт один раз в указанную дату."
                    CalendarRepeat.DATES -> "Событие повторяется только в выбранные даты."
                    CalendarRepeat.DAILY -> "Событие повторяется каждый день, начиная с указанной даты."
                    CalendarRepeat.WEEKLY -> "Событие повторяется каждую неделю в выбранные дни, начиная с даты события."
                    CalendarRepeat.DAYS -> "Событие повторяется через указанное число дней, начиная с даты события."
                    CalendarRepeat.WEEKS -> "Событие повторяется через указанное число недель в тот же день недели."
                    CalendarRepeat.MONTHS -> "Событие повторяется через указанное число месяцев в то же число. Месяцы без этого числа пропускаются."
                }, style = MaterialTheme.typography.bodySmall)
                if (value.repeat == CalendarRepeat.WEEKLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(selected = day in value.weekdays, enabled = enabled,
                            onClick = { onChange(value.copy(weekdays = if (day in value.weekdays) value.weekdays - day else value.weekdays + day)) },
                            label = { Text(WEEKDAYS[day.value - 1]) })
                    }
                }
                if (value.repeat == CalendarRepeat.DATES) {
                    OutlinedTextField(value = value.datesText, onValueChange = { onChange(value.copy(datesText = it)) }, enabled = enabled,
                        label = { Text("Даты через запятую (ГГГГ-ММ-ДД)") }, modifier = Modifier.fillMaxWidth().standardTextEditing())
                    TextButton(onClick = { dateTarget = "dates" }, enabled = enabled) { Text("Добавить дату из календаря") }
                }
                if (value.repeat in setOf(CalendarRepeat.DAYS, CalendarRepeat.WEEKS, CalendarRepeat.MONTHS)) {
                    OutlinedTextField(value = value.intervalText, onValueChange = { onChange(value.copy(intervalText = it)) }, enabled = enabled,
                        label = { Text("Интервал (1–999)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        modifier = Modifier.standardTextEditing())
                }
                if (value.repeat != CalendarRepeat.NONE) {
                    Row {
                        FilterChip(selected = !value.hasUntil, enabled = enabled, onClick = { onChange(value.copy(hasUntil = false)) }, label = { Text("Без окончания") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = value.hasUntil, enabled = enabled, onClick = { onChange(value.copy(hasUntil = true)) }, label = { Text("До даты") })
                    }
                    if (value.hasUntil) {
                        OutlinedTextField(value = value.untilText, onValueChange = { onChange(value.copy(untilText = it)) }, enabled = enabled,
                            label = { Text("Последний день (ГГГГ-ММ-ДД)") }, singleLine = true, modifier = Modifier.fillMaxWidth().standardTextEditing())
                        TextButton(onClick = { dateTarget = "until" }, enabled = enabled) { Text("Выбрать дату окончания") }
                    }
                }
            } else Text("Изменяется только это повторение.", style = MaterialTheme.typography.bodySmall)
            if (value.mode == CalendarTimeMode.FIXED) fixedTimeContent()
        }
    }
    dateTarget?.let { target ->
        val picker = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { dateTarget = null }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                val date = Instant.ofEpochMilli(picker.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                onChange(if (target == "until") value.copy(untilText = date.toString())
                    else value.copy(datesText = listOf(value.datesText.trim().trimEnd(','), date.toString()).filter(String::isNotEmpty).joinToString(", ")))
                dateTarget = null
            }) { Text("Выбрать") }
        }, dismissButton = { TextButton(onClick = { dateTarget = null }) { Text("Отмена") } }) {
            DatePicker(state = picker, modifier = Modifier.standardTextEditing())
        }
    }
}
