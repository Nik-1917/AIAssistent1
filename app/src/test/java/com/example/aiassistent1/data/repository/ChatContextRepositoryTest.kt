package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.aiassistent1.domain.model.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatContextRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `defaults ask and updates preserve other fields and isolate chats`() = runTest {
        val repository = DataStoreChatContextRepository(TestPreferencesDataStore())
        assertEquals(ChatContextSettings(), repository.observe("general").first())
        repository.update("general", excludedTurnIds = setOf("u1", "u2"))
        repository.update("general", policy = ChatHistoryPolicy.AUTOMATIC)
        assertEquals(ChatContextSettings(setOf("u1", "u2"), ChatHistoryPolicy.AUTOMATIC), repository.observe("general").first())
        assertEquals(ChatContextSettings(), repository.observe("another-chat").first())
        assertTrue(runCatching { repository.update("calendar", policy = ChatHistoryPolicy.AUTOMATIC) }.isFailure)
        assertEquals(ChatContextSettings(), repository.observe("calendar").first())
        repository.update("general", excludedTurnIds = emptySet())
        assertEquals(ChatHistoryPolicy.AUTOMATIC, repository.observe("general").first().policy)
    }

    @Test fun `exclusions and user preference survive closing and reopening storage`() = runTest {
        val file = File(folder.root, "chat-context.preferences_pb")
        val writerJob = Job(backgroundScope.coroutineContext[Job])
        val writer = DataStoreChatContextRepository(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + writerJob), produceFile = { file }))
        writer.update("general", setOf("turn-one", "turn-two"), ChatHistoryPolicy.AUTOMATIC)
        writerJob.cancelAndJoin()
        val reader = DataStoreChatContextRepository(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        assertEquals(ChatContextSettings(setOf("turn-one", "turn-two"), ChatHistoryPolicy.AUTOMATIC),
            reader.observe("general").first())
    }
}
