package com.example.aiassistent1.presentation.viewmodel

data class VoiceDraftState(
    val text: String = "",
    val isVisible: Boolean = false,
    val isRecording: Boolean = false,
)