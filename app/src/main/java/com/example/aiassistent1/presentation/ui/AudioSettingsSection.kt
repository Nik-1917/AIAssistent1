package com.example.aiassistent1.presentation.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.di.AppModule
import kotlinx.coroutines.launch

@Composable
fun AudioSettingsSection() {
    val context = LocalContext.current
    val settings = remember { AppModule.provideSettingsRepository(context) }
    val prefs by settings.audioPreferences.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun save(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { block(); error = null }
            catch (e: Exception) { error = e.message ?: "Не удалось сохранить настройку" }
            finally { saving = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) save {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settings.setActivationSoundUri(uri.toString())
        }
    }
    val current = prefs ?: return
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) save {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings.setRecordingDirectory(uri.toString())
        }
    }
    var name by remember(current.wakeWord) { mutableStateOf(current.wakeWord) }
    val nativeKws = remember { AppModule.provideKeywordSpotter(context).isAvailable() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Активация и звук", style = MaterialTheme.typography.titleMedium)
        AudioToggle("Активация по имени", "Работает при включённом голосовом режиме",
            current.wakeWordEnabled, !saving) { save { settings.setWakeWordEnabled(it) } }
        OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) name = it },
            label = { Text("Имя ассистента") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = name != current.wakeWord && !saving, onClick = { save { settings.setCustomWakeWord(name) } }) {
            Text("Сохранить имя")
        }
        Text(if (nativeKws) "Активация: Sherpa KWS" else
            "Активация: локальное распознавание речи. Отдельная KWS-модель не установлена; имя определяется после паузы.",
            style = MaterialTheme.typography.bodySmall)
        AudioToggle("Voice ID", "Первое обращение по имени запоминает голос. Смена имени сбрасывает отпечаток.",
            current.voiceIdEnabled, !saving) { save { settings.setVoiceIdEnabled(it) } }
        if (current.voiceIdEnabled) Text(
            if (current.needsEnrollment) "Ожидается первое обращение по имени для запоминания голоса"
            else "Проверка голоса включена", style = MaterialTheme.typography.bodySmall)
        AudioToggle("Прерывать ответ голосом", "Микрофон остаётся активным во время ответа в голосовом режиме",
            current.bargeIn, !saving) { save { settings.setBargeInEnabled(it) } }
        AudioToggle("Вибрация перед ответом", "", current.haptics, !saving) { save { settings.setHapticFeedbackEnabled(it) } }
        Text(if (current.soundUri == null) "Звук активации: колокольчик" else "Звук активации: выбранный файл",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !saving) { Text("Выбрать звук") }
            if (current.soundUri != null) TextButton(onClick = { save { settings.setActivationSoundUri(null) } }, enabled = !saving) { Text("Сбросить") }
        }
        Text("Сигнал воспроизводится не более двух секунд.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Конференции", style = MaterialTheme.typography.titleMedium)
        Text(if (current.recordingDirectory == null) "Хранение: приватная папка приложения" else "Хранение: выбранная папка устройства или SD-карты",
            style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { folderPicker.launch(null) }) { Text("Выбрать папку") }
            if (current.recordingDirectory != null) TextButton(onClick = { save { settings.setRecordingDirectory(null) } }) { Text("Сбросить") }
        }
        AudioToggle("Шумоподавление при записи", "Выключено — сохраняется исходный звук",
            current.conferenceEffects, !saving) { save { settings.setConferenceModeEffectsEnabled(it) } }
        AudioToggle("Автоматическая выжимка", "После записи, когда модель свободна. Обработка локальная.",
            current.autoSummary, !saving) { save { settings.setAutoSummaryEnabled(it) } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AudioToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
