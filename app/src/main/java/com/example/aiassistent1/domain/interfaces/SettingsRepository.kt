package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.GenerationParams
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val selectedModel: StateFlow<String>
    suspend fun setSelectedModel(modelName: String)

    fun getParamsForModel(modelName: String): StateFlow<GenerationParams>
    suspend fun updateParamsForModel(modelName: String, params: GenerationParams)
}
