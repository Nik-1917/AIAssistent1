package com.example.aiassistent1.presentation.viewmodel

data class VoiceDraftState(
    val text: String = "",
    val isVisible: Boolean = false,
    val isRecording: Boolean = false,
    val noiseSuppressionEnabled: Boolean = true,
) {
    val characterCount: Int get() = text.length
    val isLimitReached: Boolean get() = characterCount >= MAX_CHARACTERS

    fun opened(): VoiceDraftState = copy(isVisible = true, isRecording = !isLimitReached)

    fun restored(text: String): VoiceDraftState = copy(
        text = text, isVisible = text.isNotBlank(), isRecording = false,
    )

    fun appendTranscript(transcript: String): VoiceDraftState {
        if (!isRecording) return this
        val recognized = transcript.trim()
        if (recognized.isEmpty()) return this
        val current = text.trim()
        val separator = if (current.isEmpty()) "" else " "
        val updated = (current + separator + recognized).take(MAX_CHARACTERS)
        val full = updated.length >= MAX_CHARACTERS
        return copy(text = updated, isVisible = isVisible && !full, isRecording = !full)
    }

    companion object {
        const val MAX_CHARACTERS = 3000
    }
}
