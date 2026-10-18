package com.example.aiassistent1.audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.aiassistent1.MainActivity
import com.example.aiassistent1.data.provider.SherpaOnnxVoiceInputProvider
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.model.VoiceActivationMode
import com.example.aiassistent1.presentation.playback.SpeechPlaybackController
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.service.GenerationForegroundService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in acoustic regression. Run only with a silent user and an unlocked physical phone. */
class VoiceSelfEchoDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    @Test fun realNarratorCannotBecomeUserInputInDirectMode() = runBlocking {
        assumeTrue("Requires -e liveAudio true and a silent user",
            InstrumentationRegistry.getArguments().getString("liveAudio") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = File(context.cacheDir, "voice-self-echo-probe.txt")
        report.writeText("Real TTS / silent user: ${Build.MODEL}, API ${Build.VERSION.SDK_INT}\n")
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Разрешите приложению доступ к микрофону перед проверкой"
        }
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var activity: MainActivity? = null
        withTimeout(60_000) {
            while (activity == null) {
                instrumentation.runOnMainSync {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                if (activity == null) delay(250)
            }
        }
        var keptScreenOn = false
        withContext(Dispatchers.Main) {
            val host = checkNotNull(activity)
            keptScreenOn = host.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val viewModel = ViewModelProvider(host)[ChatViewModel::class.java]
            viewModel.stopSpeechPlayback()
            viewModel.setVoiceMode(false)
        }
        val settings = AppModule.provideSettingsRepository(context)
        val preferences = settings.readAudioPreferences()
        check(preferences.activationMode == VoiceActivationMode.DIRECT && !preferences.voiceIdEnabled && preferences.bargeIn) {
            "Для воспроизведения сбоя выберите обычный ввод, включите перебивание и выключите Voice ID"
        }
        val provider = AppModule.provideVoiceInputProvider(context) as SherpaOnnxVoiceInputProvider
        val controller = withContext(Dispatchers.Main) {
            SpeechPlaybackController(context, AppModule.provideSpeechPlayback(context))
        }
        val foreground = withContext(Dispatchers.Main) {
            GenerationForegroundService.acquire(context, GenerationForegroundService.Kind.Microphone)
        }
        val commands = AtomicInteger()
        val interruptions = AtomicInteger()
        val failures = mutableListOf<String>()
        val beganAt = SystemClock.elapsedRealtime()
        var readyAt = 0L
        val input = launch(start = CoroutineStart.UNDISPATCHED) {
            provider.observeInput().collect { event ->
                commands.incrementAndGet()
                // Keep only timing and character count; never persist microphone PCM or transcript.
                report.appendText("commandAtMs=${SystemClock.elapsedRealtime() - beganAt} chars=${event.transcript.length}\n")
            }
        }
        val starts = launch(start = CoroutineStart.UNDISPATCHED) {
            provider.observeSpeechStart().collect {
                interruptions.incrementAndGet()
                report.appendText("interruptionAtMs=${SystemClock.elapsedRealtime() - beganAt}\n")
            }
        }
        val errors = launch(start = CoroutineStart.UNDISPATCHED) {
            provider.observeErrors().collect { failures += it.cause.javaClass.simpleName + ": " + it.cause.message }
        }
        try {
            foreground.awaitReady()
            val captureSession = provider.startBargeIn()
            withContext(Dispatchers.Main) {
                // Start TTS immediately. Its production barrier, not a test-only Listening wait,
                // must confirm actual capture/AEC readiness before the first sound.
                assertTrue(controller.speak(TEXT) {
                    withTimeout(30_000) { provider.awaitReady(captureSession) }
                    if (readyAt == 0L) readyAt = SystemClock.elapsedRealtime()
                })
            }
            withTimeout(35_000) {
                controller.state.first { it is SpeechPlaybackState.Playing || it is SpeechPlaybackState.Error }
            }
            assertTrue("TTS did not begin: ${controller.state.value}", controller.state.value is SpeechPlaybackState.Playing)
            assertTrue("Speech started before microphone readiness", readyAt > 0)
            report.appendText("captureReadyAtMs=${readyAt - beganAt} playingObservedAtMs=${SystemClock.elapsedRealtime() - beganAt}\n")
            withTimeout(40_000) {
                controller.state.first { it is SpeechPlaybackState.Idle || it is SpeechPlaybackState.Error }
            }
            assertTrue("TTS failed: ${controller.state.value}", controller.state.value is SpeechPlaybackState.Idle)
            delay(2_000)
            val processing = AppModule.provideAudioProcessingManager(context)
            val statistics = processing.captureStatistics()
            report.appendText("capture=$statistics guard=${processing.speechEchoGuard.diagnostics.value}\n")
            report.appendText("commands=${commands.get()} interruptions=${interruptions.get()} errors=$failures\n")
            assertTrue("Capture failed: $failures", failures.isEmpty())
            assertNotNull("Microphone session was not active", statistics)
            assertTrue("No microphone sound or render reference was measured: $statistics",
                statistics!!.samples > 16_000 && statistics.rawRms > .00001 && statistics.referenceSamples > 1600)
            assertEquals("Narrator was accepted as an interrupting voice", 0, interruptions.get())
            assertEquals("Narrator was accepted as a user message", 0, commands.get())
        } finally {
            provider.stop()
            input.cancelAndJoin(); starts.cancelAndJoin(); errors.cancelAndJoin()
            provider.close()
            withContext(Dispatchers.Main) {
                controller.close()
                foreground.close()
                if (!keptScreenOn) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private companion object {
        const val TEXT = "Солнечная система. В её центре находится Солнце. Вокруг Солнца движутся планеты. " +
            "Меркурий расположен ближе остальных. Земля имеет один естественный спутник. " +
            "Юпитер является самой большой планетой. Это проверка озвучивания."
    }
}
