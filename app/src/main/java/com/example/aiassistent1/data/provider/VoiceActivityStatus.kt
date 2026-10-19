package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.model.AudioSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A rejection briefly overlays the current capture status without pausing recognition. */
internal class VoiceActivityStatus(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(AudioSessionState.Idle)
    val state = mutableState.asStateFlow()
    private var sessionId: Long? = null
    private var rejectionToken: Any? = null
    private var resetJob: Job? = null

    @Synchronized fun begin(id: Long) {
        cancelRejection()
        sessionId = id
        mutableState.value = AudioSessionState.Idle
    }

    @Synchronized fun update(id: Long, next: AudioSessionState) {
        if (sessionId != id) return
        if (next != AudioSessionState.Rejected) {
            cancelRejection()
            mutableState.value = next
            return
        }
        // Further rejected sounds must not extend the first rejection's deadline.
        if (mutableState.value == AudioSessionState.Rejected) return
        val previous = mutableState.value
        val token = Any()
        rejectionToken = token
        mutableState.value = AudioSessionState.Rejected
        // Start counting immediately, even when recognition keeps the dispatcher busy.
        resetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(REJECTION_DISPLAY_MS)
            synchronized(this@VoiceActivityStatus) {
                // Cancellation alone cannot guard a timer that has already passed delay().
                if (sessionId == id && rejectionToken === token) {
                    rejectionToken = null
                    resetJob = null
                    mutableState.value = previous
                }
            }
        }
    }

    @Synchronized fun end(id: Long) {
        if (sessionId != id) return
        cancelRejection()
        sessionId = null
        mutableState.value = AudioSessionState.Idle
    }

    private fun cancelRejection() {
        rejectionToken = null
        resetJob?.cancel()
        resetJob = null
    }

    private companion object {
        const val REJECTION_DISPLAY_MS = 1_000L
    }
}
