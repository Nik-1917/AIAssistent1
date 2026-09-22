package com.example.aiassistent1.data.provider

/** Never open two AudioRecords in this application at the same time. */
object MicrophoneCoordinator {
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any) {
        check(owner == null) { "Микрофон занят. Остановите голосовой ввод или конференцию." }
        owner = token
    }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
    suspend fun awaitAcquire(token: Any) {
        kotlinx.coroutines.withTimeout(10_000) {
            while (true) {
                val acquired = synchronized(this@MicrophoneCoordinator) {
                    if (owner == null) { owner = token; true } else false
                }
                if (acquired) return@withTimeout
                kotlinx.coroutines.delay(20)
            }
        }
    }
}
