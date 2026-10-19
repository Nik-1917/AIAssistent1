package com.example.aiassistent1.presentation.ui

import android.content.Intent
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
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.VoiceActivationMode
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
fun AudioSettingsSection(keywordControls: PersonalKeywordControls? = null,
    settingsRepository: SettingsRepository? = null) {
    val context = LocalContext.current
    val settings = remember(context, settingsRepository) { settingsRepository ?: AppModule.provideSettingsRepository(context) }
    val prefs by settings.audioPreferences.collectAsStateWithLifecycle(initialValue = null)
    val assistantName by settings.assistantDisplayName.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var profileBusy by remember { mutableStateOf(false) }
    val controlsBusy = saving || profileBusy
    fun save(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { block(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "Не удалось сохранить настройку" }
            finally { saving = false }
        }
    }
    val current = prefs ?: return
    val currentAssistantName = assistantName ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        VoiceActivationSettings(
            controls = keywordControls, current = current, currentName = currentAssistantName,
            settingsBusy = controlsBusy,
            onSelectMode = { mode -> save {
                if (keywordControls != null) keywordControls.selectActivationMode(mode)
                else settings.setVoiceActivationMode(mode)
            } },
            onSaveName = { name -> save { settings.setAssistantDisplayName(name) } },
            onInterruptionChanged = { value -> save { settings.setVoiceInterruptionEnabled(value) } },
            onBusyChanged = { profileBusy = it },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("Общие настройки звука", style = MaterialTheme.typography.titleMedium)
        PlaybackCaptureSettings()
        MessageSentSoundSetting(current.messageSentSoundEnabled, !saving) {
            save { settings.setMessageSentSoundEnabled(it) }
        }
        AudioToggle("Звук отмены ожидания", "Если после сигнала активации речь не началась за 5 секунд — сигнал и возврат в «Ожидание имени».",
            current.activationTimeoutSoundEnabled, !saving) { save { settings.setActivationTimeoutSoundEnabled(it) } }
        AudioToggle("Вибрация перед ответом", "", current.haptics, !saving) { save { settings.setHapticFeedbackEnabled(it) } }
    }
}

@Composable
internal fun ConferenceAudioSettings(settingsRepository: SettingsRepository? = null) {
    val context = LocalContext.current
    val settings = remember(context, settingsRepository) { settingsRepository ?: AppModule.provideSettingsRepository(context) }
    val prefs by settings.audioPreferences.collectAsStateWithLifecycle(initialValue = null)
    val current = prefs ?: return
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { block(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Не удалось сохранить настройку" }
            finally { saving = false }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) save {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings.setRecordingDirectory(uri.toString())
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (current.recordingDirectory == null) "Хранение: приватная папка приложения" else "Хранение: выбранная папка устройства или SD-карты",
            style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { folderPicker.launch(null) }, enabled = !saving) { Text("Выбрать папку") }
            if (current.recordingDirectory != null) TextButton(onClick = { save { settings.setRecordingDirectory(null) } }, enabled = !saving) { Text("Сбросить") }
        }
        AudioToggle("Шумоподавление при записи", "Выключено — сохраняется исходный звук",
            current.conferenceEffects, !saving) { save { settings.setConferenceModeEffectsEnabled(it) } }
        AudioToggle("Автоматическая выжимка", "После записи, когда модель свободна. Обработка локальная.",
            current.autoSummary, !saving) { save { settings.setAutoSummaryEnabled(it) } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun VoiceActivationSettings(
    controls: PersonalKeywordControls?, current: AudioPreferences, currentName: String, settingsBusy: Boolean,
    onSelectMode: (VoiceActivationMode) -> Unit, onSaveName: (String) -> Unit,
    onInterruptionChanged: (Boolean) -> Unit,
    onBusyChanged: (Boolean) -> Unit = {},
) {
    var wordRecordingVersion by remember { mutableIntStateOf(0) }
    Text("Способ активации", style = MaterialTheme.typography.titleMedium)
    for ((mode, label) in listOf(VoiceActivationMode.DIRECT to "Обычный голосовой ввод",
        VoiceActivationMode.NAME to "Активация по имени", VoiceActivationMode.PERSONAL_WORD to "Персональное слово v3")) {
        val selected = current.activationMode == mode
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = !settingsBusy, role = Role.RadioButton,
            onClick = { if (!selected) onSelectMode(mode) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = !settingsBusy)
            Text(label, Modifier.padding(start = 8.dp))
        }
        if (selected) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(voiceModeDescription(mode), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("voice_mode_description_${mode.name}"))
                key(mode, wordRecordingVersion) {
                    AssistantNameSettings(currentName, !settingsBusy, onSave = onSaveName)
                }
                if (mode == VoiceActivationMode.PERSONAL_WORD) {
                    PersonalKeywordSettings(controls, current, settingsBusy,
                        onWordRecorded = { wordRecordingVersion++ }, onBusyChanged = onBusyChanged)
                }
                if (mode != VoiceActivationMode.DIRECT) {
                    Text("Звук активации: как при отправке сообщения",
                        style = MaterialTheme.typography.bodyMedium)
                    VoiceInterruptionSetting(current, !settingsBusy, onInterruptionChanged)
                }
            }
        }
    }
}

