package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.data.provider.UnavailableMetricKwsEngine
import com.example.aiassistent1.domain.interfaces.*
import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PersonalKeywordActivationTest {
    private val config = MetricKwsConfig("test", "a".repeat(64), "test-dsp", 2, .8f)
    private val audio = FloatArray(16000) { if (it % 2 == 0) .1f else -.1f }
    private var prefs = AudioPreferences(voiceIdEnabled = true, revision = 3,
        wakeWordEngine = WakeWordEngine.METRIC_KWS)
    private var speaker = true
    private var speakerCalls = 0
    private val store = Store()
    private val engine = Engine()

    private inner class Engine : MetricKwsEngine {
        override var config: MetricKwsConfig? = this@PersonalKeywordActivationTest.config
        override val unavailableReason: String? = null
        override var activationValidated = true
        var calls = 0
        var closes = 0
        var value = floatArrayOf(1f, 0f)
        var fail: Exception? = null
        var action: suspend () -> Unit = {}
        override suspend fun embedding(samples: FloatArray): FloatArray {
            calls++
            action()
            fail?.let { throw it }
            return value
        }
        override suspend fun close() { closes++ }
    }

    private inner class Store : MetricKwsProfileStore {
        var current: MetricKwsProfile? = MetricKwsProfile(config, 3, 1, floatArrayOf(1f, 0f))
        var writes = 0
        var fail = false
        override suspend fun load() = current
        override suspend fun save(profile: MetricKwsProfile) {
            if (fail) throw java.io.IOException("disk full")
            writes++
            current = profile
        }
        override suspend fun delete() { current = null }
    }

    private fun service() = PersonalKeywordActivation(engine, store, { prefs }, { speakerCalls++; speaker }, { 100L })

    @Test fun dualGateTruthTableWithIndependentVetoes() = runBlocking {
        val service = service()
        for (keyword in listOf(false, true)) for (owner in listOf(false, true)) {
            engine.value = if (keyword) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
            speaker = owner
            speakerCalls = 0
            val result = service.evaluate(audio, WakeWordEngine.METRIC_KWS)
            assertEquals(if (keyword && owner) MetricKwsDecision.Accept else MetricKwsDecision.Reject, result)
            assertEquals(if (keyword) 1 else 0, speakerCalls)
        }
    }

    @Test fun shadowHasNoSpeakerOrEnrollmentSideEffects() = runBlocking {
        val service = service()
        val result = service.evaluate(audio, WakeWordEngine.METRIC_KWS_SHADOW)
        assertTrue(result is MetricKwsDecision.Shadow)
        assertEquals(result, service.lastDiagnostic.value)
        assertEquals(0, speakerCalls)
        assertEquals(0, store.writes)
    }

    @Test fun legacyDoesNotRunEncoderOrSpeaker() = runBlocking {
        assertTrue(service().evaluate(audio, WakeWordEngine.LEGACY_ASR) is MetricKwsDecision.Legacy)
        assertEquals(0, engine.calls)
        assertEquals(0, speakerCalls)
    }

    @Test fun unvalidatedEngineNeverActivates() = runBlocking {
        engine.activationValidated = false
        assertEquals(MetricKwsDecision.Legacy("validation_required"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertEquals(0, engine.calls)
    }

    @Test fun missingProfileModelAndChangedIdentityFallBack() = runBlocking {
        val service = service()
        store.current = null
        assertTrue(service.evaluate(audio, WakeWordEngine.METRIC_KWS) is MetricKwsDecision.Legacy)
        store.current = MetricKwsProfile(config.copy(modelSha256 = "b".repeat(64)), 3, 1, floatArrayOf(1f, 0f))
        assertEquals(MetricKwsDecision.Legacy("reenrollment_required"), service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        store.current = MetricKwsProfile(config.copy(featureVersion = "changed"), 3, 1, floatArrayOf(1f, 0f))
        assertEquals(MetricKwsDecision.Legacy("reenrollment_required"), service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        engine.config = null
        assertEquals(MetricKwsDecision.Legacy("model_unavailable"), service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertEquals(0, engine.calls)
    }

    @Test fun disabledOrUnenrolledVoiceIdCannotPass() = runBlocking {
        for (state in listOf(prefs.copy(voiceIdEnabled = false), prefs.copy(needsEnrollment = true))) {
            prefs = state
            assertEquals(MetricKwsDecision.Legacy("voice_id_required"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        }
        assertEquals(0, speakerCalls)
    }

    @Test fun settingsRaceRejectsAfterSpeakerCheck() = runBlocking {
        val service = PersonalKeywordActivation(engine, store, { prefs }, {
            prefs = prefs.copy(voiceIdEnabled = false)
            true
        })
        assertEquals(MetricKwsDecision.Reject, service.evaluate(audio, WakeWordEngine.METRIC_KWS))
    }

    @Test fun exceptionsFallbackButCancellationPropagates() = runBlocking {
        engine.fail = IllegalStateException("native inference failed")
        assertEquals(MetricKwsDecision.Legacy("runtime_error"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        engine.fail = CancellationException("stop")
        try {
            service().evaluate(audio, WakeWordEngine.METRIC_KWS)
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun invalidOutputsCannotActivate() = runBlocking {
        for (bad in listOf(floatArrayOf(Float.NaN, 1f), floatArrayOf(0f, 0f), floatArrayOf(1f))) {
            engine.value = bad
            assertEquals(MetricKwsDecision.Legacy("invalid_embedding"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        }
        assertEquals(0, speakerCalls)
    }

    @Test fun oneShotCapturesOnceVerifiesSameAudioAndWipesPcm() = runBlocking {
        var captures = 0
        val captured = audio.copyOf()
        val service = PersonalKeywordActivation(engine, store, { prefs }, { assertSame(captured, it); true }, { 100 })
        service.enrollFrom { captures++; captured }
        assertEquals(1, captures)
        assertEquals(1, engine.calls)
        assertEquals(1, store.writes)
        assertEquals(3L, store.current!!.voiceRevision)
        assertTrue(captured.all { it == 0f })
    }

    @Test fun failedRepeatEnrollmentPreservesPreviousProfileAndWipesAudio() = runBlocking {
        val old = store.current
        speaker = false
        val captured = audio.copyOf()
        assertTrue(runCatching { service().enrollFrom { captured } }.isFailure)
        assertSame(old, store.current)
        assertEquals(0, store.writes)
        assertTrue(captured.all { it == 0f })
        speaker = true
        service().enroll(audio)
        assertNotSame(old, store.current)
        assertEquals(1, store.writes)
    }

    @Test fun saveFailurePreservesPreviousProfile() = runBlocking {
        val old = store.current
        store.fail = true
        assertTrue(runCatching { service().enroll(audio) }.isFailure)
        assertSame(old, store.current)
    }

    @Test fun cancellationDuringEnrollmentPreservesProfileAndWipesAudio() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        engine.action = { entered.complete(Unit); awaitCancellation() }
        val old = store.current
        val captured = audio.copyOf()
        val service = service()
        val job = launch { service.enrollFrom { captured } }
        entered.await()
        job.cancelAndJoin()
        assertSame(old, store.current)
        assertTrue(captured.all { it == 0f })
        service.close()
        assertEquals(1, engine.closes)
    }

    @Test fun closeIsSerializedAndIdempotent() = runBlocking {
        val service = service()
        service.close()
        service.close()
        assertEquals(1, engine.closes)
        assertEquals(MetricKwsDecision.Legacy("closed"), service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertTrue(runCatching { service.enroll(audio) }.isFailure)
    }

    @Test fun productionGateNeverRecordsOrRunsInference() = runBlocking {
        val service = PersonalKeywordActivation(UnavailableMetricKwsEngine(), store, { prefs }, { true })
        var captures = 0
        assertFalse(service.available)
        assertFalse(service.activationValidated)
        assertTrue(runCatching { service.enrollFrom { captures++; audio } }.isFailure)
        assertEquals(0, captures)
        for (mode in WakeWordEngine.entries)
            assertTrue(service.evaluate(audio, mode) is MetricKwsDecision.Legacy)
    }

    @Test fun deletionDoesNotModifySpeakerPreferences() = runBlocking {
        val before = prefs
        val service = PersonalKeywordActivation(engine, store, { prefs }, { true }, selectLegacy = {
            prefs = prefs.copy(wakeWordEngine = WakeWordEngine.LEGACY_ASR)
        })
        service.delete()
        assertNull(store.current)
        assertEquals(WakeWordEngine.LEGACY_ASR, prefs.wakeWordEngine)
        assertEquals(before, prefs.copy(wakeWordEngine = before.wakeWordEngine))
    }
}
