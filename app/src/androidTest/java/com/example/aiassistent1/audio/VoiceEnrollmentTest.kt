package com.example.aiassistent1.audio

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.VoiceProfileManager
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.model.VoiceEmbedding
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceEnrollmentTest {
    @Test fun independentEnrollmentSurvivesTogglesRenameAndFailedReplacement() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "voice-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profile = File(directory, "voice_profile.bin")
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") }
            val settings = DataStoreSettingsRepository(data, scope, profile)
            var commitFailure = false
            val guardedSettings = object : SettingsRepository by settings {
                override suspend fun completeVoiceEnrollment(revision: Long): Boolean {
                    if (commitFailure) throw java.io.IOException("simulated preference commit failure")
                    return settings.completeVoiceEnrollment(revision)
                }
            }
            var embedding: FloatArray? = FloatArray(32) { 1f }
            var computations = 0
            val speaker = object : SpeakerIdentifier {
                override suspend fun prepare() = Unit
                override suspend fun computeEmbedding(samples: FloatArray): FloatArray? { computations++; return embedding }
                override fun verify(embedding1: FloatArray, embedding2: FloatArray, threshold: Float) =
                    VoiceEmbedding.matches(embedding1, embedding2, threshold)
                override fun close() = Unit
            }
            val manager = VoiceProfileManager(context, speaker, guardedSettings)
            val sample = FloatArray(16_000) { .1f }
            val wordFile = File(directory, "metric_kws_profile.bin").apply { writeBytes(byteArrayOf(6, 5, 4)) }
            assertFalse(settings.readAudioPreferences().voiceIdEnabled)
            assertTrue(manager.enroll(sample))
            assertTrue(profile.exists())
            val saved = profile.readBytes()
            val calls = computations
            assertTrue(manager.verify(sample))
            assertEquals(calls, computations)
            assertTrue(manager.status().profileReady)
            settings.setVoiceIdEnabled(true)
            assertTrue(manager.verify(sample))
            settings.setCustomWakeWord("Алиса")
            assertArrayEquals(saved, profile.readBytes())
            settings.setVoiceIdEnabled(false); settings.setVoiceIdEnabled(true)
            assertArrayEquals(saved, profile.readBytes())
            assertFalse(manager.needsEnrollment())
            embedding = null
            assertFalse(manager.enroll(sample))
            assertArrayEquals(saved, profile.readBytes())
            assertFalse(settings.readAudioPreferences().needsEnrollment)
            assertFalse(manager.verify(sample))
            embedding = FloatArray(32) { if (it % 2 == 0) 1f else -1f }
            commitFailure = true
            assertTrue(runCatching { manager.enroll(sample) }.isFailure)
            assertArrayEquals(saved, profile.readBytes())
            commitFailure = false
            assertTrue(manager.enroll(sample))
            profile.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(manager.verify(sample))
            manager.delete()
            assertFalse(profile.exists()); assertTrue(manager.needsEnrollment())
            assertArrayEquals(byteArrayOf(6, 5, 4), wordFile.readBytes())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
