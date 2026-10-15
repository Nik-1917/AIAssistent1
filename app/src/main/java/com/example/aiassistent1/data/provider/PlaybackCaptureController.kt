package com.example.aiassistent1.data.provider

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.aiassistent1.service.PlaybackReferenceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackCaptureState(val enabled: Boolean = false, val message: String? = null)

/** Consent is session-only. Projection tokens are never written to preferences. */
class PlaybackCaptureController(context: Context) {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(PlaybackCaptureState())
    val state = mutableState.asStateFlow()
    private var nextId = 0L
    @Volatile private var requestedId: Long? = null

    @Synchronized fun start(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            mutableState.value = PlaybackCaptureState(message = "Разрешение на обработку звука других приложений не выдано.")
            return
        }
        val id = ++nextId
        requestedId = id
        mutableState.value = PlaybackCaptureState(enabled = true, message = "Подключение…")
        try {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackReferenceService::class.java)
                .putExtra(PlaybackReferenceService.SESSION, id)
                .putExtra(PlaybackReferenceService.RESULT_CODE, resultCode)
                .putExtra(PlaybackReferenceService.RESULT_DATA, data))
        } catch (error: Exception) {
            finish(id, "Не удалось подключить обработку звука: ${error.message}")
        }
    }

    @Synchronized fun stop() {
        requestedId = null
        mutableState.value = PlaybackCaptureState()
        context.stopService(Intent(context, PlaybackReferenceService::class.java))
    }

    fun isRequested(id: Long) = requestedId == id
    @Synchronized fun connected(id: Long) {
        if (isRequested(id)) mutableState.value = PlaybackCaptureState(enabled = true,
            message = "Включено на текущий сеанс. Обрабатывается только звук, доступный Android.")
    }
    @Synchronized fun finish(id: Long, message: String? = null) {
        if (isRequested(id)) {
            requestedId = null
            mutableState.value = PlaybackCaptureState(message = message)
        }
    }
}
