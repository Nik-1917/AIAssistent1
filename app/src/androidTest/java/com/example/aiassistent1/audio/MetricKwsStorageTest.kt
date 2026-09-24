package com.example.aiassistent1.audio

import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.MetricKwsProfileManager
import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Storage tests only; not ONNX inference or Russian/device-microphone evidence. */
class MetricKwsStorageTest {
    @Test fun atomicRecoveryInvalidReplacementAndSeparateSpeakerStorage() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "metric-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val speaker = File(directory, "voice_profile.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manager = MetricKwsProfileManager(context)
        val profile = MetricKwsProfile(MetricKwsConfig("test", "a".repeat(64), "feature", 2, .8f),
            1, 1000, floatArrayOf(1f, 0f))
        try {
            assertNull(manager.load())
            manager.save(profile)
            assertArrayEquals(profile.embedding, manager.load()!!.embedding, 0f)
            assertTrue(runCatching { manager.save(profile.copy(embedding = floatArrayOf(Float.NaN, 0f))) }.isFailure)
            assertArrayEquals(profile.embedding, manager.load()!!.embedding, 0f)
            val file = AtomicFile(File(directory, "metric_kws_profile.bin"))
            val interrupted = file.startWrite()
            interrupted.write(byteArrayOf(42))
            file.failWrite(interrupted)
            assertArrayEquals(profile.embedding, manager.load()!!.embedding, 0f)
            manager.save(profile.copy(embedding = floatArrayOf(0f, 1f)))
            assertArrayEquals(floatArrayOf(0f, 1f), manager.load()!!.embedding, 0f)
            File(directory, "metric_kws_profile.bin").writeBytes(byteArrayOf(0))
            assertNull(manager.load())
            manager.delete()
            assertArrayEquals(byteArrayOf(1, 2, 3), speaker.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
