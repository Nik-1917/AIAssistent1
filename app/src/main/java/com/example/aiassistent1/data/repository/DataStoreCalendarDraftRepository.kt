package com.example.aiassistent1.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aiassistent1.domain.interfaces.CalendarDraftRepository
import com.example.aiassistent1.domain.model.CalendarDraftRecord
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.calendarDraftDataStore: DataStore<Preferences> by preferencesDataStore("calendar_drafts")
fun Context.calendarDraftStore(): DataStore<Preferences> = calendarDraftDataStore

class DataStoreCalendarDraftRepository(private val dataStore: DataStore<Preferences>) : CalendarDraftRepository {
    override suspend fun load(): List<CalendarDraftRecord> {
        val encoded = dataStore.data.first()[DRAFTS] ?: return emptyList()
        val root = JSONObject(encoded)
        require(root.getInt("version") == 1) { "Неподдерживаемая версия черновиков событий" }
        val items = root.getJSONArray("items")
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val inputs = item.getJSONObject("fieldInputs")
            CalendarDraftRecord(
                requestId = item.getString("requestId"),
                chatId = item.getString("chatId"),
                createdAtEpochMillis = item.getLong("createdAt"),
                title = item.nullableString("title"), date = item.nullableString("date"),
                time = item.nullableString("time"), notes = item.nullableString("notes"),
                endsAt = item.nullableString("endsAt"),
                durationMinutes = if (item.isNull("durationMinutes")) null else item.getInt("durationMinutes"),
                value = if (item.isNull("value")) null else item.getLong("value"),
                fieldInputs = inputs.keys().asSequence().associateWith { inputs.getString(it) },
                savedEventId = item.nullableString("savedEventId"),
                saveRequested = item.getBoolean("saveRequested"),
            ).also { require(it.requestId.isNotBlank()) }
        }.also { drafts -> require(drafts.map { it.requestId }.distinct().size == drafts.size) }
    }

    override suspend fun save(drafts: List<CalendarDraftRecord>) {
        val items = JSONArray()
        drafts.forEach { draft ->
            items.put(JSONObject().apply {
                put("requestId", draft.requestId); put("chatId", draft.chatId)
                put("createdAt", draft.createdAtEpochMillis)
                put("title", draft.title); put("date", draft.date); put("time", draft.time)
                put("durationMinutes", draft.durationMinutes); put("value", draft.value)
                put("notes", draft.notes); put("endsAt", draft.endsAt)
                put("fieldInputs", JSONObject(draft.fieldInputs))
                put("savedEventId", draft.savedEventId); put("saveRequested", draft.saveRequested)
            })
        }
        val encoded = JSONObject().put("version", 1).put("items", items).toString()
        dataStore.edit { it[DRAFTS] = encoded }
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    private companion object {
        val DRAFTS = stringPreferencesKey("drafts")
    }
}
