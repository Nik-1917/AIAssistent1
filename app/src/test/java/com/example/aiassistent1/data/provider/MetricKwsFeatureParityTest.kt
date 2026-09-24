package com.example.aiassistent1.data.provider

import com.example.aiassistent1.audio.MetricKwsParity
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MetricKwsFeatureParityTest(private val fixture: String) {
    @Test fun fullMfccMatrixMatchesPinnedTorchaudioReference() {
        println(MetricKwsParity.check(fixture, ::read))
    }

    companion object {
        private fun read(name: String): ByteArray = checkNotNull(
            MetricKwsFeatureParityTest::class.java.getResourceAsStream("/metric_kws/$name")
        ).use { it.readBytes() }

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String>> = MetricKwsParity.cases(::read).map { arrayOf(it) }
    }
}
