package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.interfaces.VoiceInputSessionChangedException
import kotlinx.coroutines.CompletableDeferred

/** Readiness belongs to one capture lifetime; errors and replacement wake every waiter. */
internal class VoiceCaptureReadiness {
    private class Session(val id: Long) {
        val ready = CompletableDeferred<Unit>()
        var ended: Throwable? = null
    }
    private var current: Session? = null

    @Synchronized fun begin(id: Long) {
        current?.let { end(it.id) }
        current = Session(id)
    }

    @Synchronized fun ready(id: Long): Boolean {
        val session = current?.takeIf { it.id == id && it.ended == null } ?: return false
        return session.ready.complete(Unit)
    }

    @Synchronized fun end(id: Long, cause: Throwable = VoiceInputSessionChangedException()) {
        current?.takeIf { it.id == id && it.ended == null }?.let {
            it.ended = cause
            it.ready.completeExceptionally(cause)
        }
    }

    suspend fun await(id: Long) {
        val session = synchronized(this) {
            current?.takeIf { it.id == id } ?: throw VoiceInputSessionChangedException()
        }
        session.ready.await()
        synchronized(this) {
            session.ended?.let { throw it }
            if (current !== session) throw VoiceInputSessionChangedException()
        }
    }
}
