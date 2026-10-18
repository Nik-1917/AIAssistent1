package com.example.aiassistent1

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.interfaces.SpeechPlayback
import com.example.aiassistent1.presentation.playback.SpeechPlaybackController
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState
import com.example.aiassistent1.presentation.playback.SpeechStopReason
import com.example.aiassistent1.service.GenerationForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies real power locks with controlled audio; physical sensor/audio checks remain manual. */
@RunWith(AndroidJUnit4::class)
class PlaybackProximityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun hasActiveLock(tag: String): Boolean = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("dumpsys power"),
    ).bufferedReader().use { reader ->
        // Released locks also appear in MIUI's WakeLockLog.
        reader.readText().lineSequence().any {
            it.contains(tag) && (it.trimStart().startsWith("PARTIAL_WAKE_LOCK") ||
                it.trimStart().startsWith("PROXIMITY_SCREEN_OFF_WAKE_LOCK"))
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(20_000) {
        while (!predicate()) delay(100)
    }

    @Test
    fun proximityFollowsListeningAndSettingsAndReleasesForEveryExit() = runBlocking {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPROXIMITY_CHECK: launching activity\n") })
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = AppModule.provideSettingsRepository(context)
            val savedEnabled = settings.screenPreferences.first().proximityScreenOffEnabled
            val supported = context.getSystemService(PowerManager::class.java)
                .isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
            var generation: GenerationForegroundService.Session? = null
            try {
                settings.setProximityScreenOffEnabled(false)
                withContext(Dispatchers.Main) {
                    generation = GenerationForegroundService.acquire(context, GenerationForegroundService.Kind.Generation)
                    generation!!.awaitReady()
                    // A non-speech session must never enable proximity, even if misreported as playing.
                    generation!!.markPlaybackStarted()
                }
                assertTrue(hasActiveLock("aiassistent1:assistantCpu"))
                assertFalse(hasActiveLock("aiassistent1:assistantProximity"))

                for (exit in listOf("complete", "stop", "error", "close")) {
                    val playback = ControlledPlayback()
                    val controller = withContext(Dispatchers.Main) { SpeechPlaybackController(context, playback) }
                    try {
                        settings.setProximityScreenOffEnabled(true)
                        withContext(Dispatchers.Main) { assertTrue(controller.speak("Проверка погашения экрана")) }
                        withTimeout(20_000) { playback.preparing.await() }
                        assertTrue(controller.state.value is SpeechPlaybackState.Generating)
                        assertFalse(hasActiveLock("aiassistent1:assistantProximity"))

                        playback.begin.complete(Unit)
                        waitUntil { controller.state.value is SpeechPlaybackState.Playing }
                        waitUntil { hasActiveLock("aiassistent1:assistantProximity") == supported }
                        settings.setProximityScreenOffEnabled(false)
                        waitUntil { !hasActiveLock("aiassistent1:assistantProximity") }
                        assertTrue(controller.state.value is SpeechPlaybackState.Playing)
                        assertTrue(hasActiveLock("aiassistent1:assistantCpu"))
                        settings.setProximityScreenOffEnabled(true)
                        waitUntil { hasActiveLock("aiassistent1:assistantProximity") == supported }

                        when (exit) {
                            "complete" -> {
                                playback.finish.complete(Result.success(Unit))
                                waitUntil { controller.state.value is SpeechPlaybackState.Idle }
                            }
                            "error" -> {
                                playback.finish.complete(Result.failure(IllegalStateException("Тестовая ошибка")))
                                waitUntil { controller.state.value is SpeechPlaybackState.Error }
                            }
                            "stop" -> withContext(Dispatchers.Main) { controller.stop(SpeechStopReason.User) }
                            "close" -> withContext(Dispatchers.Main) { controller.close() }
                        }
                        waitUntil { !hasActiveLock("aiassistent1:assistantProximity") }
                        assertTrue(hasActiveLock("aiassistent1:assistantCpu"))
                        // A late audio-thread callback must not reacquire proximity after playback ends.
                        withContext(Dispatchers.Default) { playback.replayStarted?.invoke() }
                        instrumentation.waitForIdleSync()
                        assertFalse(hasActiveLock("aiassistent1:assistantProximity"))
                    } finally {
                        withContext(Dispatchers.Main) { controller.close() }
                    }
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPROXIMITY_CHECK: $exit passed\n") })
                }
            } finally {
                withContext(Dispatchers.Main) { generation?.close() }
                settings.setProximityScreenOffEnabled(savedEnabled)
            }
            waitUntil { !hasActiveLock("aiassistent1:assistantCpu") }
            assertFalse(hasActiveLock("aiassistent1:assistantProximity"))
        }
    }

    private class ControlledPlayback : SpeechPlayback {
        val preparing = CompletableDeferred<Unit>()
        val begin = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Result<Unit>>()
        @Volatile var replayStarted: (() -> Unit)? = null

        override suspend fun speak(text: String, onPlaybackStarted: () -> Unit): Result<Unit> {
            replayStarted = onPlaybackStarted
            preparing.complete(Unit)
            begin.await()
            // The real AudioTrack implementation also reports playback from a worker thread.
            withContext(Dispatchers.Default) { onPlaybackStarted() }
            return finish.await()
        }

        override fun stop() = Unit
        override fun close() = Unit
    }
}
