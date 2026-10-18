package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.VoiceActivationMode

/** Playback must never turn name/personal-word activation into unrestricted input. */
object VoicePlaybackInputPolicy {
    enum class Capture { STOP, DIRECT, ACTIVATION }

    fun capture(preferences: AudioPreferences, playbackActive: Boolean, manualInput: Boolean = false): Capture = when {
        manualInput -> Capture.DIRECT
        playbackActive && !preferences.voiceInterruptionEnabled -> Capture.STOP
        preferences.activationMode == VoiceActivationMode.DIRECT -> Capture.DIRECT
        else -> Capture.ACTIVATION
    }

    fun acceptsInterruption(preferences: AudioPreferences,
        capturePolicy: com.example.aiassistent1.domain.model.VoiceInputPolicy?): Boolean =
        preferences.voiceInterruptionEnabled && preferences.inputPolicy() == capturePolicy
}
