package com.example.aiassistent1.audio

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.MetricKwsNative
import com.example.aiassistent1.data.provider.OnnxMetricKwsEngine
import com.example.aiassistent1.domain.model.KeywordEmbedding
import com.example.aiassistent1.domain.model.MetricKwsConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real JNI/ORT inference on ART. All speech/model assets are confined to the test APK. */
class MetricKwsEncoderTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private val assetRoot get() = InstrumentationRegistry.getArguments()
        .getString("metricKwsAssets", "metric_kws_ru")!!
    private fun read(name: String) = assets.open(name).use { it.readBytes() }
    private fun manifest() = JSONObject(String(read("$assetRoot/manifest.json"), Charsets.UTF_8))
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }
    private fun config(json: JSONObject) = MetricKwsConfig(
        modelVersion = json.getString("model_version"), modelSha256 = json.getString("model_sha256"),
        featureVersion = json.getString("feature_version"), embeddingSize = json.getInt("embedding_size"),
        keywordThreshold = json.getDouble("threshold").toFloat(),
    )

    @Test fun russianAndAnalyticPcmMatchPythonEmbeddings() = runBlocking {
        val json = manifest()
        val engine = OnnxMetricKwsEngine.createForExperiment(read("$assetRoot/encoder.onnx"), config(json))
        try {
            assertFalse(engine.activationValidated)
            var maximumError = 0f
            var minimumCosine = 1f
            val vectors = mutableMapOf<String, FloatArray>()
            val cases = json.getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val data = read(case.getString("asset"))
                assertEquals(case.getString("sha256"), sha(data))
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(0x31464b4d, buffer.int) // MKF1
                val samples = buffer.int
                assertEquals(4040, buffer.int)
                val pcm = FloatArray(samples) { buffer.float }
                val original = pcm.copyOf()
                val expectedArray = case.getJSONArray("expected_embedding")
                val expected = FloatArray(expectedArray.length()) { expectedArray.getDouble(it).toFloat() }
                val actual = engine.embedding(pcm)
                assertArrayEquals(original, pcm, 0f)
                assertEquals(64, actual.size)
                val error = actual.indices.maxOf { abs(actual[it] - expected[it]) }
                val cosine = requireNotNull(KeywordEmbedding.cosine(actual, expected))
                assertTrue("${case.getString("name")}: abs error=$error", error <= 1e-4f)
                assertTrue("${case.getString("name")}: cosine=$cosine", cosine >= .9999f)
                maximumError = maxOf(maximumError, error)
                minimumCosine = minOf(minimumCosine, cosine)
                vectors[case.getString("name")] = actual
            }
            // Scores/decisions must also agree with Python for distinct Russian utterances.
            for (support in 0 until cases.length()) {
                val s = cases.getJSONObject(support)
                if (s.optString("role") != "support") continue
                for (query in 0 until cases.length()) {
                    val q = cases.getJSONObject(query)
                    if (q.optString("role") != "query") continue
                    fun expected(case: JSONObject): FloatArray {
                        val values = case.getJSONArray("expected_embedding")
                        return FloatArray(values.length()) { values.getDouble(it).toFloat() }
                    }
                    val referenceScore = requireNotNull(KeywordEmbedding.cosine(expected(s), expected(q)))
                    val score = requireNotNull(KeywordEmbedding.cosine(vectors.getValue(s.getString("name")), vectors.getValue(q.getString("name"))))
                    assertEquals(referenceScore, score, 1e-4f)
                    assertEquals(referenceScore >= engine.config.keywordThreshold, score >= engine.config.keywordThreshold)
                }
            }
            Log.i("MetricKwsEncoder", "cases=${cases.length()} maxAbs=$maximumError minCosine=$minimumCosine ORT=${MetricKwsNative.runtimeVersion()}")
            Unit
        } finally {
            engine.close()
        }
    }

    @Test fun corruptModelsShapesInputsAndClosedSessionsAreRejected() = runBlocking {
        val json = manifest()
        val model = read("$assetRoot/encoder.onnx")
        val changed = model.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try {
            OnnxMetricKwsEngine.createForExperiment(changed, config(json))
            fail("Changed model admitted")
        } catch (_: IllegalArgumentException) { }
        val wrong = read("$assetRoot/invalid_shape.onnx")
        assertEquals(json.getString("invalid_shape_sha256"), sha(wrong))
        try {
            OnnxMetricKwsEngine.createForExperiment(wrong, config(json).copy(modelSha256 = sha(wrong)))
            fail("Wrong output shape admitted")
        } catch (_: IllegalStateException) { }
        val handle = MetricKwsNative.create(model)
        try {
            for (input in listOf(FloatArray(1), FloatArray(4040).also { it[9] = Float.NaN })) {
                try { MetricKwsNative.infer(handle, input); fail("Invalid features accepted") }
                catch (_: IllegalStateException) { }
            }
        } finally {
            MetricKwsNative.close(handle)
        }
        MetricKwsNative.close(handle)
        try { MetricKwsNative.infer(handle, FloatArray(4040)); fail("Stale handle accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun parallelCallsReopenAndIdempotentClose() = runBlocking {
        val json = manifest()
        val model = read("$assetRoot/encoder.onnx")
        repeat(3) {
            val engine = OnnxMetricKwsEngine.createForExperiment(model, config(json))
            try {
                val values = coroutineScope { List(4) { async { engine.embedding(FloatArray(16000)) } }.awaitAll() }
                values.drop(1).forEach { assertArrayEquals(values.first(), it, 0f) }
            } finally {
                engine.close()
                engine.close()
            }
            try { engine.embedding(FloatArray(16000)); fail("Closed Kotlin engine accepted input") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun existingSherpaModelsAndMetricSessionCoexist() = runBlocking {
        val json = manifest()
        val engine = OnnxMetricKwsEngine.createForExperiment(read("$assetRoot/encoder.onnx"), config(json))
        try {
            val before = engine.embedding(FloatArray(16000))
            // Exercise actual bundled TTS, ASR and speaker models in this same process while
            // the C API session remains alive. Reuse the existing integration assertion.
            AudioModelIntegrationTest().bundledModelsRecognizeSyntheticRussianNameAndExtractSpeakerEmbedding()
            assertArrayEquals(before, engine.embedding(FloatArray(16000)), 0f)
        } finally {
            engine.close()
        }
    }
}
