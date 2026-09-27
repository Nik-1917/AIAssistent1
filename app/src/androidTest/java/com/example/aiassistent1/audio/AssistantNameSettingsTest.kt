package com.example.aiassistent1.audio

import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.BundledMetricKwsEngine
import com.example.aiassistent1.data.provider.MetricKwsProfileManager
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.interfaces.PersonalKeywordControls
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.usecase.PersonalKeywordActivation
import com.example.aiassistent1.presentation.ui.AudioSettingsSection
import com.example.aiassistent1.presentation.ui.ChatTopBar
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real local ASR, v3, storage and Compose; prerecorded Russian speech, no live microphone. */
class AssistantNameSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Before fun grantPermission() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "pm grant " + instrument.targetContext.packageName + " android.permission.RECORD_AUDIO")).use { it.readBytes() }
    }

    private fun pcm(name: String): FloatArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val data = ByteBuffer.wrap(assets.open("metric_kws_ru_v3/$name.bin").use { it.readBytes() })
            .order(ByteOrder.LITTLE_ENDIAN)
        check(data.int == 0x31464b4d)
        val count = data.int
        check(data.int == 4040)
        return FloatArray(count) { data.float }
    }

    @Test fun recordedNameAppearsInFieldAndHeaderAndEditingLeavesAudioUntouched() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "assistant-name-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = storageScope) { File(directory, "settings.preferences_pb") }
        val settings = DataStoreSettingsRepository(data, storageScope)
        val profiles = MetricKwsProfileManager(context)
        val activation = PersonalKeywordActivation(BundledMetricKwsEngine.fromAssets(base), profiles,
            onProfileChanged = settings::invalidateAudioSession)
        val recognizer = AppModule.provideSpeechRecognizer(base)
        try {
            settings.setCustomWakeWord("Ассистент")
            val voiceFile = File(directory, "voice_profile.bin").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            var captured = FloatArray(0)
            var transcript = ""
            val controls = object : PersonalKeywordControls {
                override suspend fun keywordStatus() = activation.status(prepareModel = false)
                override suspend fun enrollKeyword() {
                    captured = pcm("ru_00_0")
                    activation.enrollNamedFrom({ captured }, {
                        recognizer.recognize(it).getOrThrow().also { text -> transcript = text }
                    }, settings::setAssistantDisplayName)
                }
                override suspend fun deleteKeyword() = activation.delete()
                override suspend fun selectActivationMode(mode: VoiceActivationMode) = settings.setVoiceActivationMode(mode)
            }
            compose.setContent {
                val name by settings.assistantDisplayName.collectAsStateWithLifecycle(AssistantDisplayName.DEFAULT)
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        ChatTopBar(assistantName = name, modelState = ModelState.Unloaded, isProcessing = false,
                            hasMessages = false, modelAvailability = ModelAvailability.Available,
                            selectedModel = "", availableModels = emptyList(), onStop = {}, onClearChat = {},
                            onLoadModel = {}, onSelectModel = {}, onOpenSettings = {}, onOpenConferences = {},
                            audioStatus = null, isCalendarMode = false, onModeToggle = {})
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            AudioSettingsSection(keywordControls = controls, settingsRepository = settings)
                        }
                    }
                }
            }
            fun nameField() = compose.onNode(hasSetTextAction() and hasText("Имя ассистента"))
            fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
            fun awaitWordSaved() = compose.waitUntil(30_000) {
                compose.onAllNodes(hasText("Перезаписать слово") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            fun assertDisplayed(name: String, header: String = name.replaceFirstChar { it.titlecase() }) {
                compose.onNode(hasText(name) and hasSetTextAction()).assertExists()
                compose.onNode(hasText(header) and !hasSetTextAction()).assertIsDisplayed()
                compose.onNodeWithText("Чат").assertIsDisplayed()
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasSetTextAction() and hasText("Имя ассистента")).fetchSemanticsNodes().size == 1
            }
            assertDisplayed("AI Assistant")
            click("Записать слово")
            awaitWordSaved()
            val recognizedName = AssistantDisplayName.fromTranscript(transcript)
            assertTrue(recognizedName.isNotBlank())
            assertTrue(captured.all { it == 0f })
            compose.waitForIdle()
            assertDisplayed(recognizedName)
            nameField().performScrollTo().performTextReplacement("Несохранённый черновик")
            click("Перезаписать слово")
            awaitWordSaved()
            assertDisplayed(recognizedName)
            settings.setVoiceActivationMode(VoiceActivationMode.PERSONAL_WORD)
            val before = settings.readAudioPreferences()
            val wordFile = File(directory, "metric_kws_profile.bin")
            val originalWord = wordFile.readBytes()
            nameField().performScrollTo().performTextReplacement("мой помощник")
            click("Сохранить имя")
            compose.waitUntil(5_000) { runBlocking { settings.assistantDisplayName.first() == "мой помощник" } }
            assertDisplayed("мой помощник", "Мой помощник")
            assertEquals(before, settings.readAudioPreferences())
            assertArrayEquals(originalWord, wordFile.readBytes())
            assertArrayEquals(byteArrayOf(7, 8, 9), voiceFile.readBytes())
            assertEquals(MetricKwsDecision.Accept, activation.evaluate(pcm("ru_00_1"), WakeWordEngine.METRIC_KWS))
            assertEquals(MetricKwsDecision.Reject, activation.evaluate(pcm("ru_02_1"), WakeWordEngine.METRIC_KWS))
            assertEquals("мой помощник", DataStoreSettingsRepository(data, storageScope).assistantDisplayName.first())
            for ((stored, header) in listOf("алиса" to "Алиса", "ёлка" to "Ёлка", "aLice" to "ALice")) {
                nameField().performScrollTo().performTextReplacement(stored)
                click("Сохранить имя")
                compose.waitUntil(5_000) { runBlocking { settings.assistantDisplayName.first() == stored } }
                assertDisplayed(stored, header)
                assertEquals(before, settings.readAudioPreferences())
                assertArrayEquals(originalWord, wordFile.readBytes())
                assertArrayEquals(byteArrayOf(7, 8, 9), voiceFile.readBytes())
            }
            nameField().performScrollTo().performTextReplacement("")
            click("Сохранить имя")
            compose.waitUntil(5_000) { runBlocking { settings.assistantDisplayName.first() == "AI Assistant" } }
            assertDisplayed("AI Assistant")
            assertEquals(before, settings.readAudioPreferences())
            assertArrayEquals(originalWord, wordFile.readBytes())
        } finally {
            activation.close(); recognizer.close()
            storageScope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
