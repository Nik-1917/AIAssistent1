package com.example.aiassistent1.presentation.ui

import android.content.Context
import android.media.*
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

internal data class ConferencePlaybackState(val playing: Boolean = false, val positionMs: Long = 0, val error: String? = null)

internal class ConferencePlayer(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ConferencePlaybackState())
    val state = mutableState.asStateFlow()
    private var player: MediaPlayer? = null
    private var source: String? = null
    private var ready = false
    private var pendingPosition = 0L
    private var pendingPlay = false
    private var ticker: Job? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) pause() }.build()

    fun toggle(path: String) {
        if (source == path && ready && mutableState.value.playing) pause()
        else seek(path, if (source == path) mutableState.value.positionMs else 0, true)
    }
    fun seek(path: String, position: Long, play: Boolean) {
        pendingPosition = position.coerceAtLeast(0)
        pendingPlay = play || mutableState.value.playing
        if (source == path && player != null) {
            if (ready) applyRequest()
            return
        }
        stop()
        pendingPosition = position.coerceAtLeast(0); pendingPlay = play
        source = path
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            if (path.startsWith("content:")) next.setDataSource(context, Uri.parse(path))
            else next.setDataSource(File(path).absolutePath)
            next.setOnPreparedListener { if (player === it) { ready = true; applyRequest() } }
            next.setOnCompletionListener { mutableState.update { state -> state.copy(playing = false, positionMs = it.duration.toLong()) }; abandonFocus() }
            next.setOnErrorListener { _, _, _ -> stop(); mutableState.value = ConferencePlaybackState(error = "Не удалось воспроизвести MP3"); true }
            next.prepareAsync()
        } catch (e: Exception) { stop(); mutableState.value = ConferencePlaybackState(error = e.message ?: "Ошибка аудиофайла") }
    }
    private fun applyRequest() {
        val active = player ?: return
        try {
            active.seekTo(pendingPosition.coerceAtMost(active.duration.toLong()), MediaPlayer.SEEK_CLOSEST)
            mutableState.update { it.copy(positionMs = pendingPosition, error = null) }
            if (pendingPlay) {
                check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Аудиовыход занят" }
                active.start()
                mutableState.update { it.copy(playing = true) }
                ticker?.cancel()
                ticker = scope.launch {
                    while (isActive && player === active && mutableState.value.playing) {
                        mutableState.update { it.copy(positionMs = active.currentPosition.toLong()) }
                        delay(250)
                    }
                }
            }
        } catch (e: Exception) { stop(); mutableState.value = ConferencePlaybackState(error = e.message) }
    }
    fun pause() {
        if (ready) runCatching { player?.pause() }
        pendingPlay = false; ticker?.cancel()
        mutableState.update { it.copy(playing = false) }
        abandonFocus()
    }
    private fun abandonFocus() { audio.abandonAudioFocusRequest(focus) }
    fun stop() {
        ticker?.cancel(); ready = false
        player?.release(); player = null; source = null
        mutableState.value = ConferencePlaybackState()
        abandonFocus()
    }
    override fun close() { stop(); scope.cancel() }
}
