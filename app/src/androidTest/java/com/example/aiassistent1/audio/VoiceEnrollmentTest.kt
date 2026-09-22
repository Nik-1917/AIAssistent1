package com.example.aiassistent1.audio

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.VoiceProfileManager
import com.example.aiassistent1.data.repository.DataStoreSettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
import com.example.aiassistent1.domain.model.VoiceEmbedding
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceEnrollmentTest {
    @Test fun renameDeletesOldProfileAndFailedEnrollmentCannotAuthorize() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "voice-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profile = File(directory, "voice_profile.bin")
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") }
            val settings = DataStoreSettingsRepository(data, scope, profile)
            var embedding: FloatArray? = FloatArray(32) { 1f }
            val speaker = object : SpeakerIdentifier {
                override suspend fun prepare() = Unit
                override suspend fun computeEmbedding(samples: FloatArray) = embedding
                override fun verify(embedding1: FloatArray, embedding2: FloatArray, threshold: Float) =
                    VoiceEmbedding.matches(embedding1, embedding2, threshold)
                override fun close() = Unit
            }
            val manager = VoiceProfileManager(context, speaker, settings)
            val sample = FloatArray(16_000) { .1f }
            settings.setVoiceIdEnabled(true)
            assertTrue(manager.enroll(sample))
            assertTrue(profile.exists())
            assertTrue(manager.verify(sample))
            settings.setCustomWakeWord("Алиса")
            assertFalse(profile.exists())
            embedding = null
            assertFalse(manager.enroll(sample))
            assertTrue(settings.readAudioPreferences().needsEnrollment)
            assertFalse(manager.verify(sample))
            embedding = FloatArray(32) { 1f }
            assertTrue(manager.enroll(sample))
            profile.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(manager.verify(sample))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
