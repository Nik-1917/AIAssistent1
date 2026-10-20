package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** All working-text writes and transfers to the library share one ordered queue. */
internal class VoiceDraftPersistence(
    private val repository: VoiceDraftRepository,
    scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var revision = 0L
    var restoreFailed: Boolean = false
        private set

    init {
        scope.launch {
            for (write in writes) write()
        }
    }

    suspend fun restore(): String? {
        restoreFailed = false
        // A deletion also wins if it happens before the restore coroutine starts.
        if (revision != 0L) return null
        return try {
            repository.loadDraft().takeIf { revision == 0L }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            restoreFailed = true
            if (revision == 0L) onError("Не удалось восстановить голосовой черновик. Повторите попытку.")
            null
        }
    }

    fun save(text: String) {
        write(text)
    }

    fun clear() {
        write(null)
    }

    suspend fun saveAndAwait(text: String): Boolean = write(text).await()

    suspend fun clearAndAwait(): Boolean = write(null).await()

    private fun write(text: String?): CompletableDeferred<Boolean> {
        val requestedRevision = ++revision
        val result = CompletableDeferred<Boolean>()
        val queued = writes.trySend {
            try {
                if (text == null) repository.clearDraft() else repository.saveDraft(text)
                result.complete(requestedRevision == revision)
            } catch (cancelled: CancellationException) {
                result.cancel(cancelled)
                throw cancelled
            } catch (_: Exception) {
                if (requestedRevision == revision) {
                    onError(if (text == null) "Не удалось удалить голосовой черновик"
                        else "Не удалось сохранить голосовой черновик")
                }
                result.complete(false)
            }
        }
        if (queued.isFailure) result.complete(false)
        return result
    }

    suspend fun <T> ordered(block: suspend () -> T): T {
        ++revision
        val result = CompletableDeferred<T>()
        writes.send {
            try {
                result.complete(block())
            } catch (cancelled: CancellationException) {
                result.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }
        return result.await()
    }
}
