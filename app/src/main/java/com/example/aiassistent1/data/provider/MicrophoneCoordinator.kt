package com.example.aiassistent1.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Never open two AudioRecords in this application at the same time. */
object MicrophoneCoordinator {
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any) {
        check(owner == null) { "Микрофон занят. Остановите голосовой ввод или конференцию." }
        owner = token
    }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
    suspend fun awaitAcquire(token: Any) {
        try {
            val acquired = withTimeoutOrNull(10_000) {
                while (true) {
                    val acquired = synchronized(this@MicrophoneCoordinator) {
                        if (owner == null) { owner = token; true } else false
                    }
                    if (acquired) return@withTimeoutOrNull true
                    delay(20)
                }
            }
            if (acquired != true) {
                // Timeout can win after ownership was set, before the block returns.
                release(token)
                error("Микрофон занят. Остановите голосовой ввод или конференцию.")
            }
        } catch (cancelled: CancellationException) {
            release(token)
            throw cancelled
        }
    }
}
