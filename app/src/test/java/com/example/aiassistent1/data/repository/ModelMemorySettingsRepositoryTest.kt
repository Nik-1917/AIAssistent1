package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.example.aiassistent1.domain.model.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelMemorySettingsRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val prefix = "model_params/chat/model.gguf/"
    private fun key(name: String) = intPreferencesKey(prefix + name)

    @Test fun `memory caps legacy settings and writes ignore forged device limits`() = runTest {
        var device = DeviceContextLimit(4096, MemoryLimitStatus.ESTIMATED)
        val store = TestPreferencesDataStore(preferencesOf(key("contextSize") to 16384))
        val repository = DataStoreSettingsRepository(store, backgroundScope,
            readModelContextLength = { 32768 }, readModelMemoryLimit = { _, _ -> device })
        val read = repository.getParamsForModel("model.gguf", ModelProfile.CHAT).first()
        assertEquals(4096, read.contextSize)
        assertEquals(ContextResponseRatio.TWO_TO_ONE, read.contextResponseRatio)
        device = device.copy(maximumContext = 1536)
        repository.updateParamsForModel("model.gguf", ModelProfile.CHAT,
            read.copy(contextSize = 16384, deviceContextLimit = device.copy(maximumContext = 131072),
                contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE))
        val saved = store.data.first()
        assertEquals(1536, saved[key("contextSize")])
        assertEquals(384, saved[key("maxTokens")])
        assertEquals(4, saved[key("contextResponseRatio")])
        assertFalse(saved.asMap().keys.any { it.name.contains("deviceContextLimit") || it.name.contains("availableBytes") })
    }

    @Test fun `temporary memory block retains usable context and ratio updates remain atomic`() = runTest {
        var device = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)
        val store = TestPreferencesDataStore(preferencesOf(key("contextSize") to 4096, key("maxTokens") to 2048,
            key("batchSize") to 1024))
        val repository = DataStoreSettingsRepository(store, backgroundScope,
            readModelContextLength = { 32768 }, readModelMemoryLimit = { _, _ -> device })
        val blocked = repository.getParamsForModel("model.gguf", ModelProfile.CHAT).first()
        assertEquals(0, blocked.contextSize)
        assertFalse(blocked.contextLimits.adjustable)
        assertEquals(1024, blocked.batchSize)
        repository.updateParamsForModel("model.gguf", ModelProfile.CHAT,
            blocked.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE).copy(temperature = 0.8f))
        assertEquals(4096, store.data.first()[key("contextSize")])
        assertEquals(1024, store.data.first()[key("maxTokens")])
        assertEquals(4, store.data.first()[key("contextResponseRatio")])
        assertEquals(1024, store.data.first()[key("batchSize")])
        device = DeviceContextLimit(8192, MemoryLimitStatus.ESTIMATED)
        val restored = repository.getParamsForModel("model.gguf", ModelProfile.CHAT).first()
        assertEquals(4096, restored.contextSize)
        assertEquals(1024, restored.maxTokens)
        assertEquals(0.8f, restored.temperature)
        assertEquals(1024, restored.batchSize)
    }

    @Test fun `ratio is scoped to model and profile and survives a real preferences reopen`() = runTest {
        val snapshot = TestPreferencesDataStore()
        val repository = DataStoreSettingsRepository(snapshot, backgroundScope)
        val quarter = ModelProfile.CHAT.defaults.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE)
        repository.updateParamsForModel("model.gguf", ModelProfile.CHAT, quarter)
        assertEquals(ContextResponseRatio.TWO_TO_ONE,
            repository.getParamsForModel("model.gguf", ModelProfile.CALENDAR).first().contextResponseRatio)
        assertEquals(ContextResponseRatio.TWO_TO_ONE,
            repository.getParamsForModel("other.gguf", ModelProfile.CHAT).first().contextResponseRatio)
        val file = File(directory.root, "ratio.preferences_pb")
        val job = Job(backgroundScope.coroutineContext[Job])
        val writer = PreferenceDataStoreFactory.create(scope = CoroutineScope(backgroundScope.coroutineContext + job), produceFile = { file })
        writer.updateData { snapshot.data.first() }
        job.cancelAndJoin()
        val reader = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }), backgroundScope)
        assertEquals(quarter, reader.getParamsForModel("model.gguf", ModelProfile.CHAT).first())
    }

    @Test fun `editing ratio during a memory block retains legacy calendar context`() = runTest {
        var device = DeviceContextLimit(0, MemoryLimitStatus.LOW_MEMORY)
        val store = TestPreferencesDataStore(preferencesOf(intPreferencesKey("model.gguf_contextSize") to 4096))
        val repository = DataStoreSettingsRepository(store, backgroundScope,
            readModelMemoryLimit = { _, _ -> device })
        val blocked = repository.getParamsForModel("model.gguf", ModelProfile.CALENDAR).first()
        repository.updateParamsForModel("model.gguf", ModelProfile.CALENDAR,
            blocked.withContextResponseRatio(ContextResponseRatio.FOUR_TO_ONE))
        device = DeviceContextLimit(8192, MemoryLimitStatus.ESTIMATED)
        val restored = repository.getParamsForModel("model.gguf", ModelProfile.CALENDAR).first()
        assertEquals(4096, restored.contextSize)
        assertEquals(1024, restored.maxTokens)
    }

    @Test fun `memory refresh does not persist a smaller ceiling or change the selected profile`() = runTest {
        var limit = 4096
        val revision = MutableStateFlow(0L)
        val store = TestPreferencesDataStore(preferencesOf(key("contextSize") to 8192))
        val repository = DataStoreSettingsRepository(store, backgroundScope,
            readModelContextLength = { 32768 },
            readModelMemoryLimit = { _, _ -> DeviceContextLimit(limit, MemoryLimitStatus.ESTIMATED) },
            memoryChanges = revision, refreshMemory = { revision.value++ })
        assertEquals(4096, repository.getParamsForModel("model.gguf", ModelProfile.CHAT).first().contextSize)
        limit = 2048
        repository.refreshModelMemory()
        assertEquals(1L, revision.value)
        assertEquals(2048, repository.getParamsForModel("model.gguf", ModelProfile.CHAT).first().contextSize)
        assertEquals(8192, store.data.first()[key("contextSize")])
        assertEquals(512, repository.getParamsForModel("model.gguf", ModelProfile.CALENDAR).first().contextSize)
    }
}
