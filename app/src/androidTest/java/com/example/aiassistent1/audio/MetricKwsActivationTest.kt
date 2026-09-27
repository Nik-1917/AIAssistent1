package com.example.aiassistent1.audio

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.BundledMetricKwsEngine
import com.example.aiassistent1.data.provider.MetricKwsProfileManager
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.usecase.PersonalKeywordActivation
import com.example.aiassistent1.domain.usecase.PersonalKeywordWakeSession
import com.example.aiassistent1.domain.usecase.PersonalWakeResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real bundled encoder/storage on Android, controlled owner/ASR doubles; no microphone quality claim. */
class MetricKwsActivationTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun pcm(name: String): FloatArray {
        val bytes = instrument.context.assets.open("metric_kws_ru_v3/$name.bin").use { it.readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(data.int == 0x31464b4d)
        val size = data.int; check(data.int == 4040)
        return FloatArray(size) { data.float }
    }

    @Test fun appBundleMetadataAndFactoryKeepExperimentSeparateFromValidation() = runBlocking {
        val context = instrument.targetContext
        val bytes = context.assets.open(BundledMetricKwsEngine.ASSET).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(BundledMetricKwsEngine.CONFIG.modelSha256, hash)
        val json = JSONObject(context.assets.open("metric_kws/v3/model.json").bufferedReader().use { it.readText() })
        assertEquals(hash, json.getString("model_sha256"))
        assertEquals(BundledMetricKwsEngine.CONFIG.keywordThreshold, json.getDouble("threshold").toFloat(), 0f)
        assertFalse(json.getBoolean("activation_validated"))
        for (name in listOf("LICENSE-CC-BY-4.0.txt", "ATTRIBUTION.md"))
            assertTrue(context.assets.open("metric_kws/v3/$name").use { it.readBytes() }.size > 100)
        val actualFactory = AppModule.providePersonalKeywordActivation(context)
        try { assertTrue(actualFactory.available); assertTrue(actualFactory.activationAllowed); assertFalse(actualFactory.activationValidated) }
        finally { actualFactory.close() }
    }

    @Test fun distinctRussianUtterancesEnrollActivateAndSurviveReopening() = runBlocking {
        val base = instrument.targetContext
        val directory = File(base.cacheDir, "metric-activation-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val speakerFile = File(directory, "voice_profile.bin").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val store = MetricKwsProfileManager(context)
        var prefs = AudioPreferences(voiceIdEnabled = false, wakeWordEnabled = true, revision = 11,
            wakeWordEngine = WakeWordEngine.METRIC_KWS)
        var owner = true
        var ownerCalls = 0
        fun service() = PersonalKeywordActivation(BundledMetricKwsEngine.fromAssets(base), store,
            onProfileChanged = { prefs = prefs.copy(policyRevision = prefs.policyRevision + 1) })
        var activation = service()
        try {
            val support = pcm("ru_00_0")
            val query = pcm("ru_00_1")
            assertFalse(support.contentEquals(query))
            var captures = 0
            activation.enrollFrom { captures++; support }
            assertEquals(1, captures); assertTrue(support.all { it == 0f })
            assertTrue(activation.status().canActivate)
            val before = File(directory, "metric_kws_profile.bin").readBytes()
            assertTrue(runCatching { activation.enrollFrom { FloatArray(16_000) } }.isFailure)
            assertArrayEquals(before, File(directory, "metric_kws_profile.bin").readBytes())
            assertEquals(MetricKwsDecision.Accept, activation.evaluate(query, WakeWordEngine.METRIC_KWS))
            val calls = ownerCalls
            assertEquals(MetricKwsDecision.Reject, activation.evaluate(pcm("ru_02_1"), WakeWordEngine.METRIC_KWS))
            assertEquals(calls, ownerCalls)
            activation.close(); activation = service()
            assertTrue(activation.status().canActivate)
            var now = 1_000L
            var asrCalls = 0
            var signals = 0
            val session = PersonalKeywordWakeSession({ prefs }, { activation.evaluate(it, WakeWordEngine.METRIC_KWS) },
                { ownerCalls++; owner }, { asrCalls++; "команда" }, { signals++ }, clock = { now })
            assertEquals(PersonalWakeResult.Activated, session.handle(query, now))
            assertEquals(0, asrCalls); assertEquals(1, signals)
            now += 1000
            assertEquals(PersonalWakeResult.Command("команда"), session.handle(pcm("ru_02_1"), now))
            assertEquals(1, asrCalls)
            assertEquals(0, ownerCalls)
            prefs = prefs.copy(voiceIdEnabled = true)
            owner = false
            assertEquals(PersonalWakeResult.Rejected, session.handle(query, now))
            assertEquals(1, signals)
            owner = true
            assertEquals(PersonalWakeResult.Activated, session.handle(query, now))
            now += 100
            owner = false
            assertEquals(PersonalWakeResult.Rejected, session.handle(pcm("ru_02_1"), now))
            assertEquals(1, asrCalls)
            owner = true
            assertEquals(PersonalWakeResult.Command("команда"), session.handle(pcm("ru_02_1"), now))
            prefs = prefs.copy(revision = 12)
            assertTrue(activation.status().canActivate)
            assertEquals(MetricKwsDecision.Accept, activation.evaluate(query, WakeWordEngine.METRIC_KWS))
            activation.delete()
            assertEquals(WakeWordEngine.METRIC_KWS, prefs.wakeWordEngine)
            assertEquals(MetricKwsDecision.Unavailable("profile_missing_or_corrupt"), activation.evaluate(query, WakeWordEngine.METRIC_KWS))
            assertNull(store.load()); assertArrayEquals(byteArrayOf(7, 8, 9), speakerFile.readBytes())
        } finally { activation.close(); directory.deleteRecursively() }
    }

    @Test fun corruptBundleIsRejectedBeforeCaptureAndDoesNotReloadRepeatedly() = runBlocking {
        var reads = 0
        val engine = BundledMetricKwsEngine {
            reads++
            instrument.targetContext.assets.open(BundledMetricKwsEngine.ASSET).use { it.readBytes() }
                .also { it[0] = (it[0].toInt() xor 1).toByte() }
        }
        try {
            assertTrue(runCatching { engine.prepare() }.isFailure)
            assertNull(engine.config)
            assertTrue(runCatching { engine.prepare() }.isFailure)
            assertEquals(1, reads)
        } finally { engine.close(); engine.close() }
    }
}
