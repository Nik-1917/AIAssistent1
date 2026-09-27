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
    private val verifySpeaker: suspend (FloatArray) -> Boolean,
    private val recognize: suspend (FloatArray) -> String,
    private val onActivation: suspend () -> Unit,
    private val onVerifiedSpeech: suspend () -> Unit = {},
    private val clock: () -> Long,
    private val speakerReady: suspend () -> Boolean = { true },
) {
    private var identity: VoiceInputPolicy? = null
    private var commandAfter: Long? = null
    private var commandUntil = 0L

    suspend fun handle(samples: FloatArray, speechStartedAt: Long): PersonalWakeResult {
        val before = preferences()
        val key = before.inputPolicy()
        if (identity != key) { reset(); identity = key }
        if (before.wakeWordEngine != WakeWordEngine.METRIC_KWS || !before.wakeWordEnabled)
            return PersonalWakeResult.Legacy()
        if (before.voiceIdEnabled && (before.needsEnrollment || !speakerReady())) {
            reset()
            return PersonalWakeResult.Unavailable("voice_id_required")
        }
        val after = commandAfter
        if (after != null) {
            // Reject queued utterances captured before the feedback ended, even if processed later.
            if (speechStartedAt < after) return PersonalWakeResult.Rejected
            if (clock() < commandUntil) {
                if (before.voiceIdEnabled && !verifySpeaker(samples)) return PersonalWakeResult.Rejected
                if (preferences().inputPolicy() != key) return PersonalWakeResult.Rejected
                onVerifiedSpeech()
                val text = recognize(samples)
                if (preferences().inputPolicy() != key) { reset(); return PersonalWakeResult.Rejected }
                if (text.isBlank()) return PersonalWakeResult.Waiting
                reset()
                return PersonalWakeResult.Command(text)
            }
            reset()
        }
        return when (val decision = evaluate(samples)) {
            MetricKwsDecision.Accept -> {
                if (before.voiceIdEnabled && !verifySpeaker(samples)) return PersonalWakeResult.Rejected
                if (preferences().inputPolicy() != key) return PersonalWakeResult.Rejected
                onVerifiedSpeech()
                onActivation()
                if (preferences().inputPolicy() != key) return PersonalWakeResult.Rejected
                commandAfter = clock()
                commandUntil = commandAfter!! + COMMAND_WINDOW_MS
                PersonalWakeResult.Activated
            }
            is MetricKwsDecision.Unavailable -> { reset(); PersonalWakeResult.Unavailable(decision.reason) }
            is MetricKwsDecision.Legacy -> { reset(); PersonalWakeResult.Unavailable(decision.reason) }
            else -> PersonalWakeResult.Rejected
        }
    }

    private fun reset() { commandAfter = null; commandUntil = 0L }

    companion object { const val COMMAND_WINDOW_MS = 10_000L }
}
