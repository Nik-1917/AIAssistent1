package com.example.aiassistent1.data.provider

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BundledMetricKwsEngineTest {
    @Test fun constructionAndCloseNeverLoadUnusedModel() = runBlocking {
        var reads = 0
        val engine = BundledMetricKwsEngine { reads++; error("unexpected load") }
        assertNotNull(engine.config); assertFalse(engine.activationValidated)
        engine.close(); engine.close()
        assertNull(engine.config); assertEquals(0, reads)
    }

    @Test fun missingAssetIsCachedAndDoesNotLoadOnEveryUtterance() = runBlocking {
        var reads = 0
        val engine = BundledMetricKwsEngine { reads++; throw java.io.IOException("missing") }
        try {
            repeat(2) { assertTrue(runCatching { engine.prepare() }.isFailure) }
            assertEquals(1, reads); assertNull(engine.config); assertNotNull(engine.unavailableReason)
        } finally { engine.close() }
    }

    @Test fun cancelledLoadingDoesNotPoisonFuturePreparation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val engine = BundledMetricKwsEngine { entered.complete(Unit); awaitCancellation() }
        val job = launch { engine.prepare() }
        entered.await(); job.cancelAndJoin()
        assertNotNull(engine.config); assertNull(engine.unavailableReason)
        engine.close()
    }
}
