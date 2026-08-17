package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

fun Context.voiceDraftStore(): DataStore<Preferences> = voiceDraftDataStore

private const val VOICE_DRAFT_DATA_STORE_NAME = "voice_draft"
private val Context.voiceDraftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = VOICE_DRAFT_DATA_STORE_NAME,
)

class DataStoreVoiceDraftRepository(
    private val dataStore: DataStore<Preferences>,
) : VoiceDraftRepository {
    override suspend fun loadDraft(): String = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .first()[DRAFT_TEXT].orEmpty()

    override suspend fun saveDraft(text: String) {
        dataStore.edit { preferences ->
            if (text.isBlank()) preferences.remove(DRAFT_TEXT)
            else preferences[DRAFT_TEXT] = text
        }
    }

    override suspend fun clearDraft() {
        dataStore.edit { preferences -> preferences.remove(DRAFT_TEXT) }
    }

    private companion object {
        val DRAFT_TEXT = stringPreferencesKey("text")
    }
}