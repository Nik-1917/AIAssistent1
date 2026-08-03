package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.VoiceModelAssets

interface VoiceModelProvider {
    suspend fun getAssets(): Result<VoiceModelAssets>
}