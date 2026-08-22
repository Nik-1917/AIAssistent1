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
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.SpeechRate
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
    private val smoothResponseEnabledKey = booleanPreferencesKey("smooth_response_enabled")
    private val systemPromptEnabledKey = booleanPreferencesKey("system_prompt_enabled")
    private val dialogueModeEnabledKey = booleanPreferencesKey("dialogue_mode_enabled")
    private val speechRateKey = floatPreferencesKey("speech_rate")
    private val chatScrollAnchorMessageIdKey = stringPreferencesKey("chat_scroll_anchor_message_id")
    private val chatScrollOffsetKey = intPreferencesKey("chat_scroll_offset")
    private val isFirstRunKey = booleanPreferencesKey("is_first_run")
    
    // Кэш для StateFlow параметров, чтобы не пересоздавать их
    private val paramsFlows = mutableMapOf<String, StateFlow<GenerationParams>>()

    override val selectedModel: StateFlow<String> = context.settingsStore.data
        .map { preferences ->
            preferences[selectedModelKey] ?: "ruadapt_qwen2.5_3B_ext_u48_instruct_v4_Q4_K_M.gguf"
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = "ruadapt_qwen2.5_3B_ext_u48_instruct_v4_Q4_K_M.gguf"
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

    override val smoothResponseEnabled: StateFlow<Boolean> = context.settingsStore.data
        .map { preferences -> preferences[smoothResponseEnabledKey] ?: false }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    override val systemPromptEnabled: StateFlow<Boolean> = context.settingsStore.data
        .map { preferences -> preferences[systemPromptEnabledKey] ?: false }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    override val dialogueModeEnabled: StateFlow<Boolean> = context.settingsStore.data
        .map { preferences -> preferences[dialogueModeEnabledKey] ?: false }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    override val speechRate: StateFlow<Float> = context.settingsStore.data
        .map { preferences -> SpeechRate.normalize(preferences[speechRateKey] ?: SpeechRate.DEFAULT) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SpeechRate.DEFAULT,
        )

    override val chatScrollPosition: kotlinx.coroutines.flow.Flow<ChatScrollPosition> = context.settingsStore.data
        .map { preferences ->
            ChatScrollPosition(
                anchorMessageId = preferences[chatScrollAnchorMessageIdKey],
                offset = (preferences[chatScrollOffsetKey] ?: 0).coerceAtLeast(0),
            )
        }

    override val isFirstRun: kotlinx.coroutines.flow.Flow<Boolean> = context.settingsStore.data
        .map { preferences ->
            preferences[isFirstRunKey] ?: true
        }

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
                        contextSize = preferences[intPreferencesKey("${modelName}_contextSize")] ?: 2048,
                        maxTokens = preferences[intPreferencesKey("${modelName}_maxTokens")] ?: 512,
                        temperature = preferences[floatPreferencesKey("${modelName}_temperature")] ?: 0.35f,
                        topP = preferences[floatPreferencesKey("${modelName}_topP")] ?: 0.8f,
                        topK = preferences[intPreferencesKey("${modelName}_topK")] ?: 20,
                        repeatPenalty = preferences[floatPreferencesKey("${modelName}_repeatPenalty")] ?: 1.15f,
                        gpuLayers = preferences[intPreferencesKey("${modelName}_gpuLayers")] ?: 0,
                    )
                }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = GenerationParams(
                        contextSize = 2048,
                        maxTokens = 512,
                        temperature = 0.35f,
                        topP = 0.8f,
                        repeatPenalty = 1.15f
                    )
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

    override suspend fun setSmoothResponseEnabled(enabled: Boolean) {
        context.settingsStore.edit { preferences ->
            preferences[smoothResponseEnabledKey] = enabled
        }
    }

    override suspend fun setSystemPromptEnabled(enabled: Boolean) {
        context.settingsStore.edit { preferences ->
            preferences[systemPromptEnabledKey] = enabled
        }
    }

    override suspend fun setDialogueModeEnabled(enabled: Boolean) {
        context.settingsStore.edit { preferences ->
            preferences[dialogueModeEnabledKey] = enabled
        }
    }

    override suspend fun setSpeechRate(rate: Float) {
        context.settingsStore.edit { preferences ->
            preferences[speechRateKey] = SpeechRate.normalize(rate)
        }
    }

    override suspend fun setChatScrollPosition(position: ChatScrollPosition) {
        context.settingsStore.edit { preferences ->
            position.anchorMessageId?.let { id ->
                preferences[chatScrollAnchorMessageIdKey] = id
            } ?: preferences.remove(chatScrollAnchorMessageIdKey)
            preferences[chatScrollOffsetKey] = position.offset.coerceAtLeast(0)
        }
    }

    override suspend fun setFirstRunCompleted() {
        context.settingsStore.edit { preferences ->
            preferences[isFirstRunKey] = false
        }
    }
}