private fun voiceModeDescription(mode: VoiceActivationMode): String = when (mode) {
    VoiceActivationMode.DIRECT -> "Включите микрофон и произнесите команду без обращения по имени. " +
        "Поле ниже задаёт имя ассистента в заголовке чата. Пустое поле возвращает AI Assistant. " +
        "Во время озвучивания ответа голосовой ввод приостановлен; для новой команды нажмите микрофон. " +
        "Если включён режим диалога, после ответа микрофон включится автоматически."
    VoiceActivationMode.NAME -> "Укажите имя в поле ниже и включите голосовой режим. Это же имя появится в заголовке чата " +
        "и будет использоваться для вызова. Например: «Алиса, покажи заметки». Можно произнести только имя, " +
        "дождаться сигнала и в течение 5 секунд начать команду. Если речь не началась, ожидание команды отменяется: " +
        "звучит сигнал отмены, если он включён, и возвращается «Ожидание имени». Начатую вовремя команду можно закончить. " +
        "Сохранение нового имени меняет обращение для вызова. " +
        "Запись голоса не требуется. Переключатель ниже разрешает прерывать ответ обращением по этому имени."
    VoiceActivationMode.PERSONAL_WORD -> "Запишите одно слово своим голосом. Для записи выключите голосовой режим " +
        "и остановите конференцию. Распознанное имя автоматически появится в поле ниже и в заголовке чата. " +
        "Текст имени можно исправить; для изменения произносимого слова нужна перезапись. Затем включите голосовой режим: " +
        "произнесите записанное слово, дождитесь сигнала и в течение 5 секунд начните отдельную команду. " +
        "Если речь не началась, звучит сигнал отмены, если он включён, и возвращается «Ожидание имени» — ожидание персонального слова. " +
        "Начатую вовремя команду можно закончить. " +
        "До готовности модели и записи слова активация недоступна. На устройстве сохраняется отпечаток слова, " +
        "исходное аудио не сохраняется. Режим экспериментальный; длинные фразы и слово с командой без паузы пока не проверены."
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
    Text(if (status?.canActivate == true) "Состояние: готово к активации" else "Состояние: не готово к активации",
        style = MaterialTheme.typography.titleSmall)
    Text(status?.message ?: if (controls == null) "Запись в этом окне недоступна" else "Проверка модели…",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && !settingsBusy && status?.modelAvailable == true, onClick = {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                perform(record = true) { it.enrollKeyword() }
            else permission.launch(android.Manifest.permission.RECORD_AUDIO)
        }) { Text(if (status?.profileReady == true) "Перезаписать слово" else "Записать слово") }
        TextButton(enabled = !busy && !settingsBusy && controls != null && status?.profileReady == true, onClick = { perform { it.deleteKeyword() } }) {
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
internal fun VoiceInterruptionSetting(current: AudioPreferences, enabled: Boolean, onChange: (Boolean) -> Unit) {
    if (current.activationMode == VoiceActivationMode.DIRECT) {
        Text("Во время ответа голосовой ввод приостановлен. Чтобы сказать новую команду, нажмите микрофон.",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val description = if (!current.allowVoiceInterruption) {
        "В голосовом режиме микрофон приостановлен на время ответа. После ответа ожидание активации возобновляется."
    } else if (current.activationMode == VoiceActivationMode.NAME) {
        "Чтобы остановить диктора, обратитесь по выбранному имени."
    } else {
        "Чтобы остановить диктора, произнесите персональное слово, дождитесь сигнала и скажите команду."
    }
    AudioToggle("Прерывать ответ голосом", description, current.allowVoiceInterruption, enabled, onChange)
}

@Composable
internal fun MessageSentSoundSetting(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    AudioToggle("Звук отправки сообщения", "Короткий сигнал, когда сообщение принято в обработку",
        checked, enabled, onChange)
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
