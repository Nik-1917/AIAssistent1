package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.model.GenerationParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) : SettingsRepository {

    private val selectedModelKey = stringPreferencesKey("selected_model")
    private val showDeleteMessageConfirmationKey = booleanPreferencesKey("show_delete_message_confirmation")
    private val showClearChatConfirmationKey = booleanPreferencesKey("show_clear_chat_confirmation")
    private val isFirstRunKey = booleanPreferencesKey("is_first_run")
    
    // Кэш для StateFlow параметров, чтобы не пересоздавать их
    private val paramsFlows = mutableMapOf<String, StateFlow<GenerationParams>>()

    override val selectedModel: StateFlow<String> = context.settingsStore.data
        .map { preferences ->
            preferences[selectedModelKey] ?: "qwen2.5-3b-instruct-q4_k_m.gguf"
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = "qwen2.5-3b-instruct-q4_k_m.gguf"
        )

    override val showDeleteMessageConfirmation: StateFlow<Boolean> = context.settingsStore.data
        .map { preferences ->
            preferences[showDeleteMessageConfirmationKey] ?: true
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    override val showClearChatConfirmation: StateFlow<Boolean> = context.settingsStore.data
        .map { preferences ->
            preferences[showClearChatConfirmationKey] ?: true
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    override val isFirstRun: StateFlow<Boolean> = context.settingsStore.data
        .map { preferences ->
            preferences[isFirstRunKey] ?: true
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    override suspend fun setSelectedModel(modelName: String) {
        context.settingsStore.edit { preferences ->
            preferences[selectedModelKey] = modelName
        }
    }

    override fun getParamsForModel(modelName: String): StateFlow<GenerationParams> {
        return paramsFlows.getOrPut(modelName) {
            context.settingsStore.data
                .map { preferences ->
                    GenerationParams(
                        contextSize = preferences[intPreferencesKey("${modelName}_contextSize")] ?: 4048,
                        maxTokens = preferences[intPreferencesKey("${modelName}_maxTokens")] ?: 512,
                        temperature = preferences[floatPreferencesKey("${modelName}_temperature")] ?: 0.7f,
                        topP = preferences[floatPreferencesKey("${modelName}_topP")] ?: 0.8f,
                        topK = preferences[intPreferencesKey("${modelName}_topK")] ?: 20,
                        repeatPenalty = preferences[floatPreferencesKey("${modelName}_repeatPenalty")] ?: 1.15f,
                        gpuLayers = preferences[intPreferencesKey("${modelName}_gpuLayers")] ?: 0,
                    )
                }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = GenerationParams(temperature = 0.7f) // Default for new models
                )
        }
    }

    override suspend fun updateParamsForModel(modelName: String, params: GenerationParams) {
        context.settingsStore.edit { preferences ->
            preferences[intPreferencesKey("${modelName}_contextSize")] = params.contextSize
            preferences[intPreferencesKey("${modelName}_maxTokens")] = params.maxTokens
            preferences[floatPreferencesKey("${modelName}_temperature")] = params.temperature
            preferences[floatPreferencesKey("${modelName}_topP")] = params.topP
            preferences[intPreferencesKey("${modelName}_topK")] = params.topK
            preferences[floatPreferencesKey("${modelName}_repeatPenalty")] = params.repeatPenalty
            preferences[intPreferencesKey("${modelName}_gpuLayers")] = params.gpuLayers
        }
    }

    override suspend fun setShowDeleteMessageConfirmation(show: Boolean) {
        context.settingsStore.edit { preferences ->
            preferences[showDeleteMessageConfirmationKey] = show
        }
    }

    override suspend fun setShowClearChatConfirmation(show: Boolean) {
        context.settingsStore.edit { preferences ->
            preferences[showClearChatConfirmationKey] = show
        }
    }

    override suspend fun setFirstRunCompleted() {
        context.settingsStore.edit { preferences ->
            preferences[isFirstRunKey] = false
        }
    }
}
