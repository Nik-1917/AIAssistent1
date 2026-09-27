package com.example.aiassistent1.data.provider

import android.content.Context
import android.util.AtomicFile
import com.example.aiassistent1.domain.interfaces.SettingsRepository
import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
import com.example.aiassistent1.domain.model.VoiceEmbedding
import com.example.aiassistent1.domain.interfaces.VoiceProfileStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayOutputStream

class VoiceProfileManager(context: Context, private val speakerIdentifier: SpeakerIdentifier,
    private val settingsRepository: SettingsRepository) {
    private val profile = AtomicFile(File(context.noBackupFilesDir, "voice_profile.bin"))
    private val mutex = Mutex()

    suspend fun needsEnrollment(): Boolean = mutex.withLock {
        val p = settingsRepository.readAudioPreferences()
        p.voiceIdEnabled && (p.needsEnrollment || loadProfile(p.revision) == null)
    }

    suspend fun status(): VoiceProfileStatus = mutex.withLock {
        val prefs = settingsRepository.readAudioPreferences()
        val ready = !prefs.needsEnrollment && loadProfile(prefs.revision) != null
        VoiceProfileStatus(ready, if (ready) "Профиль голоса сохранён" else "Запишите голос для Voice ID")
    }

    suspend fun delete() = mutex.withLock {
        settingsRepository.setVoiceIdNeedsEnrollment(true)
        withContext(Dispatchers.IO) { profile.delete() }
    }

    suspend fun enroll(samples: FloatArray): Boolean = mutex.withLock {
        val before = settingsRepository.readAudioPreferences()
        if (samples.size !in 8_000..128_000 || samples.any { !it.isFinite() || kotlin.math.abs(it) > 1f } ||
            samples.sumOf { it.toDouble() * it } / samples.size < 0.00001) return@withLock false
        val embedding = speakerIdentifier.computeEmbedding(samples) ?: return@withLock false
        if (!VoiceEmbedding.isValid(embedding)) return@withLock false
        if (settingsRepository.readAudioPreferences().revision != before.revision) return@withLock false
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x56494431)
                output.writeLong(before.revision)
                output.writeInt(embedding.size)
                embedding.forEach(output::writeFloat)
            }
        }.toByteArray()
        currentCoroutineContext().ensureActive()
        // Commit file + preference state as one non-cancellable operation. A failed commit restores the old file.
        withContext(Dispatchers.IO + NonCancellable) {
            val previous = try { profile.openRead().use {
                check(it.channel.size() <= 16_400) { "Повреждён профиль голоса. Удалите его перед повторной записью." }
                it.readBytes()
            } } catch (_: java.io.FileNotFoundException) { null }
            try {
                write(bytes)
                if (settingsRepository.completeVoiceEnrollment(before.revision)) true
                else { restore(previous); false }
            } catch (error: Exception) {
                try { restore(previous) } catch (restoreError: Exception) { error.addSuppressed(restoreError) }
                throw error
            }
        }
    }

    private fun write(bytes: ByteArray) {
        val stream = profile.startWrite()
        try { stream.write(bytes); profile.finishWrite(stream) }
        catch (error: Exception) { profile.failWrite(stream); throw error }
    }
    private fun restore(bytes: ByteArray?) { if (bytes == null) profile.delete() else write(bytes) }

    suspend fun verify(samples: FloatArray): Boolean = mutex.withLock {
        val before = settingsRepository.readAudioPreferences()
        if (!before.voiceIdEnabled) return@withLock true
        if (before.needsEnrollment) return@withLock false
        val stored = loadProfile(before.revision) ?: return@withLock false
        val incoming = speakerIdentifier.computeEmbedding(samples) ?: return@withLock false
        val after = settingsRepository.readAudioPreferences()
        after.voiceIdEnabled && after.inputPolicy() == before.inputPolicy() && !after.needsEnrollment &&
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
