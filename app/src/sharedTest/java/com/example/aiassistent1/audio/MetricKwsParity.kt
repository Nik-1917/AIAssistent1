package com.example.aiassistent1.audio

import com.example.aiassistent1.data.provider.MetricKwsFeatureExtractor
import org.json.JSONObject
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Same full-vector, SHA-checked reference assertion on JVM and Android. */
internal object MetricKwsParity {
    fun cases(read: (String) -> ByteArray): List<String> {
        val manifest = JSONObject(String(read("manifest.json"), Charsets.UTF_8))
        return manifest.getJSONArray("cases").let { cases ->
            List(cases.length()) { cases.getJSONObject(it).getString("name") }
        }
    }

    fun check(name: String, read: (String) -> ByteArray): String {
        val manifest = JSONObject(String(read("manifest.json"), Charsets.UTF_8))
        assertEquals(1, manifest.getInt("schema_version"))
        assertEquals(MetricKwsFeatureExtractor.FEATURE_VERSION, manifest.getString("feature_version"))
        assertEquals(0.002, manifest.getDouble("max_absolute_error_limit"), 0.0)
        val cases = manifest.getJSONArray("cases")
        val description = (0 until cases.length()).map(cases::getJSONObject).single { it.getString("name") == name }
        val bytes = read("$name.bin")
        assertEquals(description.getInt("bytes"), bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("Fixture SHA: $name", description.getString("sha256"), sha)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x31464b4d, data.int) // MKF1
        val sampleCount = data.int
        val featureCount = data.int
        assertEquals(description.getInt("samples"), sampleCount)
        assertEquals(101 * 40, featureCount)
        assertEquals((sampleCount + featureCount) * 4, data.remaining())
        val pcm = FloatArray(sampleCount) { data.float }
        val expected = FloatArray(featureCount) { data.float }
        val original = pcm.copyOf()
        val actual = MetricKwsFeatureExtractor().extract(pcm)
        assertArrayEquals("Input modified: $name", original, pcm, 0f)
        assertEquals(expected.size, actual.size)
        var maximum = 0.0
        var squares = 0.0
        for (index in expected.indices) {
            assertTrue("Nonfinite feature $name/$index", actual[index].isFinite())
            val difference = abs(expected[index].toDouble() - actual[index])
            maximum = maxOf(maximum, difference)
            squares += difference * difference
        }
        val report = "$name samples=$sampleCount features=$featureCount max_abs=$maximum rmse=${sqrt(squares / featureCount)}"
        assertTrue(report, maximum <= 0.002)
        return report
    }
}
