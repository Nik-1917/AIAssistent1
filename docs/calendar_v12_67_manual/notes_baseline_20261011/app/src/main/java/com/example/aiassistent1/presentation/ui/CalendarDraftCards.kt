package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.CalendarEventField

@Composable
internal fun CalendarDraftCard(draft: CalendarEventDraftUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val saved = draft.savedEventId != null
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (saved) Icons.Default.CheckCircle else Icons.Default.CalendarMonth, contentDescription = null)
                Text(
                    draft.fieldText(CalendarEventField.Title).ifBlank { "Новое событие" },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(if (saved) "Создано" else "Черновик", style = MaterialTheme.typography.labelMedium)
            }
            val date = draft.fieldText(CalendarEventField.Date).ifBlank { "Дата не указана" }
            val time = draft.fieldText(CalendarEventField.Time).ifBlank { "Время не указано" }
            Text("${LocalUserDateTimeFormatter.current.value(date)} · $time")
            if (draft.missingFields.isNotEmpty()) {
                Text("Не указаны: ${draft.missingFields.joinToString { it.shortLabel() }}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "${draft.fieldText(CalendarEventField.DurationMinutes)} мин · Ценность: ${draft.fieldText(CalendarEventField.Value)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!saved) {
                if (draft.isSaving) Text("Сохранение события…", style = MaterialTheme.typography.bodySmall)
                else if (draft.saveRequested) Text("Сохранение требует проверки", style = MaterialTheme.typography.bodySmall)
                draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onOpen) {
                    Text(when {
                        draft.saveRequested -> "Проверить сохранение"
                        draft.missingFields.isNotEmpty() -> "Дополнить"
                        else -> "Проверить и создать"
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDraftListSheet(
    drafts: List<CalendarEventDraftUiState>, onOpen: (String) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Черновики · ${drafts.size}", style = MaterialTheme.typography.headlineSmall) }
            if (drafts.isEmpty()) item { Text("Нет незавершённых событий") }
            items(drafts, key = { it.requestId }) { draft ->
                CalendarDraftCard(draft, onOpen = { onOpen(draft.requestId) })
            }
            item { TextButton(onClick = onDismiss) { Text("Закрыть") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDraftEditorSheet(
    draft: CalendarEventDraftUiState,
    onValueChange: (CalendarEventField, String) -> Unit,
    onNotesChange: (String) -> Unit,
    onFormat: (CalendarEventField) -> Unit,
    onVoiceInput: (CalendarEventField) -> Unit,
    onCreate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    storageError: String? = null,
) {
    var confirmDelete by remember(draft.requestId) { mutableStateOf(false) }
    val editable = draft.isEditable && !draft.isFormatting
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Черновик события", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть редактор") }
            }
            Text("Можно закрыть и продолжить позже", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(CalendarEventField.entries, key = { it.name }) { field ->
                    Column {
                        OutlinedTextField(
                            value = draft.fieldText(field), onValueChange = { onValueChange(field, it) },
                            label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, enabled = editable,
                            trailingIcon = {
                                Row {
                                    if (field != CalendarEventField.Title && draft.fieldText(field).isNotBlank()) {
                                        IconButton(onClick = { onFormat(field) }, enabled = editable && !draft.isVoiceInputActive) {
                                            Icon(Icons.Default.AutoFixHigh, "Распознать: ${field.shortLabel()}")
                                        }
                                    }
                                    IconButton(onClick = { onVoiceInput(field) }, enabled = editable) {
                                        val listening = draft.isVoiceInputActive && draft.activeField == field
                                        Icon(if (listening) Icons.Default.MicOff else Icons.Default.Mic, "Голосовой ввод: ${field.shortLabel()}")
                                    }
                                }
                            },
                        )
                        if (draft.isFormatting && draft.formattingField == field) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp))
                                Text("Распознавание значения…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = draft.notes.orEmpty(), onValueChange = onNotesChange, enabled = editable,
                        label = { Text("Примечание (необязательно)") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                }
                item {
                    runCatching { draft.resolveInputs().endDisplayText() }.getOrNull()?.let {
                        Text("Окончание: ${LocalUserDateTimeFormatter.current.value(it)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    if (draft.saveRequested && !draft.isSaving) {
                        Text("Повторите сохранение, чтобы проверить результат предыдущей попытки.")
                    }
                    if (draft.missingFields.isNotEmpty()) Text("Заполните: ${draft.missingFields.joinToString { it.shortLabel() }}")
                }
            }
            draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = onCreate, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !draft.isSaving && !draft.isFormatting && !draft.isVoiceInputActive && draft.missingFields.isEmpty(),
            ) { Text(if (draft.isSaving) "Сохранение…" else if (draft.saveRequested) "Повторить сохранение" else "Создать событие") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { confirmDelete = true }, enabled = draft.isEditable) {
                    Text("Удалить черновик", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Готово") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("Удалить черновик?") },
            text = { Text("Введённые данные этого черновика будут удалены.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Оставить") } },
        )
    }
}

private fun CalendarEventField.shortLabel(): String = when (this) {
    CalendarEventField.Title -> "название"
    CalendarEventField.Date -> "дата"
    CalendarEventField.Time -> "время"
    CalendarEventField.DurationMinutes -> "длительность"
    CalendarEventField.Value -> "ценность"
}
