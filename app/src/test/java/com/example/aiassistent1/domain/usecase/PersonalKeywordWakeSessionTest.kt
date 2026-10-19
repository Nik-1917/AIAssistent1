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
    private var keywordCalls = 0
    private var asrCalls = 0
    private var sounds = 0
    private var interruptions = 0
    private var text = "покажи заметки"
    private var duringAsr: () -> Unit = {}
    private var duringFeedback: () -> Unit = {}
    private val audio = FloatArray(16000) { .1f }
    private fun session(window: ActivationCommandWindow = ActivationCommandWindow { now }) = PersonalKeywordWakeSession(
        preferences = { prefs }, evaluate = { keywordCalls++; decision },
        recognize = { asrCalls++; duringAsr(); text },
        onActivation = { sounds++; now += 100; duringFeedback() },
        onVerifiedSpeech = { interruptions++ }, clock = { now },
        commandWindow = window,
    )

    @Test fun wakeIsConsumedAndOnlyNextCommandGoesToAsr() = runBlocking {
        val session = session()
        assertEquals(PersonalWakeResult.Activated, session.handle(audio, now))
        assertEquals(0, asrCalls)
        now += 200
        assertEquals(PersonalWakeResult.Command(text), session.handle(audio, now))
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
        assertEquals(0, sounds); assertEquals(0, asrCalls)
        assertEquals(0, interruptions)
    }

    @Test fun emptyCommandCannotInterruptPlayback() = runBlocking {
        val session = session()
        session.handle(audio, now)
        val afterActivation = interruptions
        now += 200
        for (empty in listOf("", "...", "   ")) {
            text = empty
            assertEquals(PersonalWakeResult.Waiting, session.handle(audio, now))
            assertEquals(afterActivation, interruptions)
        }
        text = "покажи заметки"
        assertEquals(PersonalWakeResult.Command(text), session.handle(audio, now))
        assertEquals(afterActivation + 1, interruptions)
    }

    @Test fun retiredOwnerFlagsDoNotCloseAnOpenCommandWindow() = runBlocking {
        val session = session()
        session.handle(audio, now)
        now += 200
        prefs = prefs.copy(voiceIdEnabled = false, needsEnrollment = true, revision = 99)
        assertEquals(PersonalWakeResult.Command(text), session.handle(audio, now))
    }

    @Test fun utteranceCapturedBeforeFeedbackIsNotACommand() = runBlocking {
        val session = session()
        val oldStart = now
        session.handle(audio, oldStart)
        now += 500
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, oldStart))
        assertEquals(0, asrCalls)
    }

    @Test fun timeoutRequiresANewKeyword() = runBlocking {
        val session = session()
        session.handle(audio, now)
        now += PersonalKeywordWakeSession.COMMAND_WINDOW_MS
        decision = MetricKwsDecision.Reject
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        assertEquals(2, keywordCalls); assertEquals(0, asrCalls)
    }

    @Test fun commandStartedInTimeSurvivesLongSpeechAndSlowRecognition() = runBlocking {
        val session = session()
        session.handle(audio, now)
        val commandStart = now + 4_999
        now += 8_000
        duringAsr = { now += 3_000 }
        assertEquals(PersonalWakeResult.Command(text), session.handle(audio, commandStart))
        assertEquals(1, asrCalls)
        assertEquals(1, sounds)
    }

    @Test fun silenceTimerClosesPersonalWindowAndRequiresAnotherKeyword() = runBlocking {
        val window = ActivationCommandWindow { now }
        val session = session(window)
        session.handle(audio, now)
        now += 5_000
        assertTrue(window.expireIfSilent())
        decision = MetricKwsDecision.Reject
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        assertEquals(0, asrCalls)
        decision = MetricKwsDecision.Accept
        assertEquals(PersonalWakeResult.Activated, session.handle(audio, now))
        assertEquals(2, sounds)
        now += 200
        assertEquals(PersonalWakeResult.Command(text), session.handle(audio, now))
    }

    @Test fun emptyTimelyCommandCanReturnToNameWaitingAfterDeadline() = runBlocking {
        val window = ActivationCommandWindow { now }
        val session = session(window)
        session.handle(audio, now)
        val start = now + 4_000
        window.speechStarted(start)
        now += 6_000
        text = "..."
        assertEquals(PersonalWakeResult.Waiting, session.handle(audio, start))
        window.speechFinished(start)
        assertTrue(window.expireIfSilent())
        assertEquals(1, sounds)
    }

    @Test fun policyAndModeChangesInvalidateActivation() = runBlocking {
        for (change in listOf<(AudioPreferences) -> AudioPreferences>(
            { it.copy(policyRevision = it.policyRevision + 1) },
            { it.copy(wakeWordEngine = WakeWordEngine.LEGACY_ASR) }, { it.copy(wakeWordEnabled = false) })) {
            prefs = AudioPreferences(voiceIdEnabled = true, wakeWordEnabled = true, wakeWordEngine = WakeWordEngine.METRIC_KWS)
            decision = MetricKwsDecision.Accept
            val session = session()
            session.handle(audio, now)
            now += 200; prefs = change(prefs); decision = MetricKwsDecision.Reject
            val result = session.handle(audio, now)
            assertTrue(result is PersonalWakeResult.Legacy || result is PersonalWakeResult.Unavailable || result == PersonalWakeResult.Rejected)
            assertEquals(0, asrCalls)
        }
    }

    @Test fun modeChangeDuringRecognitionCannotEmitCommand() = runBlocking {
        val session = session()
        session.handle(audio, now); now += 200
        duringAsr = { prefs = prefs.copy(wakeWordEngine = WakeWordEngine.LEGACY_ASR) }
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
    }

    @Test fun feedbackRaceDoesNotLeaveAnOpenWindow() = runBlocking {
        val session = session()
        duringFeedback = { prefs = prefs.copy(policyRevision = prefs.policyRevision + 1) }
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        now += 200
        decision = MetricKwsDecision.Reject
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
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
        decision = MetricKwsDecision.Unavailable("model_unavailable")
        assertEquals(PersonalWakeResult.Unavailable("model_unavailable"), session().handle(audio, now))
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

    @Test fun oldEnabledOwnerWithoutProfileDoesNotBlockWordOrCommand() = runBlocking {
        prefs = prefs.copy(voiceIdEnabled = true, needsEnrollment = true)
        val session = PersonalKeywordWakeSession({ prefs }, { decision }, { "команда" }, {}, clock = { now })
        assertEquals(PersonalWakeResult.Activated, session.handle(audio, now))
        now += 100
        assertEquals(PersonalWakeResult.Command("команда"), session.handle(audio, now))
    }

    @Test fun legacyOwnerFlagsNeverOverrideKeywordDecision() = runBlocking {
        for (voiceId in listOf(false, true)) for (keyword in listOf(false, true)) for (missing in listOf(false, true)) {
            prefs = prefs.copy(voiceIdEnabled = voiceId, needsEnrollment = missing)
            decision = if (keyword) MetricKwsDecision.Accept else MetricKwsDecision.Reject
            sounds = 0
            val result = session().handle(audio, now)
            assertEquals(if (keyword) PersonalWakeResult.Activated else PersonalWakeResult.Rejected, result)
            assertEquals(if (result == PersonalWakeResult.Activated) 1 else 0, sounds)
        }
    }

    @Test fun wordProfileChangesCloseAlreadyOpenedWindow() = runBlocking {
        val session = session()
        session.handle(audio, now)
        now += 100
        prefs = prefs.copy(policyRevision = prefs.policyRevision + 1)
        decision = MetricKwsDecision.Reject
        assertEquals(PersonalWakeResult.Rejected, session.handle(audio, now))
        assertEquals(0, asrCalls)
    }
}
