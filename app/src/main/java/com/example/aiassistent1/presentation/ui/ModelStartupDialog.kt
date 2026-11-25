package com.example.aiassistent1.presentation.ui

import android.os.SystemClock
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.viewmodel.ChatUiState
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import com.example.aiassistent1.presentation.viewmodel.ModelStartup
import kotlinx.coroutines.delay

@Composable
internal fun ModelStartupDialog(
    state: ChatUiState,
    onStart: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    val startup = state.modelStartup
    if (startup == ModelStartup.Ready || state.isProcessing) return
    val loading = startup as? ModelStartup.Loading
    val importing = state.modelState as? ModelState.Importing
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var choosing by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { backDispatcher?.onBackPressed() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(when {
                loading != null -> "Запускаем модель — «${loading.fileName}»"
                importing != null -> "Импортируем модель"
                else -> "ИИ не запущен"
            })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    loading != null -> {
                        StartupCountdown(loading.startedAtMillis)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    importing != null -> LinearProgressIndicator(
                        progress = { importing.progress }, modifier = Modifier.fillMaxWidth(),
                    )
                    else -> {
                        Text("Первый запуск может занять до минуты.")
                        if (state.selectedModel.isNotBlank()) Text(state.selectedModel,
                            style = MaterialTheme.typography.bodySmall)
                        if (startup is ModelStartup.Failed) Text(startup.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (loading == null && importing == null) Button(
                enabled = state.modelAvailability != ModelAvailability.Checking,
                onClick = { if (state.modelAvailability == ModelAvailability.Available) onStart() else choosing = true },
            ) { Text("Запустить ИИ") }
        },
        dismissButton = {
            if (loading == null && importing == null) Box {
                TextButton(onClick = { choosing = true }) { Text("Выбрать модель") }
                ModelPickerMenu(
                    expanded = choosing, onDismiss = { choosing = false }, modelState = state.modelState,
                    selectedModel = state.selectedModel, availableModels = state.availableModels,
                    onSelectModel = onSelect, onDeleteModel = onDelete, onLoadModel = onImport,
                )
            }
        },
    )
}

@Composable
private fun StartupCountdown(startedAtMillis: Long) {
    val deadline = startedAtMillis + 59_000L
    fun remaining() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    val seconds by produceState((remaining() + 999L) / 1000L, startedAtMillis) {
        while (true) {
            val millis = remaining()
            value = (millis + 999L) / 1000L
            if (millis == 0L) break
            delay((millis - 1) % 1000L + 1)
        }
    }
    Text(if (seconds > 0) "$seconds с" else "Загрузка продолжается…",
        style = MaterialTheme.typography.titleMedium)
}
