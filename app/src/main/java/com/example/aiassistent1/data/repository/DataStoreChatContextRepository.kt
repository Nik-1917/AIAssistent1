package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.ChatContextRepository
import com.example.aiassistent1.domain.model.ChatContextSettings
import com.example.aiassistent1.domain.model.ChatHistoryPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.chatContextDataStore by preferencesDataStore("chat_context")
fun Context.chatContextStore(): DataStore<Preferences> = chatContextDataStore

class DataStoreChatContextRepository(private val store: DataStore<Preferences>) : ChatContextRepository {
    override fun observe(chatId: String) = store.data.map { preferences ->
        ChatContextSettings(
            preferences[excludedKey(chatId)]?.toSet().orEmpty(),
            ChatHistoryPolicy.entries.firstOrNull { it.name == preferences[policyKey(chatId)] }
                ?: ChatHistoryPolicy.ASK,
        )
    }.distinctUntilChanged()

    override suspend fun update(chatId: String, excludedTurnIds: Set<String>?, policy: ChatHistoryPolicy?) {
        require(chatId != "calendar") { "Calendar context is managed independently" }
        store.edit { preferences ->
            excludedTurnIds?.let { preferences[excludedKey(chatId)] = it.toSet() }
            policy?.let { preferences[policyKey(chatId)] = it.name }
        }
    }

    private fun excludedKey(chatId: String) = stringSetPreferencesKey("$chatId/excluded_turns")
    private fun policyKey(chatId: String) = stringPreferencesKey("$chatId/overflow_policy")
}
