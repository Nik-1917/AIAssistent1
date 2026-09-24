package com.example.aiassistent1.data.provider

import android.content.Context
import android.util.AtomicFile
import com.example.aiassistent1.domain.interfaces.MetricKwsProfileStore
import com.example.aiassistent1.domain.model.MetricKwsProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MetricKwsProfileManager(context: Context) : MetricKwsProfileStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "metric_kws_profile.bin"))
    private val mutex = Mutex()

    override suspend fun load(): MetricKwsProfile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                file.openRead().use { stream ->
                    // Limit allocations even for a corrupt/hostile file, including a restored backup.
                    val buffer = ByteArray(MetricKwsProfileCodec.MAX_BYTES + 1)
                    var size = 0
                    while (size < buffer.size) {
                        val count = stream.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    MetricKwsProfileCodec.decode(buffer.copyOf(size))
                }
            } catch (_: java.io.IOException) { null }
        }
    }

    override suspend fun save(profile: MetricKwsProfile) = mutex.withLock {
        val bytes = MetricKwsProfileCodec.encode(profile)
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                stream.flush()
                currentCoroutineContext().ensureActive()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    override suspend fun delete() = mutex.withLock { withContext(Dispatchers.IO) { file.delete() } }
}
