package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.AudioPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Runs only for an accepted new message, inside its cancellable request job. */
internal class MessageSendFeedback(
    private val preferences: suspend () -> AudioPreferences,
    private val awaitInputStopped: suspend () -> Unit,
    private val playSound: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    suspend fun play() {
        try {
            // A slow recorder, settings read or player must not hold up the request indefinitely.
            withTimeoutOrNull(1_000) {
                if (!preferences().messageSentSoundEnabled) return@withTimeoutOrNull
                awaitInputStopped()
                currentCoroutineContext().ensureActive()
                if (preferences().messageSentSoundEnabled) playSound()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The message has already been accepted; optional feedback cannot reject it.
            onFailure(failure)
        }
    }
}
