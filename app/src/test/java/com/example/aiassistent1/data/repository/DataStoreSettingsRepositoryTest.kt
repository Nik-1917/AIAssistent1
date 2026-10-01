package com.example.aiassistent1.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.AppNavigationState
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.CpuThreadSettings
import com.example.aiassistent1.domain.model.ModelProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `old saved mode is restored when page preference does not yet exist`() = runTest {
        for (isCalendar in listOf(false, true)) {
            val store = TestPreferencesDataStore(preferencesOf(booleanPreferencesKey("system_prompt_enabled") to isCalendar))
            val repository = DataStoreSettingsRepository(store, backgroundScope)

            assertEquals(AppNavigationState(AppDestination.CHAT, isCalendar), repository.navigationState.first())
        }
    }

    @Test
    fun `new installation uses calendar conversation and unknown destination keeps saved mode`() = runTest {
        assertEquals(
            AppNavigationState(AppDestination.CHAT, true),
            DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope).navigationState.first(),
        )
        val store = TestPreferencesDataStore(preferencesOf(
            stringPreferencesKey("last_app_destination") to "UNKNOWN",
            booleanPreferencesKey("system_prompt_enabled") to false,
        ))
        assertEquals(
            AppNavigationState(AppDestination.CHAT, false),
            DataStoreSettingsRepository(store, backgroundScope).navigationState.first(),
        )
    }

    @Test
    fun `opening chat from calendar saves destination and mode in one emission`() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        repository.setAppDestination(AppDestination.CALENDAR)
        val states = mutableListOf<AppNavigationState>()
        backgroundScope.launch { repository.navigationState.collect { states += it } }
        runCurrent()

        repository.setSystemPromptEnabled(false)
        runCurrent()

        assertEquals(listOf(
            AppNavigationState(AppDestination.CALENDAR, true),
            AppNavigationState(AppDestination.CHAT, false),
        ), states)
    }

    @Test
    fun `calendar page remembers conversation mode when returning`() = runTest {
        val repository = DataStoreSettingsRepository(TestPreferencesDataStore(), backgroundScope)
        for (isCalendar in listOf(false, true)) {
            repository.setSystemPromptEnabled(isCalendar)
            repository.setAppDestination(AppDestination.CALENDAR)
            assertEquals(AppNavigationState(AppDestination.CALENDAR, isCalendar), repository.navigationState.first())
            repository.setAppDestination(AppDestination.CHAT)
            assertEquals(AppNavigationState(AppDestination.CHAT, isCalendar), repository.navigationState.first())
        }
    }

    @Test
    fun `restores all pages from the reopened actual preferences file`() = runTest {
        for (state in listOf(
            AppNavigationState(AppDestination.CHAT, false),
            AppNavigationState(AppDestination.CHAT, true),
            AppNavigationState(AppDestination.CALENDAR, false),
            AppNavigationState(AppDestination.CALENDAR, true),
        )) {
            val file = File(temporaryFolder.newFolder(), "settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
            val writerStore = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
            // Seed a disk snapshot in one transaction; repository mutations are tested above.
            writerStore.edit { preferences ->
                preferences[booleanPreferencesKey("system_prompt_enabled")] = state.isCalendarMode
                preferences[stringPreferencesKey("last_app_destination")] = state.destination.name
            }
            writerJob.cancelAndJoin()

            val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
            val reader = DataStoreSettingsRepository(readerStore, backgroundScope)
            assertEquals(state, reader.navigationState.first())
        }
    }
    @Test
    fun `compact date mode defaults to compact and persists both switch positions`() = runTest {
        val store = TestPreferencesDataStore()
        val writer = DataStoreSettingsRepository(store, backgroundScope)
        runCurrent()
        assertEquals(true, writer.compactDatesEnabled.value)
        for (enabled in listOf(true, false)) {
            writer.setCompactDatesEnabled(enabled)
            runCurrent()
            assertEquals(enabled, writer.compactDatesEnabled.value)
            assertEquals(enabled, store.data.first()[booleanPreferencesKey("compact_dates_enabled")])
            val reader = DataStoreSettingsRepository(store, backgroundScope)
            runCurrent()
            assertEquals(enabled, reader.compactDatesEnabled.value)
        }
    }

    @Test
    fun `compact date mode restores both values from reopened preferences files`() = runTest {
        for (enabled in listOf(true, false)) {
            val file = File(temporaryFolder.newFolder(), "date-settings.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
            val writerStore = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
            // Single disk transaction, as in the navigation persistence test above.
            writerStore.edit { it[booleanPreferencesKey("compact_dates_enabled")] = enabled }
            writerJob.cancelAndJoin()
            val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
            val reader = DataStoreSettingsRepository(readerStore, backgroundScope)
            readerStore.data.first()
            runCurrent()
            assertEquals(enabled, reader.compactDatesEnabled.value)
        }
    }

    @Test
    fun `new profiles have independent defaults and only calendar inherits legacy settings`() = runTest {
        val legacy = GenerationParams(contextSize = 1952, maxTokens = 64, temperature = 0.55f,
            topP = 0.72f, topK = 32, repeatPenalty = 1.3f, gpuLayers = 2)
        val store = TestPreferencesDataStore(preferencesOf(
            intPreferencesKey("legacy_contextSize") to legacy.contextSize,
            intPreferencesKey("legacy_maxTokens") to legacy.maxTokens,
            floatPreferencesKey("legacy_temperature") to legacy.temperature,
            floatPreferencesKey("legacy_topP") to legacy.topP,
            intPreferencesKey("legacy_topK") to legacy.topK,
            floatPreferencesKey("legacy_repeatPenalty") to legacy.repeatPenalty,
            intPreferencesKey("legacy_gpuLayers") to legacy.gpuLayers,
        ))
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        for (profile in ModelProfile.entries) {
            assertEquals(profile.defaults, repository.getParamsForModel("new", profile).first())
        }
        assertEquals(legacy.copy(contextSize = 2048, maxTokens = 1024),
            repository.getParamsForModel("legacy", ModelProfile.CALENDAR).first())
        assertEquals(ModelProfile.CHAT.defaults, repository.getParamsForModel("legacy", ModelProfile.CHAT).first())

        val changed = ModelProfile.CALENDAR.defaults.withContextSize(1024)
        repository.updateParamsForModel("legacy", ModelProfile.CALENDAR, changed)
        val reopened = DataStoreSettingsRepository(store, backgroundScope)
        assertEquals(changed, reopened.getParamsForModel("legacy", ModelProfile.CALENDAR).first())
        assertEquals(ModelProfile.CHAT.defaults, reopened.getParamsForModel("legacy", ModelProfile.CHAT).first())
        // Reading and saving the calendar profile does not rewrite the legacy snapshot.
        assertEquals(1952, store.data.first()[intPreferencesKey("legacy_contextSize")])
        assertEquals(0.55f, store.data.first()[floatPreferencesKey("legacy_temperature")])
    }

    @Test
    fun `one atomic profile update keeps the other profile and other models unchanged`() = runTest {
        val store = TestPreferencesDataStore()
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        val calendarObserved = mutableListOf<GenerationParams>()
        val chatObserved = mutableListOf<GenerationParams>()
        backgroundScope.launch {
            repository.getParamsForModel("first", ModelProfile.CALENDAR).collect { calendarObserved += it }
        }
        backgroundScope.launch {
            repository.getParamsForModel("first", ModelProfile.CHAT).collect { chatObserved += it }
        }
        runCurrent()
        val requested = GenerationParams(contextSize = 4096, maxTokens = 64, temperature = 0.8f,
            topP = 0.75f, topK = 32, repeatPenalty = 1.3f, gpuLayers = 2)
        val expected = requested.copy(maxTokens = 2048)
        repository.updateParamsForModel("first", ModelProfile.CALENDAR, requested)
        runCurrent()
        assertEquals(listOf(ModelProfile.CALENDAR.defaults, expected), calendarObserved)
        assertEquals(listOf(ModelProfile.CHAT.defaults), chatObserved)

        val chat = ModelProfile.CHAT.defaults.withMaxTokens(3072).copy(topK = 40)
        repository.updateParamsForModel("first", ModelProfile.CHAT, chat)
        runCurrent()
        assertEquals(listOf(ModelProfile.CALENDAR.defaults, expected), calendarObserved)
        assertEquals(listOf(ModelProfile.CHAT.defaults, chat), chatObserved)
        for (profile in ModelProfile.entries) {
            assertEquals(profile.defaults, repository.getParamsForModel("second", profile).first())
        }
        val saved = store.data.first()
        assertEquals(4096, saved[intPreferencesKey("model_params/calendar/first/contextSize")])
        assertEquals(2048, saved[intPreferencesKey("model_params/calendar/first/maxTokens")])
        assertEquals(6144, saved[intPreferencesKey("model_params/chat/first/contextSize")])
        assertEquals(3072, saved[intPreferencesKey("model_params/chat/first/maxTokens")])
        assertEquals(40, saved[intPreferencesKey("model_params/chat/first/topK")])
    }

    @Test
    fun `both model profiles survive reopening the real preferences file`() = runTest {
        val file = File(temporaryFolder.newFolder(), "model-settings.preferences_pb")
        val writerJob = Job(backgroundScope.coroutineContext[Job])
        val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
        val writerStore = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
        val snapshotStore = TestPreferencesDataStore()
        val writer = DataStoreSettingsRepository(snapshotStore, writerScope)
        val calendar = ModelProfile.CALENDAR.defaults.withMaxTokens(512).copy(
            temperature = 0.4f, batchSizeAuto = false, batchSize = 128, cpuThreadsAuto = false, cpuThreads = 1,
        )
        val chat = ModelProfile.CHAT.defaults.withMaxTokens(3072).copy(
            topK = 32, temperature = 0.8f, batchSize = 1024, cpuThreads = CpuThreadSettings.availableProcessors,
        )
        writer.updateParamsForModel("assistant.gguf", ModelProfile.CALENDAR, calendar)
        writer.updateParamsForModel("assistant.gguf", ModelProfile.CHAT, chat)
        // Write one complete snapshot: Android's mock SDK uses renameTo on Windows,
        // which cannot replace an existing file. Profile mutations are tested above.
        writerStore.updateData { snapshotStore.data.first() }
        writerJob.cancelAndJoin()

        val readerStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val reader = DataStoreSettingsRepository(readerStore, backgroundScope)
        assertEquals(calendar, reader.getParamsForModel("assistant.gguf", ModelProfile.CALENDAR).first())
        assertEquals(chat, reader.getParamsForModel("assistant.gguf", ModelProfile.CHAT).first())
    }

    @Test
    fun `model settings wait for storage instead of first emitting temporary defaults`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = ModelProfile.CHAT.defaults.withContextSize(4096).copy(temperature = 0.6f)
        val store = TestPreferencesDataStore(preferencesOf(
            intPreferencesKey("model_params/chat/assistant.gguf/contextSize") to saved.contextSize,
            floatPreferencesKey("model_params/chat/assistant.gguf/temperature") to saved.temperature,
        ), readGate = gate)
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        val firstValue = async { repository.getParamsForModel("assistant.gguf", ModelProfile.CHAT).first() }
        runCurrent()
        assertFalse(firstValue.isCompleted)
        gate.complete(Unit)
        assertEquals(saved, firstValue.await())
    }

    @Test
    fun `batch mode and manual value are saved per profile and invalid values are normalized`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(
            intPreferencesKey("model_params/calendar/assistant.gguf/batchSize") to 200,
            booleanPreferencesKey("model_params/calendar/assistant.gguf/batchSizeAuto") to false,
            intPreferencesKey("model_params/calendar/assistant.gguf/topK") to 0,
        ))
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        assertEquals(ModelProfile.CALENDAR.defaults.copy(batchSizeAuto = false, batchSize = 256, topK = 1),
            repository.getParamsForModel("assistant.gguf", ModelProfile.CALENDAR).first())
        assertEquals(ModelProfile.CHAT.defaults,
            repository.getParamsForModel("assistant.gguf", ModelProfile.CHAT).first())

        val manual = ModelProfile.CALENDAR.defaults.copy(batchSizeAuto = false, batchSize = 128, topK = 25)
        repository.updateParamsForModel("assistant.gguf", ModelProfile.CALENDAR, manual)
        val auto = ModelProfile.CHAT.defaults.copy(batchSizeAuto = true, batchSize = 1024)
        repository.updateParamsForModel("assistant.gguf", ModelProfile.CHAT, auto)
        assertEquals(manual, repository.getParamsForModel("assistant.gguf", ModelProfile.CALENDAR).first())
        assertEquals(auto, repository.getParamsForModel("assistant.gguf", ModelProfile.CHAT).first())
        assertEquals(ModelProfile.CALENDAR.defaults, repository.getParamsForModel("other.gguf", ModelProfile.CALENDAR).first())
        val saved = store.data.first()
        assertEquals(false, saved[booleanPreferencesKey("model_params/calendar/assistant.gguf/batchSizeAuto")])
        assertEquals(128, saved[intPreferencesKey("model_params/calendar/assistant.gguf/batchSize")])
        assertEquals(true, saved[booleanPreferencesKey("model_params/chat/assistant.gguf/batchSizeAuto")])
        assertEquals(1024, saved[intPreferencesKey("model_params/chat/assistant.gguf/batchSize")])
    }

    @Test
    fun `cpu mode and manual value persist atomically per model and profile with device bounds`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(
            booleanPreferencesKey("model_params/calendar/assistant.gguf/cpuThreadsAuto") to true,
            intPreferencesKey("model_params/calendar/assistant.gguf/cpuThreads") to Int.MAX_VALUE,
        ))
        val repository = DataStoreSettingsRepository(store, backgroundScope)
        assertEquals(ModelProfile.CALENDAR.defaults.copy(cpuThreads = CpuThreadSettings.availableProcessors),
            repository.getParamsForModel("assistant.gguf", ModelProfile.CALENDAR).first())
        assertEquals(ModelProfile.CHAT.defaults, repository.getParamsForModel("assistant.gguf", ModelProfile.CHAT).first())

        val observed = mutableListOf<GenerationParams>()
        backgroundScope.launch {
            repository.getParamsForModel("assistant.gguf", ModelProfile.CALENDAR).collect { observed += it }
        }
        runCurrent()
        val manual = ModelProfile.CALENDAR.defaults.copy(cpuThreadsAuto = false, cpuThreads = Int.MIN_VALUE)
        val expected = manual.copy(cpuThreads = 1)
        repository.updateParamsForModel("assistant.gguf", ModelProfile.CALENDAR, manual)
        runCurrent()
        val auto = ModelProfile.CHAT.defaults.copy(cpuThreadsAuto = true, cpuThreads = 1)
        repository.updateParamsForModel("assistant.gguf", ModelProfile.CHAT, auto)
        runCurrent()
        assertEquals(expected, observed.last())
        assertEquals(1, observed.drop(1).size)
        assertEquals(auto, repository.getParamsForModel("assistant.gguf", ModelProfile.CHAT).first())
        for (profile in ModelProfile.entries) {
            assertEquals(profile.defaults, repository.getParamsForModel("other.gguf", profile).first())
        }
        val saved = store.data.first()
        assertEquals(false, saved[booleanPreferencesKey("model_params/calendar/assistant.gguf/cpuThreadsAuto")])
        assertEquals(1, saved[intPreferencesKey("model_params/calendar/assistant.gguf/cpuThreads")])
        assertEquals(true, saved[booleanPreferencesKey("model_params/chat/assistant.gguf/cpuThreadsAuto")])
        assertEquals(1, saved[intPreferencesKey("model_params/chat/assistant.gguf/cpuThreads")])
    }
}
