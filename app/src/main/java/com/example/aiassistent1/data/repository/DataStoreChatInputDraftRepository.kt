package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.ChatInputDraftRepository
import kotlinx.coroutines.flow.first

private val Context.chatInputDraftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat_input_draft",
)

fun Context.chatInputDraftStore(): DataStore<Preferences> = chatInputDraftDataStore

class DataStoreChatInputDraftRepository(
    private val dataStore: DataStore<Preferences>,
) : ChatInputDraftRepository {
    // Propagate read failures: an unread draft must not be replaced with an empty one.
    override suspend fun load(): String = dataStore.data.first()[TEXT].orEmpty()

    override suspend fun save(text: String) {
        dataStore.edit { preferences ->
            if (text.isEmpty()) preferences.remove(TEXT)
            else preferences[TEXT] = text
        }
    }

    private companion object {
        val TEXT = stringPreferencesKey("text")
    }
}
