package com.example.aiassistent1.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreVoiceDraftRepositoryTest {
    @Test
    fun `saves restores and clears voice draft`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val repository = DataStoreVoiceDraftRepository(dataStore)

        repository.saveDraft("Первая сохраненная фраза")

        assertEquals(
            "Первая сохраненная фраза",
            DataStoreVoiceDraftRepository(dataStore).loadDraft(),
        )

        repository.clearDraft()

        assertEquals("", repository.loadDraft())
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val preferences = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = preferences

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updatedPreferences = transform(preferences.value)
            preferences.value = updatedPreferences
            return updatedPreferences
        }
    }
}