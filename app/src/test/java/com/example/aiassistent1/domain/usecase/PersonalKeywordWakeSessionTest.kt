package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersonalKeywordWakeSessionTest {
    private var prefs = AudioPreferences(voiceIdEnabled = true, wakeWordEnabled = true,
        wakeWordEngine = WakeWordEngine.METRIC_KWS)
    private var now = 1000L
    private var decision: MetricKwsDecision = MetricKwsDecision.Accept
    private var owner = true
    private var keywordCalls = 0
    private var speakerCalls = 0
    private var asrCalls = 0
    private var sounds = 0
    private var text = "покажи заметки"
    private var duringSpeaker: () -> Unit = {}
    private var duringAsr: () -> Unit = {}
    private var duringFeedback: () -> Unit = {}
    private val audio = FloatArray(16000) { .1f }
    private fun session() = PersonalKeywordWakeSession(
        preferences = { prefs }, evaluate = { keywordCalls++; decision },
        verifySpeaker = { speakerCalls++; duringSpeaker(); owner },
        recognize = { asrCalls++; duringAsr(); text },
        onActivation = { sounds++; now += 100; duringFeedback() }, clock = { now },
    )

    @Test fun wakeIsConsumedAndNextCommandIsVerifiedSeparately() = runBlocking {
        val session = session()
        assertEquals(PersonalWakeResult.Activated, session.handle(audio, now))
        assertEquals(0, asrCalls)
        assertEquals(0, speakerCalls) // Keyword evaluator already applies its own owner gate.
        now += 200
        assertEquals(PersonalWakeResult.Command(text), session.handle(audio, now))
        assertEquals(1, speakerCalls)
        assertEquals(1, asrCalls)
        assertEquals(1, sounds)
        now += 200
        decision = MetricKwsDecision.Reject
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        assertEquals(2, keywordCalls)
        assertEquals(1, asrCalls)
    }

    @Test fun rejectedKeywordNeverFallsThroughToAsrOrFeedback() = runBlocking {
        decision = MetricKwsDecision.Reject
        assertEquals(PersonalWakeResult.Rejected, session().handle(audio, now))
        assertEquals(0, sounds); assertEquals(0, asrCalls); assertEquals(0, speakerCalls)
    }

    @Test fun foreignCommandCannotUseOwnersActivationWindow() = runBlocking {
        val session = session()
        session.handle(audio, now)
        now += 200; owner = false
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        assertEquals(0, asrCalls)
        owner = true
        assertEquals(PersonalWakeResult.Command(text), session.handle(audio, now))
    }

    @Test fun utteranceCapturedBeforeFeedbackIsNotACommand() = runBlocking {
        val session = session()
        val oldStart = now
        session.handle(audio, oldStart)
        now += 500
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, oldStart))
        assertEquals(0, asrCalls); assertEquals(0, speakerCalls)
    }

    @Test fun timeoutRequiresANewKeyword() = runBlocking {
        val session = session()
        session.handle(audio, now)
        now += PersonalKeywordWakeSession.COMMAND_WINDOW_MS
        decision = MetricKwsDecision.Reject
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        assertEquals(2, keywordCalls); assertEquals(0, asrCalls)
    }

    @Test fun revisionNameAndModeChangesInvalidateActivation() = runBlocking {
        for (change in listOf<(AudioPreferences) -> AudioPreferences>(
            { it.copy(revision = it.revision + 1) }, { it.copy(wakeWord = "Другое") },
            { it.copy(wakeWordEngine = WakeWordEngine.LEGACY_ASR) }, { it.copy(voiceIdEnabled = false) },
            { it.copy(needsEnrollment = true) }, { it.copy(wakeWordEnabled = false) })) {
            prefs = AudioPreferences(voiceIdEnabled = true, wakeWordEnabled = true, wakeWordEngine = WakeWordEngine.METRIC_KWS)
            decision = MetricKwsDecision.Accept
            val session = session()
            session.handle(audio, now)
            now += 200; prefs = change(prefs); decision = MetricKwsDecision.Reject
            val result = session.handle(audio, now)
            assertTrue(result is PersonalWakeResult.Legacy || result == PersonalWakeResult.Rejected)
            assertEquals(0, asrCalls)
        }
    }

    @Test fun modeChangeDuringSpeakerOrRecognitionCannotEmitCommand() = runBlocking {
        for (during in listOf("speaker", "asr")) {
            prefs = prefs.copy(wakeWordEngine = WakeWordEngine.METRIC_KWS)
            duringSpeaker = {}; duringAsr = {}
            val session = session()
            session.handle(audio, now); now += 200
            val change = { prefs = prefs.copy(wakeWordEngine = WakeWordEngine.LEGACY_ASR) }
            if (during == "speaker") duringSpeaker = change else duringAsr = change
            assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        }
    }

    @Test fun feedbackRaceDoesNotLeaveAnOpenWindow() = runBlocking {
        val session = session()
        duringFeedback = { prefs = prefs.copy(voiceIdEnabled = false) }
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        now += 200
        assertTrue(session.handle(audio, now) is PersonalWakeResult.Legacy)
        assertEquals(0, asrCalls)
    }

    @Test fun legacyAndShadowDoNotRunActiveModel() = runBlocking {
        for (mode in listOf(WakeWordEngine.LEGACY_ASR, WakeWordEngine.METRIC_KWS_SHADOW)) {
            prefs = prefs.copy(wakeWordEngine = mode)
            assertTrue(session().handle(audio, now) is PersonalWakeResult.Legacy)
        }
        assertEquals(0, keywordCalls); assertEquals(0, sounds); assertEquals(0, asrCalls)
    }

    @Test fun technicalFallbackIsDistinctFromAKeywordRejection() = runBlocking {
        decision = MetricKwsDecision.Legacy("model_unavailable")
        assertEquals(PersonalWakeResult.Legacy("model_unavailable"), session().handle(audio, now))
        assertEquals(0, asrCalls); assertEquals(0, sounds)
    }

    @Test fun emptyCommandCanBeRetriedAndCancellationPropagates() = runBlocking {
        val session = session()
        session.handle(audio, now); now += 200; text = " "
        assertEquals(PersonalWakeResult.Waiting, session.handle(audio, now))
        duringAsr = { throw CancellationException("stop") }
        try { session.handle(audio, now); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }
}
