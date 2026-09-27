package com.example.aiassistent1.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.domain.interfaces.VoiceProfileControls
import com.example.aiassistent1.domain.interfaces.VoiceProfileStatus
import com.example.aiassistent1.domain.model.AudioPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
internal fun VoiceProfileSettings(controls: VoiceProfileControls?, current: AudioPreferences, settingsBusy: Boolean,
    onBusyChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(controls) { mutableStateOf<VoiceProfileStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var enrolling by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val flow = remember(controls) { controls?.observeVoiceRecording() ?: emptyFlow() }
    val recording by flow.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(controls, current.revision, current.policyRevision) { status = controls?.voiceProfileStatus() }
    fun perform(record: Boolean = false, action: suspend (VoiceProfileControls) -> Unit) {
        val target = controls ?: return
        if (busy) return
        busy = true; enrolling = record; message = null; onBusyChanged(true)
        operation = scope.launch {
            try {
                action(target)
                status = target.voiceProfileStatus()
                message = if (record) "Голос сохранён. Проверка включается переключателем Voice ID." else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Не удалось настроить Voice ID" }
            catch (_: LinkageError) { message = "Библиотека проверки голоса недоступна" }
            finally { busy = false; enrolling = false; onBusyChanged(false) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) perform(record = true) { it.enrollVoice() }
        else message = "Для записи голоса нужно разрешение на микрофон"
    }
    AudioToggle("Voice ID", "Проверять владельца. Можно использовать отдельно или вместе с выбранной активацией.",
        current.voiceIdEnabled, !busy && !settingsBusy && controls != null) { enabled -> perform { it.selectVoiceId(enabled) } }
    Text(status?.message ?: if (controls == null) "Запись голоса в этом окне недоступна" else "Проверка профиля голоса…",
        style = MaterialTheme.typography.bodySmall)
    if (current.voiceIdEnabled && status?.profileReady == false)
        Text("Voice ID включён. Голосовые команды станут доступны после записи голоса.", style = MaterialTheme.typography.bodySmall)
    Text("Выключите голосовой режим и остановите конференцию. Произнесите короткую фразу обычным голосом. Имя ассистента и слово v3 не нужны. Сохраняется только отпечаток голоса.",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && !settingsBusy && controls != null, onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                perform(record = true) { it.enrollVoice() }
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text(if (status?.profileReady == true) "Перезаписать голос" else "Записать голос") }
        TextButton(enabled = !busy && !settingsBusy && controls != null,
            onClick = { perform { it.deleteVoice() } }) { Text("Удалить голос") }
    }
    if (enrolling) {
        Text(if (recording) "Идёт запись голоса: произнесите фразу и сделайте паузу" else "Подготовка микрофона…")
        TextButton(onClick = { scope.launch {
            operation?.cancelAndJoin()
            status = controls?.voiceProfileStatus()
            message = "Запись остановлена. Состояние профиля голоса обновлено."
        } }) { Text("Отменить запись голоса") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
}
