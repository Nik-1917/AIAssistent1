package com.example.aiassistent1.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Owned by the serial recognition consumer; VAD start/end events arrive through its channel. */
class ActivationCommandWindow(private val clock: () -> Long) {
    var openedAt: Long? = null
        private set
    private var deadline = 0L
    private var activeSpeech: Long? = null

    fun open() {
        openedAt = clock()
        deadline = openedAt!! + COMMAND_WINDOW_MS
        activeSpeech = null
    }

    fun reset() {
        openedAt = null
        deadline = 0L
        activeSpeech = null
    }

    /** A command may finish or reach ASR after the deadline if it started in time. */
    fun accepts(speechStartedAt: Long): Boolean = openedAt?.let {
        speechStartedAt >= it && speechStartedAt < deadline
    } ?: false

    fun speechStarted(at: Long) {
        if (accepts(at)) activeSpeech = at
    }

    fun speechFinished(at: Long) {
        if (activeSpeech == at) activeSpeech = null
    }

    fun expireIfSilent(): Boolean {
        if (openedAt == null || activeSpeech != null || clock() < deadline) return false
        reset()
        return true
    }

    /** Null means the timer fired; a closed channel remains distinguishable from silence. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> receive(channel: ReceiveChannel<T>): ChannelResult<T>? {
        // Preserve capture order, including a timely VAD start queued while ASR was busy.
        val queued = channel.tryReceive()
        if (queued.isSuccess || queued.isClosed) return queued
        if (openedAt == null || activeSpeech != null) return channel.receiveCatching()
        val remaining = (deadline - clock()).coerceAtLeast(0)
        // Select the event and timer atomically: timing out a suspended receive can otherwise
        // discard an already-delivered VAD start before its continuation gets dispatched.
        return select<ChannelResult<T>?> {
            channel.onReceiveCatching { it }
            onTimeout(remaining) { null }
        } ?: channel.tryReceive().takeIf { it.isSuccess || it.isClosed }
    }

    companion object { const val COMMAND_WINDOW_MS = 5_000L }
}
