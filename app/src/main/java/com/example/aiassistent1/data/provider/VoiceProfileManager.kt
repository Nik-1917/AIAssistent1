package com.example.aiassistent1.data.provider

import android.content.Context
import android.util.AtomicFile
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
import com.example.aiassistent1.domain.model.VoiceEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class VoiceProfileManager(context: Context, private val speakerIdentifier: SpeakerIdentifier,
    private val settingsRepository: SettingsRepository) {
    private val profile = AtomicFile(File(context.noBackupFilesDir, "voice_profile.bin"))
    private val mutex = Mutex()

    suspend fun needsEnrollment(): Boolean {
        val p = settingsRepository.readAudioPreferences()
        return p.voiceIdEnabled && (p.needsEnrollment || loadProfile(p.revision) == null)
    }

    suspend fun enroll(samples: FloatArray): Boolean = mutex.withLock {
        val before = settingsRepository.readAudioPreferences()
        if (!before.voiceIdEnabled || samples.size < 8_000) return@withLock false
        val embedding = speakerIdentifier.computeEmbedding(samples) ?: return@withLock false
        if (!VoiceEmbedding.isValid(embedding)) return@withLock false
        withContext(Dispatchers.IO) {
            val stream = profile.startWrite()
            try {
                val output = DataOutputStream(stream)
                output.writeInt(0x56494431)
                output.writeLong(before.revision)
                output.writeInt(embedding.size)
                embedding.forEach(output::writeFloat)
                output.flush()
                profile.finishWrite(stream)
            } catch (error: Exception) {
                profile.failWrite(stream)
                throw error
            }
        }
        if (!settingsRepository.completeVoiceEnrollment(before.revision)) {
            withContext(Dispatchers.IO) { profile.delete() }
            return@withLock false
        }
        true
    }

    suspend fun verify(samples: FloatArray): Boolean = mutex.withLock {
        val before = settingsRepository.readAudioPreferences()
        if (!before.voiceIdEnabled) return@withLock true
        if (before.needsEnrollment) return@withLock false
        val stored = loadProfile(before.revision) ?: return@withLock false
        val incoming = speakerIdentifier.computeEmbedding(samples) ?: return@withLock false
        val after = settingsRepository.readAudioPreferences()
        after.revision == before.revision && !after.needsEnrollment &&
            speakerIdentifier.verify(stored, incoming)
    }

    private suspend fun loadProfile(revision: Long): FloatArray? = withContext(Dispatchers.IO) {
        try {
            profile.openRead().use { input ->
                val data = DataInputStream(input)
                if (data.readInt() != 0x56494431 || data.readLong() != revision) return@withContext null
                val size = data.readInt()
                if (size !in 16..4096) return@withContext null
                val result = FloatArray(size) { data.readFloat() }
                result.takeIf { data.read() == -1 && VoiceEmbedding.isValid(it) }
            }
        } catch (_: java.io.IOException) { null }
    }
}
