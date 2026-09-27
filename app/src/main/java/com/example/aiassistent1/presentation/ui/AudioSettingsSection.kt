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
import com.example.aiassistent1.domain.interfaces.PersonalKeywordControls
import com.example.aiassistent1.domain.interfaces.PersonalKeywordStatus
import com.example.aiassistent1.domain.interfaces.VoiceProfileControls
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.WakeWordEngine
import com.example.aiassistent1.domain.model.VoiceActivationMode
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
fun AudioSettingsSection(keywordControls: PersonalKeywordControls? = null, voiceControls: VoiceProfileControls? = null,
    settingsRepository: SettingsRepository? = null) {
    val context = LocalContext.current
    val settings = remember(context, settingsRepository) { settingsRepository ?: AppModule.provideSettingsRepository(context) }
    val prefs by settings.audioPreferences.collectAsStateWithLifecycle(initialValue = null)
    val assistantName by settings.assistantDisplayName.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var profileBusy by remember { mutableStateOf(false) }
    var wordRecordingVersion by remember { mutableIntStateOf(0) }
    val controlsBusy = saving || profileBusy
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
    val currentAssistantName = assistantName ?: return
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
        Text("Способ активации и проверка голоса настраиваются независимо. Работают при включённом голосовом режиме.",
            style = MaterialTheme.typography.bodySmall)
        key(wordRecordingVersion) {
            AssistantNameSettings(currentAssistantName, !controlsBusy) { name -> save { settings.setAssistantDisplayName(name) } }
        }
        PersonalKeywordSettings(keywordControls, current, controlsBusy,
            onWordRecorded = { wordRecordingVersion++ }, onBusyChanged = { profileBusy = it })
        OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) name = it },
            label = { Text("Слово для режима «Активация по имени»") }, enabled = !controlsBusy, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = name != current.wakeWord && !controlsBusy, onClick = { save { settings.setCustomWakeWord(name) } }) {
            Text("Сохранить слово активации")
        }
        Text(if (current.activationMode == VoiceActivationMode.DIRECT) "Команды без слова активации"
            else if (current.activationMode == VoiceActivationMode.PERSONAL_WORD) "Выбран режим персонального слова v3"
            else if (nativeKws) "Активация: Sherpa KWS" else
            "Активация по имени: локальное распознавание речи после паузы.",
            style = MaterialTheme.typography.bodySmall)
        VoiceProfileSettings(voiceControls, current, controlsBusy) { profileBusy = it }
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
internal fun PersonalKeywordSettings(controls: PersonalKeywordControls?, current: AudioPreferences, settingsBusy: Boolean,
    onWordRecorded: () -> Unit = {},
    onBusyChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(controls) { mutableStateOf<PersonalKeywordStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var enrolling by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val fallbackFlow = remember(controls) { controls?.observeActivationIssue() ?: emptyFlow() }
    val fallback by fallbackFlow.collectAsStateWithLifecycle(initialValue = null)
    val recordingFlow = remember(controls) { controls?.observeKeywordRecording() ?: emptyFlow() }
    val recording by recordingFlow.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(controls, current.policyRevision, current.wakeWordEngine) {
        status = controls?.keywordStatus()
    }
    fun perform(record: Boolean = false, action: suspend (PersonalKeywordControls) -> Unit) {
        val target = controls ?: return
        if (busy) return
        busy = true; enrolling = record; message = null
        onBusyChanged(true)
        operation = scope.launch {
            try {
                action(target)
                if (record) onWordRecorded()
                status = target.keywordStatus()
                message = if (record) "Слово сохранено. Распознанное имя появилось в поле «Имя ассистента» и в хедере." else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Не удалось настроить персональное слово" }
            catch (_: LinkageError) { message = "Не удалось загрузить аудиобиблиотеку" }
            finally { busy = false; enrolling = false; onBusyChanged(false) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) perform(record = true) { it.enrollKeyword() }
        else message = "Для записи слова нужно разрешение на микрофон"
    }
    Text("Способ активации", style = MaterialTheme.typography.titleSmall)
    for ((mode, label) in listOf(VoiceActivationMode.DIRECT to "Обычный голосовой ввод",
        VoiceActivationMode.NAME to "Активация по имени", VoiceActivationMode.PERSONAL_WORD to "Персональное слово v3")) {
        val selected = current.activationMode == mode
        val enabled = !busy && !settingsBusy && controls != null &&
            (mode != VoiceActivationMode.PERSONAL_WORD || selected || status?.canActivate == true)
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton,
            onClick = { perform { it.selectActivationMode(mode) } }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Text(label, Modifier.padding(start = 8.dp))
        }
    }
    Text("Запись персонального слова v3 · эксперимент", style = MaterialTheme.typography.titleSmall)
    Text("Произнесите одно слово, дождитесь сигнала, затем скажите команду. Длинные фразы и слово с командой без паузы пока не проверены.",
        style = MaterialTheme.typography.bodySmall)
    Text(status?.message ?: if (controls == null) "Запись в этом окне недоступна" else "Проверка модели…",
        style = MaterialTheme.typography.bodySmall)
    Text("Перед записью выключите голосовой режим и остановите конференцию. На устройстве сохраняются отпечаток слова и распознанное имя; исходное аудио не сохраняется.",
        style = MaterialTheme.typography.bodySmall)
    Text("Voice ID для записи и работы v3 не требуется. При выключенном Voice ID владелец отдельно не проверяется.",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && !settingsBusy && status?.modelAvailable == true, onClick = {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                perform(record = true) { it.enrollKeyword() }
            else permission.launch(android.Manifest.permission.RECORD_AUDIO)
        }) { Text(if (status?.profileReady == true) "Перезаписать слово" else "Записать слово") }
        TextButton(enabled = !busy && !settingsBusy && controls != null, onClick = { perform { it.deleteKeyword() } }) {
            Text("Удалить слово")
        }
    }
    if (enrolling) {
        Text(if (recording) "Идёт запись: произнесите одно слово и сделайте паузу" else "Подготовка и обработка записи…")
        TextButton(onClick = {
            scope.launch {
                operation?.cancelAndJoin()
                status = controls?.keywordStatus()
                message = "Запись остановлена. Состояние сохранённого слова обновлено."
            }
        }) { Text("Отменить запись") }
    }
    fallback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun AudioToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
