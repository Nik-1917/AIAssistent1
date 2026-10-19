package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.MetricKwsDecision
import com.example.aiassistent1.domain.model.WakeWordEngine
import com.example.aiassistent1.domain.model.VoiceInputPolicy

sealed interface PersonalWakeResult {
    data class Legacy(val reason: String? = null) : PersonalWakeResult
    data class Unavailable(val reason: String) : PersonalWakeResult
    data object Rejected : PersonalWakeResult
    data object Activated : PersonalWakeResult
    data object Waiting : PersonalWakeResult
    data class Command(val text: String) : PersonalWakeResult
}

/** One serial recognition consumer. The wake utterance is never submitted to ASR/LLM. */
class PersonalKeywordWakeSession(
    private val preferences: suspend () -> AudioPreferences,
    private val evaluate: suspend (FloatArray) -> MetricKwsDecision,
    private val recognize: suspend (FloatArray) -> String,
    private val onActivation: suspend () -> Unit,
    private val onVerifiedSpeech: suspend () -> Unit = {},
    clock: () -> Long,
    private val commandWindow: ActivationCommandWindow = ActivationCommandWindow(clock),
) {
    private var identity: VoiceInputPolicy? = null

    suspend fun handle(samples: FloatArray, speechStartedAt: Long): PersonalWakeResult {
        val before = preferences()
        val key = before.inputPolicy()
        if (identity != key) { reset(); identity = key }
        if (before.wakeWordEngine != WakeWordEngine.METRIC_KWS || !before.wakeWordEnabled)
            return PersonalWakeResult.Legacy()
        val after = commandWindow.openedAt
        if (after != null) {
            // Reject queued utterances captured before the feedback ended, even if processed later.
            if (speechStartedAt < after) return PersonalWakeResult.Rejected
            if (commandWindow.accepts(speechStartedAt)) {
                if (preferences().inputPolicy() != key) return PersonalWakeResult.Rejected
                val text = recognize(samples)
                if (preferences().inputPolicy() != key) { reset(); return PersonalWakeResult.Rejected }
                if (!VoiceCommandAcceptance.hasSpeechText(text)) return PersonalWakeResult.Waiting
                onVerifiedSpeech()
                reset()
                return PersonalWakeResult.Command(text)
            }
            reset()
        }
        return when (val decision = evaluate(samples)) {
            MetricKwsDecision.Accept -> {
                if (preferences().inputPolicy() != key) return PersonalWakeResult.Rejected
                onVerifiedSpeech()
                onActivation()
                if (preferences().inputPolicy() != key) return PersonalWakeResult.Rejected
                commandWindow.open()
                PersonalWakeResult.Activated
            }
            is MetricKwsDecision.Unavailable -> { reset(); PersonalWakeResult.Unavailable(decision.reason) }
            is MetricKwsDecision.Legacy -> { reset(); PersonalWakeResult.Unavailable(decision.reason) }
            else -> PersonalWakeResult.Rejected
        }
    }

    private fun reset() = commandWindow.reset()

    companion object { const val COMMAND_WINDOW_MS = ActivationCommandWindow.COMMAND_WINDOW_MS }
}
