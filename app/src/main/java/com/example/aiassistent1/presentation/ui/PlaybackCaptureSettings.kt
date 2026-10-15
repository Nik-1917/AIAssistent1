package com.example.aiassistent1.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiassistent1.di.AppModule

@Composable
internal fun PlaybackCaptureSettings() {
    val context = LocalContext.current
    val controller = remember(context) { AppModule.providePlaybackCaptureController(context) }
    val state by controller.state.collectAsStateWithLifecycle()
    var requesting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        requesting = false
        controller.start(result.resultCode, result.data)
    }
    fun requestProjection() {
        try {
            requesting = true
            projection.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } catch (failure: Exception) {
            requesting = false
            error = failure.message ?: "Не удалось запросить доступ к звуку"
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestProjection() else {
            requesting = false
            error = "Для обработки звука требуется разрешение на запись аудио."
        }
    }
    AudioToggle("Подавлять звук других приложений",
        "Для музыки и видео, которые разрешают обработку звука. Требуется разрешение Android на текущий сеанс. Запись не сохраняется.",
        state.enabled, !requesting) { enabled ->
        error = null
        if (!enabled) controller.stop()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
        else {
            requesting = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    (error ?: state.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
