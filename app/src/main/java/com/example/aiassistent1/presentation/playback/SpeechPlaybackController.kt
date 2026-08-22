package com.example.aiassistent1.presentation.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.example.aiassistent1.domain.formatter.SpeechTextNormalizer
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SpeechPlaybackState {
    data object Idle : SpeechPlaybackState
    data object Generating : SpeechPlaybackState
    data object Playing : SpeechPlaybackState
    data class Stopped(val reason: SpeechStopReason) : SpeechPlaybackState
    data class Error(val message: String) : SpeechPlaybackState
}

enum class SpeechStopReason(val label: String) {
    User("Остановлено пользователем"),
    NewMessage("Остановлено новым сообщением"),
    Background("Остановлено в фоне"),
    AudioFocus("Остановлено: другое приложение использует звук"),
    NoisyOutput("Остановлено: отключены наушники"),
}

class SpeechPlaybackController(
    context: Context,
    private val speechPlayback: SpeechPlayback,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<SpeechPlaybackState>(SpeechPlaybackState.Idle)
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onAudioFocusChanged)
        .build()
    private val noisyOutputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                stop(SpeechStopReason.NoisyOutput)
            }
        }
    }

    private var playbackJob: Job? = null
    private var sessionId = 0L
    private var isClosed = false

    val state: StateFlow<SpeechPlaybackState> = mutableState.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            appContext,
            noisyOutputReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun speak(text: String) {
        if (text.isBlank() || isClosed) return
        stopActivePlayback()
        if (audioManager.requestAudioFocus(audioFocusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableState.value = SpeechPlaybackState.Error("Не удалось получить доступ к аудиовыводу")
            return
        }

        val activeSessionId = ++sessionId
        mutableState.value = SpeechPlaybackState.Generating
        playbackJob = scope.launch {
            val result = speechPlayback.speak(SpeechTextNormalizer.normalize(text)) {
                if (activeSessionId == sessionId) {
                    mutableState.value = SpeechPlaybackState.Playing
                }
            }
            if (!isActive || activeSessionId != sessionId) return@launch

            result.onSuccess {
                mutableState.value = SpeechPlaybackState.Idle
            }.onFailure { error ->
                if (error !is CancellationException) {
                    mutableState.value = SpeechPlaybackState.Error(
                        error.message ?: "Не удалось воспроизвести голосовой ответ",
                    )
                }
            }
            abandonAudioFocus()
        }
    }

    fun stop(reason: SpeechStopReason) {
        if (mutableState.value is SpeechPlaybackState.Idle || isClosed) return
        ++sessionId
        stopActivePlayback()
        mutableState.value = SpeechPlaybackState.Stopped(reason)
        scope.launch {
            val stoppedSessionId = sessionId
            delay(STOPPED_STATE_DURATION_MILLIS)
            if (stoppedSessionId == sessionId && mutableState.value is SpeechPlaybackState.Stopped) {
                mutableState.value = SpeechPlaybackState.Idle
            }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        ++sessionId
        stopActivePlayback()
        appContext.unregisterReceiver(noisyOutputReceiver)
        speechPlayback.close()
        scope.cancel()
    }

    private fun stopActivePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        speechPlayback.stop()
        abandonAudioFocus()
    }

    private fun onAudioFocusChanged(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> stop(SpeechStopReason.AudioFocus)
        }
    }

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private companion object {
        const val STOPPED_STATE_DURATION_MILLIS = 2_500L
    }
}
