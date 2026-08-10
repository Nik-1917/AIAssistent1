package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) : SettingsRepository {

    private val selectedModelKey = stringPreferencesKey("selected_model")

    override val selectedModel: StateFlow<String> = context.settingsStore.data
        .map { preferences ->
            preferences[selectedModelKey] ?: "qwen2.5-3b-instruct-q4_k_m.gguf"
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = "qwen2.5-3b-instruct-q4_k_m.gguf"
        )

    override suspend fun setSelectedModel(modelName: String) {
        context.settingsStore.edit { preferences ->
            preferences[selectedModelKey] = modelName
        }
    }
}
