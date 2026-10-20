package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
) {
    var menu by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Режим времени", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = value.mode == CalendarTimeMode.FIXED, enabled = enabled,
                onClick = { onChange(value.copy(mode = CalendarTimeMode.FIXED)) }, label = { Text("Фиксированный момент") })
            FilterChip(selected = value.mode == CalendarTimeMode.FLOATING, enabled = enabled,
                onClick = { onChange(value.copy(mode = CalendarTimeMode.FLOATING)) }, label = { Text("Всегда по местному времени") })
        }
        if (value.mode == CalendarTimeMode.FLOATING) Text("Указанное время сохраняется при смене часового пояса.", style = MaterialTheme.typography.bodySmall)
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
            if (value.repeat == CalendarRepeat.WEEKLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(selected = day in value.weekdays, enabled = enabled,
                        onClick = { onChange(value.copy(weekdays = if (day in value.weekdays) value.weekdays - day else value.weekdays + day)) },
                        label = { Text(WEEKDAYS[day.value - 1]) })
                }
            }
            if (value.repeat == CalendarRepeat.DATES) {
                OutlinedTextField(value = value.datesText, onValueChange = { onChange(value.copy(datesText = it)) }, enabled = enabled,
                    label = { Text("Даты через запятую (ГГГГ-ММ-ДД)") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { dateTarget = "dates" }, enabled = enabled) { Text("Добавить дату из календаря") }
            }
            if (value.repeat in setOf(CalendarRepeat.DAYS, CalendarRepeat.WEEKS, CalendarRepeat.MONTHS)) {
                OutlinedTextField(value = value.intervalText, onValueChange = { onChange(value.copy(intervalText = it)) }, enabled = enabled,
                    label = { Text("Интервал (1–999)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                if (value.repeat == CalendarRepeat.MONTHS) Text("Месяцы, в которых нет выбранного числа, пропускаются.", style = MaterialTheme.typography.bodySmall)
            }
            if (value.repeat != CalendarRepeat.NONE) {
                Row {
                    FilterChip(selected = !value.hasUntil, enabled = enabled, onClick = { onChange(value.copy(hasUntil = false)) }, label = { Text("Без окончания") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = value.hasUntil, enabled = enabled, onClick = { onChange(value.copy(hasUntil = true)) }, label = { Text("До даты") })
                }
                if (value.hasUntil) {
                    OutlinedTextField(value = value.untilText, onValueChange = { onChange(value.copy(untilText = it)) }, enabled = enabled,
                        label = { Text("Последний день (ГГГГ-ММ-ДД)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { dateTarget = "until" }, enabled = enabled) { Text("Выбрать дату окончания") }
                }
            }
        } else Text("Изменяется только это повторение.", style = MaterialTheme.typography.bodySmall)
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
        }, dismissButton = { TextButton(onClick = { dateTarget = null }) { Text("Отмена") } }) { DatePicker(state = picker) }
    }
}
