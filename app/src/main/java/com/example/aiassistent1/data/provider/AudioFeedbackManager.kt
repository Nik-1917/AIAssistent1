package com.example.aiassistent1.data.provider

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AudioFeedbackManager(private val context: Context, private val settingsRepository: SettingsRepository) {
    suspend fun playActivationSound() = withContext(Dispatchers.Main.immediate) {
        val uri = settingsRepository.readAudioPreferences().soundUri
        suspendCancellableCoroutine<Unit> { continuation ->
            val player = MediaPlayer()
            var released = false
            fun finish() {
                if (!released) { released = true; player.release() }
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { finish() } }
            try {
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                if (uri != null) player.setDataSource(context, Uri.parse(uri))
                else context.assets.openFd("sounds/activation.mp3").use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                player.setOnCompletionListener { finish() }
                player.setOnErrorListener { _, _, _ -> finish(); true }
                player.setOnPreparedListener { if (continuation.isActive) it.start() else finish() }
                player.prepareAsync()
            } catch (_: Exception) { finish() }
        }
    }
    suspend fun triggerVibration() {
        if (!settingsRepository.readAudioPreferences().haptics) return
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
