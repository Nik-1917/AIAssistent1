package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.R
import com.example.aiassistent1.domain.model.CalendarCreationMode
import com.example.aiassistent1.presentation.formatting.CalendarTimeZoneLabels
import kotlinx.coroutines.launch
import java.time.ZoneId

private data class TimeModeOption(val mode: CalendarCreationMode, val title: String, val example: Int)

private val timeModeOptions = listOf(
    TimeModeOption(CalendarCreationMode.FIXED_MOMENT, "Фиксированный момент", R.string.calendar_example_fixed),
    TimeModeOption(CalendarCreationMode.LOCAL_TIME, "Всегда по местному времени", R.string.calendar_example_local),
    TimeModeOption(CalendarCreationMode.PHONE_TIME, "По времени телефона", R.string.calendar_example_phone),
    TimeModeOption(CalendarCreationMode.HOME_TIME, "Домашний пояс", R.string.calendar_example_home),
)

@Composable
internal fun CalendarTimeModeSettings() {
    val repository = LocalCalendarTimeZones.current ?: return
    val state = LocalCalendarTimeZone.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<CalendarCreationMode?>(null) }
    var choosingHome by remember { mutableStateOf(false) }
    var choosingDisplay by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val checkColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFF22733C) else Color(0xFF83DA99)

    Column(Modifier.fillMaxWidth().selectableGroup()) {
        for ((mode, title, example) in timeModeOptions) {
            val selected = state.isLoaded && mode == state.creationMode
            val showExample = selected && expanded == mode
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = selected, enabled = state.isLoaded && !saving, role = Role.RadioButton) {
                        when {
                            selected && state.error == null -> expanded = if (showExample) null else mode
                            mode == CalendarCreationMode.HOME_TIME && state.homeZoneId == null -> choosingHome = true
                            else -> {
                                saving = true
                                scope.launch {
                                    try {
                                        repository.selectCreationMode(mode)
                                        if (repository.state.value.error == null) expanded = mode
                                    } finally { saving = false }
                                }
                            }
                        }
                    }
                    .semantics {
                        if (selected) stateDescription = if (showExample) "Выбрано, описание раскрыто" else "Выбрано, описание свёрнуто"
                    }.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = checkColor)
                    Icon(if (showExample) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
            }
            if (showExample) {
                val html = stringResource(example)
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(remember(html) { AnnotatedString.fromHtml(html) }, style = MaterialTheme.typography.bodyMedium)
                    when (mode) {
                        CalendarCreationMode.HOME_TIME -> TextButton(onClick = { choosingHome = true }) {
                            Text("Домашний пояс · " + CalendarTimeZoneLabels.name(ZoneId.of(state.homeZoneId)))
                        }
                        CalendarCreationMode.FIXED_MOMENT -> TextButton(onClick = { choosingDisplay = true }) {
                            Text("Ввод по поясу календаря · " + CalendarTimeZoneLabels.name(state.zoneId))
                        }
                        CalendarCreationMode.LOCAL_TIME -> if (state.selectedZoneId != null) {
                            Text("Календарь закреплён за поясом ${CalendarTimeZoneLabels.name(state.zoneId)}. Чтобы в поездке прогулка приходилась на 09:00 по местному времени, выберите для календаря пояс телефона.",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { choosingDisplay = true }) { Text("Выбрать пояс календаря") }
                        }
                        CalendarCreationMode.PHONE_TIME -> Unit
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (choosingHome) CalendarTimeZonePicker(repository, state, onDismiss = { choosingHome = false },
        forHome = true, onSelected = { expanded = CalendarCreationMode.HOME_TIME })
    if (choosingDisplay) CalendarTimeZonePicker(repository, state, onDismiss = { choosingDisplay = false })
}
