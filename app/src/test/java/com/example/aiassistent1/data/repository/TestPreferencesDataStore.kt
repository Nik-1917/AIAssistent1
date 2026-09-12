package com.example.aiassistent1.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal class TestPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
    private val readGate: CompletableDeferred<Unit>? = null,
) : DataStore<Preferences> {
    private val preferences = MutableStateFlow(initial)

    override val data: Flow<Preferences> = flow {
        readGate?.await()
        emitAll(preferences)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        return transform(preferences.value).also { preferences.value = it }
    }
}
