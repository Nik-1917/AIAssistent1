package com.example.aiassistent1.domain.usecase

/** Endpoint ASR must confirm actual text before it can interrupt TTS. This is not an intent classifier. */
object VoiceCommandAcceptance {
    fun hasSpeechText(text: String): Boolean = text.any { it.isLetterOrDigit() }
}
