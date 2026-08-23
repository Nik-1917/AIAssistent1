package com.example.aiassistent1.domain.model

data class VoiceModelAssets(
    val asrEncoder: String,
    val asrDecoder: String,
    val asrJoiner: String,
    val asrTokens: String,
    val ttsModel: String,
    val ttsTokens: String,
    val ttsDataDirectory: String,
    val speechVoice: SpeechVoice,
    val vadModel: String,
)
