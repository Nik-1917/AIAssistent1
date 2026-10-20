package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import com.example.aiassistent1.domain.interfaces.SavedTextDraftRepository
import com.example.aiassistent1.domain.model.SavedTextDraft
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

fun Context.voiceDraftStore(): DataStore<Preferences> = voiceDraftDataStore

private const val VOICE_DRAFT_DATA_STORE_NAME = "voice_draft"
private val Context.voiceDraftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = VOICE_DRAFT_DATA_STORE_NAME,
)

class DataStoreVoiceDraftRepository(
    private val dataStore: DataStore<Preferences>,
) : VoiceDraftRepository, SavedTextDraftRepository {
    override suspend fun loadDraft(): String = dataStore.data.first()[DRAFT_TEXT].orEmpty()

    override suspend fun saveDraft(text: String) {
        dataStore.edit { preferences ->
            if (text.isBlank()) preferences.remove(DRAFT_TEXT)
            else preferences[DRAFT_TEXT] = text
        }
    }

    override suspend fun clearDraft() {
        dataStore.edit { preferences -> preferences.remove(DRAFT_TEXT) }
    }

    override suspend fun loadTextDrafts(): List<SavedTextDraft> =
        decodeDrafts(dataStore.data.first()[SAVED_DRAFTS])

    override suspend fun archiveVoiceDraft(draft: SavedTextDraft): List<SavedTextDraft> {
        require(draft.id.isNotBlank() && draft.text.isNotBlank())
        val updated = dataStore.edit { preferences ->
            val existing = decodeDrafts(preferences[SAVED_DRAFTS])
            val saved = existing.find { it.id == draft.id }
            if (saved != null) {
                // A retry after a lost acknowledgement must not clear a newer working draft.
                check(saved == draft)
            } else {
                check(preferences[DRAFT_TEXT] == draft.text)
                preferences[SAVED_DRAFTS] = encodeDrafts(listOf(draft) + existing)
                preferences.remove(DRAFT_TEXT)
            }
        }
        return decodeDrafts(updated[SAVED_DRAFTS])
    }

    override suspend fun deleteTextDraft(id: String): List<SavedTextDraft> {
        val updated = dataStore.edit { preferences ->
            val remaining = decodeDrafts(preferences[SAVED_DRAFTS]).filterNot { it.id == id }
            if (remaining.isEmpty()) preferences.remove(SAVED_DRAFTS)
            else preferences[SAVED_DRAFTS] = encodeDrafts(remaining)
        }
        return decodeDrafts(updated[SAVED_DRAFTS])
    }

    private fun decodeDrafts(encoded: String?): List<SavedTextDraft> {
        if (encoded == null) return emptyList()
        val root = JSONObject(encoded)
        check(root.getInt("version") == 1)
        val items = root.getJSONArray("items")
        val ids = mutableSetOf<String>()
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            SavedTextDraft(
                id = item.getString("id"),
                text = item.getString("text"),
                createdAtEpochMillis = item.getLong("createdAt"),
            ).also { check(it.id.isNotBlank() && it.text.isNotBlank() && ids.add(it.id)) }
        }.sortedByDescending { it.createdAtEpochMillis }
    }

    private fun encodeDrafts(drafts: List<SavedTextDraft>): String {
        val items = JSONArray()
        drafts.forEach { draft ->
            items.put(JSONObject().apply {
                put("id", draft.id)
                put("text", draft.text)
                put("createdAt", draft.createdAtEpochMillis)
            })
        }
        return JSONObject().put("version", 1).put("items", items).toString()
    }

    private companion object {
        val DRAFT_TEXT = stringPreferencesKey("text")
        val SAVED_DRAFTS = stringPreferencesKey("saved_text_drafts")
    }
}
