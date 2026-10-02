package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.example.aiassistent1.data.model.GgufMetadataReader
import com.example.aiassistent1.data.model.GgufTestFile
import com.example.aiassistent1.domain.model.ModelProfile
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

class ModelContextSettingsRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val readContext: suspend (String) -> Int? = {
        GgufMetadataReader.readInDirectory(temporaryFolder.root, it)
    }

    @Test fun `reads each model ceiling before normalizing both stored profiles`() = runTest {
        GgufTestFile().architecture().context(1024).write(File(temporaryFolder.root, "small.gguf"))
        GgufTestFile().architecture().context(32768).write(File(temporaryFolder.root, "large.gguf"))
        val store = TestPreferencesDataStore(preferencesOf(
            intPreferencesKey("model_params/calendar/small.gguf/contextSize") to 8192,
            intPreferencesKey("model_params/chat/small.gguf/contextSize") to 4096,
            intPreferencesKey("model_params/chat/large.gguf/contextSize") to 16384,
        ))
        val repository = DataStoreSettingsRepository(store, backgroundScope, readModelContextLength = readContext)
        for (profile in ModelProfile.entries) {
            val small = repository.getParamsForModel("small.gguf", profile).first()
            assertEquals(1024, small.contextSize)
            assertEquals(512, small.maxTokens)
            assertEquals(1024, small.trainedContextLength)
        }
        val large = repository.getParamsForModel("large.gguf", ModelProfile.CHAT).first()
        assertEquals(16384, large.contextSize)
        assertEquals(8192, large.maxTokens)
        assertEquals(32768, large.trainedContextLength)
        assertEquals(512, repository.getParamsForModel("large.gguf", ModelProfile.CALENDAR).first().contextSize)
    }

    @Test fun `writes use target file metadata instead of stale or forged UI limits`() = runTest {
        val file = File(temporaryFolder.root, "model.gguf")
        GgufTestFile().architecture().context(1024).write(file)
        val store = TestPreferencesDataStore()
        val repository = DataStoreSettingsRepository(store, backgroundScope, readModelContextLength = readContext)
        val stale = ModelProfile.CHAT.defaults.copy(contextSize = 32768, maxTokens = 16384, trainedContextLength = 131072)
        repository.updateParamsForModel(file.name, ModelProfile.CHAT, stale)
        val saved = store.data.first()
        assertEquals(1024, saved[intPreferencesKey("model_params/chat/model.gguf/contextSize")])
        assertEquals(512, saved[intPreferencesKey("model_params/chat/model.gguf/maxTokens")])
        assertFalse(saved.asMap().keys.any { it.name.contains("trainedContextLength") })
        val calendar = repository.getParamsForModel(file.name, ModelProfile.CALENDAR).first()
        assertEquals(512, calendar.contextSize)
        assertEquals(1024, calendar.trainedContextLength)
        GgufTestFile().architecture().context(32768, wide = true).write(file)
        repository.updateParamsForModel(file.name, ModelProfile.CHAT, stale.copy(trainedContextLength = 1024))
        assertEquals(32768, repository.getParamsForModel(file.name, ModelProfile.CHAT).first().contextSize)
    }

    @Test fun `late imported model replaces unknown limit without increasing current context`() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope, readModelContextLength = readContext)
        val before = repository.getParamsForModel("late.gguf", ModelProfile.CHAT).first()
        assertNull(before.trainedContextLength)
        assertEquals(8192, before.contextLimits.maximum)
        GgufTestFile().architecture().context(32768).write(File(temporaryFolder.root, "late.gguf"))
        val after = repository.getParamsForModel("late.gguf", ModelProfile.CHAT).first()
        assertEquals(32768, after.contextLimits.maximum)
        assertEquals(before.contextSize, after.contextSize)
    }

    @Test fun `large context survives preferences reopen with its model metadata`() = runTest {
        GgufTestFile().architecture().context(32768).write(File(temporaryFolder.root, "large.gguf"))
        val file = File(temporaryFolder.root, "model-context.preferences_pb")
        val writerJob = Job(backgroundScope.coroutineContext[Job])
        val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
        val writerStore = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
        val snapshot = TestPreferencesDataStore()
        val writer = DataStoreSettingsRepository(snapshot, writerScope, readModelContextLength = readContext)
        val requested = ModelProfile.CHAT.defaults.copy(contextSize = 16384, maxTokens = 8192)
        writer.updateParamsForModel("large.gguf", ModelProfile.CHAT, requested)
        writerStore.updateData { snapshot.data.first() }
        writerJob.cancelAndJoin()
        val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val reader = DataStoreSettingsRepository(readerStore, backgroundScope, readModelContextLength = readContext)
        assertEquals(requested.copy(trainedContextLength = 32768),
            reader.getParamsForModel("large.gguf", ModelProfile.CHAT).first())
    }
}
