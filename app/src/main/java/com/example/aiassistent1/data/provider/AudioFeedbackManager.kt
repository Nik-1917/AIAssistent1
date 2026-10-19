package com.example.aiassistent1.data.provider

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AudioFeedbackManager(private val context: Context, private val settingsRepository: SettingsRepository) {
    suspend fun playActivationSound() = playMessageSentSound()

    suspend fun playMessageSentSound() = playSound("sounds/message_sent.wav")
    suspend fun playActivationTimeoutSound() = playSound("sounds/activation_timeout.wav")

    private suspend fun playSound(assetPath: String) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val player = MediaPlayer()
            var released = false
            fun finish() {
                if (!released) { released = true; player.release() }
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) finish()
                else android.os.Handler(android.os.Looper.getMainLooper()).post { finish() }
            }
            try {
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                context.assets.openFd(assetPath).use {
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
