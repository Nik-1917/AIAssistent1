package com.example.aiassistent1.domain.model

data class AudioPreferences(
    val voiceIdEnabled: Boolean = false,
    val needsEnrollment: Boolean = false,
    val revision: Long = 0,
    val wakeWord: String = "Ассистент",
    val wakeWordEnabled: Boolean = false,
    val bargeIn: Boolean = true,
    val autoSummary: Boolean = false,
    val conferenceEffects: Boolean = false,
    val soundUri: String? = null,
    val haptics: Boolean = true,
    val recordingDirectory: String? = null,
    val wakeWordEngine: WakeWordEngine = WakeWordEngine.LEGACY_ASR,
    val policyRevision: Long = 0,
) {
    val activationMode: VoiceActivationMode get() = when {
        !wakeWordEnabled -> VoiceActivationMode.DIRECT
        wakeWordEngine == WakeWordEngine.METRIC_KWS -> VoiceActivationMode.PERSONAL_WORD
        else -> VoiceActivationMode.NAME
    }
    fun inputPolicy() = VoiceInputPolicy(activationMode, wakeWordEngine, voiceIdEnabled,
        if (voiceIdEnabled) revision else 0, voiceIdEnabled && needsEnrollment,
        if (activationMode == VoiceActivationMode.NAME) wakeWord else "", policyRevision)
}

enum class VoiceActivationMode { DIRECT, NAME, PERSONAL_WORD }

data class VoiceInputPolicy(val activation: VoiceActivationMode, val engine: WakeWordEngine,
    val voiceId: Boolean, val voiceRevision: Long, val enrollment: Boolean, val name: String, val revision: Long)
