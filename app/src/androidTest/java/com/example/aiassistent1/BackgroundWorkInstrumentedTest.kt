package com.example.aiassistent1

import android.app.ActivityManager
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.presentation.playback.SpeechPlaybackController
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.service.GenerationForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundWorkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun checkpoint(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBACKGROUND_CHECK: $message\n") })
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    @Suppress("DEPRECATION")
    private fun runningService() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).firstOrNull { it.service.className == GenerationForegroundService::class.java.name }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(20_000) {
        while (!predicate()) delay(100)
    }

    // MIUI also includes released locks in WakeLockLog; inspect only active lock lines.
    private fun hasActiveLock(tag: String) = shell("dumpsys power").lineSequence().any {
        it.contains(tag) && (it.trimStart().startsWith("PARTIAL_WAKE_LOCK") ||
            it.trimStart().startsWith("PROXIMITY_SCREEN_OFF_WAKE_LOCK"))
    }

    @Test
    fun overlappingWorkKeepsServiceAndReleasesPowerAfterLastSession() = runBlocking {
        checkpoint("overlap: waking screen")
        shell("input keyevent 224")
        checkpoint("overlap: launching activity")
        ActivityScenario.launch(MainActivity::class.java).use {
            checkpoint("overlap: activity launched")
            var generation: GenerationForegroundService.Session? = null
            var speech: GenerationForegroundService.Session? = null
            try {
                withContext(Dispatchers.Main) {
                    generation = GenerationForegroundService.acquire(context, GenerationForegroundService.Kind.Generation)
                    generation!!.awaitReady()
                    speech = GenerationForegroundService.acquire(context, GenerationForegroundService.Kind.Speech)
                    speech!!.awaitReady()
                    generation!!.close()
                }
                assertTrue(runningService()?.foreground == true)
                assertTrue(hasActiveLock("aiassistent1:assistantCpu"))
                val power = context.getSystemService(PowerManager::class.java)
                if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    assertTrue(hasActiveLock("aiassistent1:assistantProximity"))
                }
            } finally {
                withContext(Dispatchers.Main) { generation?.close(); speech?.close() }
            }
            waitUntil { runningService() == null }
            assertFalse(hasActiveLock("aiassistent1:assistantCpu"))
            assertFalse(hasActiveLock("aiassistent1:assistantProximity"))
        }
    }

    @Test
    fun playbackSessionSurvivesScreenOff() = runBlocking {
        shell("input keyevent 224")
        ActivityScenario.launch(MainActivity::class.java).use {
            val finish = CompletableDeferred<Unit>()
            val playback = object : SpeechPlayback {
                override suspend fun speak(text: String, onPlaybackStarted: () -> Unit): Result<Unit> {
                    onPlaybackStarted()
                    finish.await()
                    return Result.success(Unit)
                }
                override fun stop() = Unit
                override fun close() = Unit
            }
            val controller = withContext(Dispatchers.Main) { SpeechPlaybackController(context, playback) }
            try {
                withContext(Dispatchers.Main) { assertTrue(controller.speak("Проверка фоновой сессии")) }
                waitUntil { controller.state.value is SpeechPlaybackState.Playing }
                shell("input keyevent 223")
                delay(1_000)
                assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
                assertTrue(controller.state.value is SpeechPlaybackState.Playing)
                assertTrue(runningService()?.foreground == true)
                finish.complete(Unit)
                waitUntil { controller.state.value is SpeechPlaybackState.Idle }
                waitUntil { runningService() == null }
            } finally {
                withContext(Dispatchers.Main) { controller.close() }
                shell("input keyevent 224")
            }
        }
    }

    @Test
    fun realMicrophoneAndVoiceModeSurviveScreenOff() = runBlocking {
        checkpoint("microphone: waking screen")
        shell("input keyevent 224")
        checkpoint("microphone: launching activity")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            checkpoint("microphone: activity launched")
            lateinit var viewModel: ChatViewModel
            scenario.onActivity { viewModel = ViewModelProvider(it)[ChatViewModel::class.java] }
            val audio = context.getSystemService(AudioManager::class.java)
            try {
                waitUntil { !viewModel.uiState.value.isProcessing }
                withContext(Dispatchers.Main) { viewModel.setVoiceMode(true) }
                checkpoint("microphone: waiting for unsilenced recording")
                waitUntil { audio.activeRecordingConfigurations.any { !it.isClientSilenced } }
                shell("input keyevent 223")
                delay(1_500)
                assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
                assertTrue(viewModel.uiState.value.error, viewModel.uiState.value.isVoiceMode)
                assertTrue(audio.activeRecordingConfigurations.any { !it.isClientSilenced })
                assertTrue(runningService()?.foreground == true)
            } finally {
                withContext(Dispatchers.Main) { viewModel.setVoiceMode(false) }
                shell("input keyevent 224")
            }
            waitUntil { runningService() == null }
        }
    }
}
