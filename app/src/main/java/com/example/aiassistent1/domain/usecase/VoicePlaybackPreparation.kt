package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.VoiceInputSessionChangedException
import com.example.aiassistent1.domain.model.AudioPreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Recheck live policy after every asynchronous capture transition, before allowing sound. */
internal class VoicePlaybackPreparation(
    private val preferences: suspend () -> AudioPreferences,
    private val activationListening: () -> Boolean,
    private val pauseCapture: suspend () -> Unit,
    private val requestCapture: suspend () -> Long,
    private val awaitCapture: suspend (Long) -> Unit,
    private val isCurrentCapture: (Long) -> Boolean,
    private val ensureConversation: () -> Unit,
) {
    suspend fun await() {
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureConversation()
            val before = preferences()
            if (!activationListening() || !before.voiceInterruptionEnabled) {
                pauseCapture()
                currentCoroutineContext().ensureActive()
                ensureConversation()
                if (activationListening() && preferences().voiceInterruptionEnabled) continue
                return
            }
            val sessionId = try {
                requestCapture().also { awaitCapture(it) }
            } catch (_: VoiceInputSessionChangedException) {
                yield()
                continue
            }
            currentCoroutineContext().ensureActive()
            ensureConversation()
            if (activationListening() && preferences().inputPolicy() == before.inputPolicy() &&
                isCurrentCapture(sessionId)) return
        }
    }
}
