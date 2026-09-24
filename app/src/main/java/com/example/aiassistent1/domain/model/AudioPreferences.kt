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
)
