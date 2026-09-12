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
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.AppNavigationState
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.FloatingControlPositions
import com.example.aiassistent1.domain.model.SpeechRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : SettingsRepository {
    constructor(context: Context, scope: CoroutineScope) : this(context.settingsStore, scope)

    private val appDestinationKey = stringPreferencesKey("last_app_destination")
    private val selectedModelKey = stringPreferencesKey("selected_model")
    private val showDeleteMessageConfirmationKey = booleanPreferencesKey("show_delete_message_confirmation")
    private val showClearChatConfirmationKey = booleanPreferencesKey("show_clear_chat_confirmation")
    private val smoothResponseEnabledKey = booleanPreferencesKey("smooth_response_enabled")
    private val systemPromptEnabledKey = booleanPreferencesKey("system_prompt_enabled")
    private val dialogueModeEnabledKey = booleanPreferencesKey("dialogue_mode_enabled")
    private val autoPlaybackEnabledKey = booleanPreferencesKey("auto_playback_enabled")
    private val speechRateKey = floatPreferencesKey("speech_rate")
    private val chatScrollAnchorMessageIdKey = stringPreferencesKey("chat_scroll_anchor_message_id")
    private val chatScrollOffsetKey = intPreferencesKey("chat_scroll_offset")
    private val speechCardXdpKey = floatPreferencesKey("speech_card_x_dp")
    private val speechCardYdpKey = floatPreferencesKey("speech_card_y_dp")
    private val calendarButtonXdpKey = floatPreferencesKey("calendar_button_x_dp")
    private val calendarButtonYdpKey = floatPreferencesKey("calendar_button_y_dp")
    private val isFirstRunKey = booleanPreferencesKey("is_first_run")
    
    // Кэш для StateFlow параметров, чтобы не пересоздавать их
    private val paramsFlows = mutableMapOf<String, StateFlow<GenerationParams>>()

    // No placeholder emission: navigation becomes available only after reading storage.
    override val navigationState = dataStore.data.map { preferences ->
        AppNavigationState(
            destination = AppDestination.entries.firstOrNull {
                it.name == preferences[appDestinationKey]
            } ?: AppDestination.CHAT,
            isCalendarMode = preferences[systemPromptEnabledKey] ?: true,
        )
    }.distinctUntilChanged()

    override suspend fun setAppDestination(destination: AppDestination) {
        dataStore.edit { preferences ->
            preferences[appDestinationKey] = destination.name
        }
    }

    override val selectedModel: StateFlow<String?> = dataStore.data
        .map { preferences ->
            val selectedModel: String? = preferences[selectedModelKey] ?: ""
            selectedModel
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    override val showDeleteMessageConfirmation: StateFlow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[showDeleteMessageConfirmationKey] ?: true
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    override val showClearChatConfirmation: StateFlow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[showClearChatConfirmationKey] ?: true
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    override val smoothResponseEnabled: StateFlow<Boolean> = dataStore.data
        .map { preferences -> preferences[smoothResponseEnabledKey] ?: false }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    override val systemPromptEnabled: StateFlow<Boolean> = dataStore.data
        .map { preferences -> preferences[systemPromptEnabledKey] ?: true }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    override val dialogueModeEnabled: StateFlow<Boolean> = dataStore.data
        .map { preferences -> preferences[dialogueModeEnabledKey] ?: false }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    override val autoPlaybackEnabled: StateFlow<Boolean> = dataStore.data
        .map { preferences -> preferences[autoPlaybackEnabledKey] ?: true }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    override val speechRate: StateFlow<Float> = dataStore.data
        .map { preferences -> SpeechRate.normalize(preferences[speechRateKey] ?: SpeechRate.DEFAULT) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SpeechRate.DEFAULT,
        )

    override val chatScrollPosition: kotlinx.coroutines.flow.Flow<ChatScrollPosition> = dataStore.data
        .map { preferences ->
            ChatScrollPosition(
                anchorMessageId = preferences[chatScrollAnchorMessageIdKey],
                offset = (preferences[chatScrollOffsetKey] ?: 0).coerceAtLeast(0),
            )
        }

    override val floatingControlPositions: kotlinx.coroutines.flow.Flow<FloatingControlPositions> = dataStore.data
        .map { preferences ->
            FloatingControlPositions(
                speechCardXdp = preferences[speechCardXdpKey] ?: 0f,
                speechCardYdp = preferences[speechCardYdpKey] ?: 0f,
                calendarButtonXdp = preferences[calendarButtonXdpKey] ?: 0f,
                calendarButtonYdp = preferences[calendarButtonYdpKey] ?: 0f,
            )
        }

    override val isFirstRun: kotlinx.coroutines.flow.Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[isFirstRunKey] ?: true
        }

    override suspend fun setSelectedModel(modelName: String) {
        dataStore.edit { preferences ->
            preferences[selectedModelKey] = modelName
        }
    }

    override fun getParamsForModel(modelName: String): StateFlow<GenerationParams> {
        return paramsFlows.getOrPut(modelName) {
            dataStore.data
                .map { preferences ->
                    GenerationParams(
                        contextSize = preferences[intPreferencesKey("${modelName}_contextSize")] ?: 512,
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
                        contextSize = 512,
                        maxTokens = 512,
                        temperature = 0.35f,
                        topP = 0.8f,
                        repeatPenalty = 1.15f
                    )
                )
        }
    }

    override suspend fun updateParamsForModel(modelName: String, params: GenerationParams) {
        dataStore.edit { preferences ->
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
        dataStore.edit { preferences ->
            preferences[showDeleteMessageConfirmationKey] = show
        }
    }

    override suspend fun setShowClearChatConfirmation(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[showClearChatConfirmationKey] = show
        }
    }

    override suspend fun setSmoothResponseEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[smoothResponseEnabledKey] = enabled
        }
    }

    override suspend fun setSystemPromptEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[systemPromptEnabledKey] = enabled
            preferences[appDestinationKey] = AppDestination.CHAT.name
        }
    }

    override suspend fun setDialogueModeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[dialogueModeEnabledKey] = enabled
        }
    }

    override suspend fun setAutoPlaybackEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[autoPlaybackEnabledKey] = enabled
        }
    }

    override suspend fun setSpeechRate(rate: Float) {
        dataStore.edit { preferences ->
            preferences[speechRateKey] = SpeechRate.normalize(rate)
        }
    }

    override suspend fun setChatScrollPosition(position: ChatScrollPosition) {
        dataStore.edit { preferences ->
            position.anchorMessageId?.let { id ->
                preferences[chatScrollAnchorMessageIdKey] = id
            } ?: preferences.remove(chatScrollAnchorMessageIdKey)
            preferences[chatScrollOffsetKey] = position.offset.coerceAtLeast(0)
        }
    }

    override suspend fun setFloatingControlPositions(positions: FloatingControlPositions) {
        dataStore.edit { preferences ->
            preferences[speechCardXdpKey] = positions.speechCardXdp
            preferences[speechCardYdpKey] = positions.speechCardYdp
            preferences[calendarButtonXdpKey] = positions.calendarButtonXdp
            preferences[calendarButtonYdpKey] = positions.calendarButtonYdp
        }
    }

    override suspend fun setFirstRunCompleted() {
        dataStore.edit { preferences ->
            preferences[isFirstRunKey] = false
        }
    }
}
