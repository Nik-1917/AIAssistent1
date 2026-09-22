package com.example.aiassistent1.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.Mp3Encoder
import com.example.aiassistent1.data.provider.NativeAudio
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sin

class AudioNativeTest {
    @Test fun mp3IsDecodableAndEncoderIsIsolatedPerSession() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "encoder-test.mp3")
        try {
            file.outputStream().use { output ->
                Mp3Encoder().use { encoder ->
                    repeat(16) { block ->
                        output.write(encoder.encode(ShortArray(1000) { (sin((it + block * 1000) * 0.17) * 5000).toInt().toShort() }))
                    }
                    output.write(encoder.flush())
                    assertEquals(0, encoder.flush().size)
                }
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals("audio/mpeg", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                assertEquals(16_000, extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(1, extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally { extractor.release() }
            Mp3Encoder().use { assertTrue(it.encode(ShortArray(1600)).size >= 0); assertTrue(it.flush().isNotEmpty()) }
        } finally { file.delete() }
    }
    @Test fun webRtcProcessesCaptureAndRenderWithoutInvalidSamples() {
        val handle = NativeAudio.createProcessor(true)
        try {
            var difference = 0.0
            repeat(500) { block ->
                val far = FloatArray(160) { (sin((block * 160 + it) * .15) * .1).toFloat() }
                val near = far.copyOf()
                NativeAudio.processFrame(handle, far, true, 30)
                NativeAudio.processFrame(handle, near, false, 30)
                assertTrue(near.all { it.isFinite() && it in -1f..1f })
                difference += near.indices.sumOf { kotlin.math.abs(near[it] - far[it]).toDouble() }
            }
            assertTrue(difference > 1.0)
            try {
                NativeAudio.processFrame(handle, FloatArray(159), false, 0)
                fail("Invalid frame must be rejected")
            } catch (_: IllegalStateException) { }
        } finally { NativeAudio.closeProcessor(handle) }
    }
    @Test fun streamThreeHoursWithConstantPcmBuffer() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "three-hour-test.mp3")
        val buffer = ShortArray(16_000)
        val before = System.nanoTime()
        try {
            file.outputStream().buffered(64 * 1024).use { output ->
                Mp3Encoder().use { encoder ->
                    repeat(3 * 60 * 60) { output.write(encoder.encode(buffer)) }
                    output.write(encoder.flush())
                }
            }
            assertTrue(file.length() in 86_000_000L..87_000_000L)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val duration = extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
                assertTrue("duration=$duration", duration in 10_790_000_000L..10_810_000_000L)
            } finally { extractor.release() }
            android.util.Log.i("AudioV4Test", "3h MP3 bytes=${file.length()}, acceleratedMs=${(System.nanoTime()-before)/1_000_000}")
        } finally { file.delete() }
    }
}
