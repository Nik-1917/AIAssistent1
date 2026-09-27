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
    private val store = Store()
    private val engine = Engine()
    private var changes = 0
    private inner class Engine : MetricKwsEngine {
        override var config: MetricKwsConfig? = this@PersonalKeywordActivationTest.config
        override val unavailableReason: String? = null
        override var activationValidated = true
        override var experimentalActivationAllowed = false
        var prepareError: Exception? = null
        var preparations = 0
        override suspend fun prepare() { preparations++; prepareError?.let { throw it } }
        var calls = 0
        var closes = 0
        var value = floatArrayOf(1f, 0f)
        var fail: Exception? = null
        var action: suspend () -> Unit = {}
        override suspend fun embedding(samples: FloatArray): FloatArray {
            calls++; action(); fail?.let { throw it }; return value
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
            writes++; current = profile
        }
        override suspend fun delete() { current = null }
    }
    private fun service() = PersonalKeywordActivation(engine, store, { 100L }, { changes++ })

    @Test fun wordGateHasNoDependencyOnSpeakerSettingsOrVerifier() = runBlocking {
        val service = service()
        assertTrue(service.status().canActivate)
        assertEquals(MetricKwsDecision.Accept, service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        engine.value = floatArrayOf(0f, 1f)
        assertEquals(MetricKwsDecision.Reject, service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertEquals(0, store.writes)
    }
    @Test fun shadowOnlyReportsScores() = runBlocking {
        val service = service()
        val result = service.evaluate(audio, WakeWordEngine.METRIC_KWS_SHADOW)
        assertTrue(result is MetricKwsDecision.Shadow)
        assertEquals(result, service.lastDiagnostic.value)
        assertEquals(0, store.writes); assertEquals(0, changes)
    }
    @Test fun legacyDoesNotRunEncoder() = runBlocking {
        assertTrue(service().evaluate(audio, WakeWordEngine.LEGACY_ASR) is MetricKwsDecision.Legacy)
        assertEquals(0, engine.calls)
    }
    @Test fun unvalidatedEngineWithoutOptInPermissionCannotActivate() = runBlocking {
        engine.activationValidated = false
        assertEquals(MetricKwsDecision.Unavailable("validation_required"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertEquals(0, engine.calls)
    }
    @Test fun explicitExperimentalOptInDoesNotClaimValidation() = runBlocking {
        engine.activationValidated = false; engine.experimentalActivationAllowed = true
        val service = service()
        assertFalse(service.activationValidated); assertTrue(service.activationAllowed)
        assertTrue(service.status().canActivate)
        assertEquals(MetricKwsDecision.Accept, service.evaluate(audio, WakeWordEngine.METRIC_KWS))
    }
    @Test fun inactiveSettingsDoNotPrepareEncoder() = runBlocking {
        assertTrue(service().status(prepareModel = false).canActivate)
        assertEquals(0, engine.preparations); assertEquals(0, engine.calls)
    }
    @Test fun loadFailurePreventsRecordingAndPreservesProfile() = runBlocking {
        val previous = store.current
        engine.prepareError = IllegalStateException("model missing")
        var captured = false
        assertFalse(service().status().canActivate)
        assertTrue(runCatching { service().enrollFrom { captured = true; audio } }.isFailure)
        assertFalse(captured); assertSame(previous, store.current)
    }
    @Test fun missingProfileAndIncompatibleModelsRemainUnavailableWithoutFallback() = runBlocking {
        store.current = null
        assertEquals(MetricKwsDecision.Unavailable("profile_missing_or_corrupt"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        for (changed in listOf(config.copy(modelSha256 = "b".repeat(64)), config.copy(featureVersion = "changed"))) {
            store.current = MetricKwsProfile(changed, 3, 1, floatArrayOf(1f, 0f))
            assertEquals(MetricKwsDecision.Unavailable("reenrollment_required"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        }
        engine.config = null
        assertEquals(MetricKwsDecision.Unavailable("model_unavailable"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertEquals(0, engine.calls)
    }
    @Test fun exceptionsBlockActivationButCancellationPropagates() = runBlocking {
        engine.fail = IllegalStateException("native inference failed")
        assertEquals(MetricKwsDecision.Unavailable("runtime_error"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        engine.fail = CancellationException("stop")
        try { service().evaluate(audio, WakeWordEngine.METRIC_KWS); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }
    @Test fun invalidOutputsCannotActivate() = runBlocking {
        for (bad in listOf(floatArrayOf(Float.NaN, 1f), floatArrayOf(0f, 0f), floatArrayOf(1f))) {
            engine.value = bad
            assertEquals(MetricKwsDecision.Unavailable("invalid_embedding"), service().evaluate(audio, WakeWordEngine.METRIC_KWS))
        }
    }
    @Test fun oneShotCapturesOnceIncrementsWordRevisionAndWipesPcm() = runBlocking {
        var captures = 0
        val captured = audio.copyOf()
        service().enrollFrom { captures++; captured }
        assertEquals(1, captures); assertEquals(1, engine.calls); assertEquals(1, store.writes)
        assertEquals(4L, store.current!!.profileRevision); assertEquals(1, changes)
        assertTrue(captured.all { it == 0f })
    }
    @Test fun badAudioCannotReplacePreviousWord() = runBlocking {
        val old = store.current
        val captured = FloatArray(16_000)
        assertTrue(runCatching { service().enrollFrom { captured } }.isFailure)
        assertSame(old, store.current); assertEquals(0, store.writes); assertEquals(0, changes)
    }
    @Test fun saveFailurePreservesPreviousProfile() = runBlocking {
        val old = store.current; store.fail = true
        assertTrue(runCatching { service().enroll(audio) }.isFailure)
        assertSame(old, store.current); assertEquals(1, changes) // Failed writes still close the prior activation window.
    }
    @Test fun failedSessionInvalidationCannotReplaceWord() = runBlocking {
        val old = store.current
        val service = PersonalKeywordActivation(engine, store, onProfileChanged = { throw java.io.IOException("settings unavailable") })
        assertTrue(runCatching { service.enroll(audio) }.isFailure)
        assertSame(old, store.current); assertEquals(0, store.writes)
    }
    @Test fun cancellationDuringEnrollmentPreservesProfileAndWipesAudio() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        engine.action = { entered.complete(Unit); awaitCancellation() }
        val old = store.current
        val captured = audio.copyOf()
        val service = service()
        val job = launch { service.enrollFrom { captured } }
        entered.await(); job.cancelAndJoin()
        assertSame(old, store.current); assertTrue(captured.all { it == 0f }); assertEquals(0, changes)
        service.close(); assertEquals(1, engine.closes)
    }
    @Test fun closeIsSerializedAndIdempotent() = runBlocking {
        val service = service(); service.close(); service.close()
        assertEquals(1, engine.closes)
        assertEquals(MetricKwsDecision.Unavailable("closed"), service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertTrue(runCatching { service.enroll(audio) }.isFailure)
    }
    @Test fun unavailableEngineNeverRecordsOrRunsInference() = runBlocking {
        val service = PersonalKeywordActivation(UnavailableMetricKwsEngine(), store)
        var captures = 0
        assertFalse(service.available); assertFalse(service.activationValidated)
        assertTrue(runCatching { service.enrollFrom { captures++; audio } }.isFailure)
        assertEquals(0, captures)
        assertTrue(service.evaluate(audio, WakeWordEngine.METRIC_KWS) is MetricKwsDecision.Unavailable)
    }
    @Test fun deletionOnlyRemovesWordAndInvalidatesCurrentSession() = runBlocking {
        service().delete()
        assertNull(store.current); assertEquals(1, changes)
    }

    @Test fun namedEnrollmentRecognizesTheSameCaptureAndSavesAnEditableLabel() = runBlocking {
        val captured = audio.copyOf()
        var captures = 0
        var label = "AI Assistant"
        val service = service()
        service.enrollNamedFrom({ captures++; captured }, {
            assertSame(captured, it); assertArrayEquals(audio, it, 0f); "  Алиса.\n"
        }, { label = it })
        assertEquals(1, captures); assertEquals(1, engine.calls); assertEquals(1, store.writes)
        assertEquals("Алиса", label); assertTrue(captured.all { it == 0f })
        val previous = store.current
        label = "Другое имя"
        assertEquals("Другое имя", label)
        assertEquals(MetricKwsDecision.Accept, service.evaluate(audio, WakeWordEngine.METRIC_KWS))
        assertSame(previous, store.current)
    }

    @Test fun recognitionFailureOrEmptyTextPreservesBothNameAndWordAndWipesAudio() = runBlocking {
        val previous = store.current
        var saves = 0
        for (transcript in listOf<String?>(null, "", " ... ", "x".repeat(41))) {
            val captured = audio.copyOf()
            assertTrue(runCatching {
                service().enrollNamedFrom({ captured }, {
                    transcript ?: throw java.io.IOException("recognizer unavailable")
                }, { saves++ })
            }.isFailure)
            assertSame(previous, store.current); assertTrue(captured.all { it == 0f })
        }
        assertEquals(0, saves); assertEquals(0, store.writes); assertEquals(0, changes)
    }

    @Test fun cancellationDuringNameRecognitionPreservesPreviousWordAndWipesAudio() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val captured = audio.copyOf()
        val previous = store.current
        var saved = false
        val job = launch {
            service().enrollNamedFrom({ captured }, { entered.complete(Unit); awaitCancellation() }, { saved = true })
        }
        entered.await(); job.cancelAndJoin()
        assertFalse(saved); assertSame(previous, store.current); assertEquals(0, changes)
        assertTrue(captured.all { it == 0f })
    }

    @Test fun failedNameCommitRestoresAnExistingWordOrRemovesTheNewOne() = runBlocking {
        val existing = store.current
        for (previous in listOf(existing, null)) {
            store.current = previous
            val captured = audio.copyOf()
            assertTrue(runCatching {
                service().enrollNamedFrom({ captured }, { "Алиса" }, { throw java.io.IOException("settings failed") })
            }.isFailure)
            assertSame(previous, store.current); assertTrue(captured.all { it == 0f })
        }
    }

    @Test fun failedWordCommitNeverChangesTheDisplayName() = runBlocking {
        val previous = store.current
        store.fail = true
        var saved = false
        val captured = audio.copyOf()
        assertTrue(runCatching {
            service().enrollNamedFrom({ captured }, { "Алиса" }, { saved = true })
        }.isFailure)
        assertFalse(saved); assertSame(previous, store.current); assertTrue(captured.all { it == 0f })
    }

    @Test fun cancellationAfterCommitStartsFinishesBothWritesAndWipesAudio() = runBlocking {
        val committing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val captured = audio.copyOf()
        var label = "AI Assistant"
        val job = launch {
            service().enrollNamedFrom({ captured }, { "Алиса" }, {
                committing.complete(Unit); finish.await(); label = it
            })
        }
        committing.await(); job.cancel(); finish.complete(Unit); job.join()
        assertEquals("Алиса", label); assertEquals(4L, store.current!!.profileRevision)
        assertEquals(1, store.writes); assertTrue(captured.all { it == 0f })
    }
}
