package com.example.aiassistent1.domain.model

data class AudioPreferences(
    val voiceIdEnabled: Boolean = false,
    val needsEnrollment: Boolean = false,
    val revision: Long = 0,
    val wakeWord: String = "Ассистент",
    val wakeWordEnabled: Boolean = false,
    val autoSummary: Boolean = false,
    val conferenceEffects: Boolean = false,
    val soundUri: String? = null,
    val haptics: Boolean = true,
    val recordingDirectory: String? = null,
    val wakeWordEngine: WakeWordEngine = WakeWordEngine.LEGACY_ASR,
    val policyRevision: Long = 0,
    val allowVoiceInterruption: Boolean = false,
    val messageSentSoundEnabled: Boolean = true,
    val activationTimeoutSoundEnabled: Boolean = true,
) {
    val activationMode: VoiceActivationMode get() = when {
        !wakeWordEnabled -> VoiceActivationMode.DIRECT
        wakeWordEngine == WakeWordEngine.METRIC_KWS -> VoiceActivationMode.PERSONAL_WORD
        else -> VoiceActivationMode.NAME
    }
    /** A saved opt-in applies only to activation modes, never to unrestricted direct input. */
    val voiceInterruptionEnabled: Boolean get() = allowVoiceInterruption && activationMode != VoiceActivationMode.DIRECT
    fun inputPolicy() = VoiceInputPolicy(activationMode, wakeWordEngine, false,
        0, false,
        if (activationMode == VoiceActivationMode.NAME) wakeWord else "", policyRevision, voiceInterruptionEnabled)
}

enum class VoiceActivationMode { DIRECT, NAME, PERSONAL_WORD }

data class VoiceInputPolicy(val activation: VoiceActivationMode, val engine: WakeWordEngine,
    val voiceId: Boolean, val voiceRevision: Long, val enrollment: Boolean, val name: String, val revision: Long,
    val voiceInterruption: Boolean = false)
