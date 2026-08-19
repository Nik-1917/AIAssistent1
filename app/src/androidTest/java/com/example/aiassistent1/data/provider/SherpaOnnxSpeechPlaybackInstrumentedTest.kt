package com.example.aiassistent1.data.provider

import android.os.SystemClock
import com.example.aiassistent1.domain.interfaces.SpeechSynthesizer
import com.example.aiassistent1.domain.model.SynthesizedSpeech
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SherpaOnnxSpeechPlaybackInstrumentedTest {
    @Test
    fun `plays synthesized pcm through AudioTrack and releases it`() = runBlocking {
        val synthesizer = FakeSpeechSynthesizer()
        val playback = SherpaOnnxSpeechPlayback(synthesizer)
        var playbackStarted = false

        val startedAt = SystemClock.elapsedRealtime()
        val result = try {
            playback.speak("Тест") { playbackStarted = true }
        } finally {
            playback.close()
        }
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt

        assertTrue(result.exceptionOrNull()?.message ?: "AudioTrack playback failed", result.isSuccess)
        assertTrue("AudioTrack did not start", playbackStarted)
        assertTrue(
            "AudioTrack был остановлен до воспроизведения PCM: ${elapsedMillis}мс",
            elapsedMillis >= MINIMUM_EXPECTED_PLAYBACK_MILLIS,
        )
        assertEquals(1, synthesizer.calls)
        assertTrue("Synthesizer was not released", synthesizer.closed)
    }

    private class FakeSpeechSynthesizer : SpeechSynthesizer {
        var calls = 0
        var closed = false

        override suspend fun synthesize(text: String): Result<SynthesizedSpeech> {
            calls += 1
            return Result.success(
                SynthesizedSpeech(
                    samples = FloatArray(SAMPLE_COUNT) { SAMPLE_AMPLITUDE },
                    sampleRate = SAMPLE_RATE,
                ),
            )
        }

        override fun close() {
            closed = true
        }

        private companion object {
            const val SAMPLE_RATE = 16_000
            const val SAMPLE_COUNT = 8_000
            const val SAMPLE_AMPLITUDE = 0.1f
        }
    }

    private companion object {
        const val MINIMUM_EXPECTED_PLAYBACK_MILLIS = 350L
    }
}
