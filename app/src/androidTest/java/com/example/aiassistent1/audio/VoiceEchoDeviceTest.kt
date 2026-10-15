package com.example.aiassistent1.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.aiassistent1.data.provider.AudioProcessingManager
import com.example.aiassistent1.data.provider.RecordingSampleClock
import com.example.aiassistent1.data.provider.SherpaOnnxSpeechPlayback
import com.example.aiassistent1.domain.interfaces.SpeechSynthesizer
import com.example.aiassistent1.domain.model.SynthesizedSpeech
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import kotlin.math.*

/** Acoustic measurements use an analytic reference, never save microphone PCM or user settings. */
class VoiceEchoDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(100)

    @Test fun compareSoftwareAndSystemEchoOnTheConnectedPhone() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = File(instrumentation.targetContext.cacheDir, "voice-echo-probe.txt")
        report.writeText("Acoustic probe: ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}\n")
        // MIUI can reject startActivitySync indefinitely. A non-blocking launch lets the user open
        // the app manually, without bypassing the phone's restrictions on background launches.
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, VoiceEchoProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var foreground: Activity? = null
        withTimeout(60_000) {
            while (foreground == null) {
                instrumentation.runOnMainSync {
                    foreground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                }
                if (foreground == null) delay(250)
            }
        }
        var keepScreenOn = false
        instrumentation.runOnMainSync {
            keepScreenOn = foreground!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            foreground!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        try {
        check(ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Перед акустической проверкой разрешите приложению доступ к микрофону в настройках Android"
        }
        val stimulus = FloatArray(16_000 * 6) { index ->
            val t = index / 16_000.0
            val envelope = .55 + .45 * sin(t * 7.3).pow(2)
            ((sin(2 * PI * (210 * t + 27 * t * t)) + .6 * sin(2 * PI * 853 * t) +
                .4 * sin(2 * PI * 1709 * t)) * .09 * envelope).toFloat()
        }
        for (backend in AudioProcessingManager.EchoBackend.entries) {
            val source = if (backend == AudioProcessingManager.EchoBackend.ANDROID)
                MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(source, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(8192, minimum))
            val processing = AudioProcessingManager()
            val session = processing.open(recorder, true, true, backend)
            val playback = SherpaOnnxSpeechPlayback(object : SpeechSynthesizer {
                override suspend fun synthesize(text: String) = Result.success(SynthesizedSpeech(stimulus, 16_000))
                override fun close() = Unit
            }, processing)
            try {
                recorder.startRecording()
                val sound = async { delay(300); playback.speak("Акустическая проверка") {}.getOrThrow() }
                var rawPower = 0.0
                var cleanPower = 0.0
                var rawCount = 0
                var cleanCount = 0
                withContext(Dispatchers.IO) {
                    val pcm = ShortArray(512)
                    val clock = RecordingSampleClock()
                    var total = 0
                    withTimeout(15_000) { while (total < 16_000 * 7) {
                        val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                        check(read >= 0)
                        if (read == 0) { delay(5); continue }
                        val input = FloatArray(read) { pcm[it] / 32768f }
                        val output = session.process(input, clock.firstSampleTimeNs(recorder, read))
                        assertTrue(output.all { it.isFinite() && it in -1f..1f })
                        if (total in (16_000 * 3)..(16_000 * 6)) {
                            rawPower += input.sumOf { it.toDouble() * it }; rawCount += input.size
                            cleanPower += output.sumOf { it.toDouble() * it }; cleanCount += output.size
                        }
                        total += read
                    } }
                }
                sound.await()
                val rawRms = sqrt(rawPower / rawCount.coerceAtLeast(1))
                val cleanRms = sqrt(cleanPower / cleanCount.coerceAtLeast(1))
                val measurement = "backend=$backend actual=${session.profile} rawRms=$rawRms cleanRms=$cleanRms reductionDb=${20 * log10((rawRms + 1e-9) / (cleanRms + 1e-9))}"
                Log.i("VoiceEchoProbe", measurement)
                report.appendText(measurement + "\n")
                assertTrue("No acoustic signal recorded", rawCount > 0 && cleanCount > 0 && rawRms > 1e-5)
            } finally {
                playback.close()
                runCatching { recorder.stop() }
                recorder.release()
                processing.close(session)
            }
            delay(300)
        }
        } finally {
            if (!keepScreenOn) instrumentation.runOnMainSync { foreground?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}
