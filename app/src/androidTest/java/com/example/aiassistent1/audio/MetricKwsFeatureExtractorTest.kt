package com.example.aiassistent1.audio

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Actual ART execution, no model, microphone or acoustic-quality claims. */
class MetricKwsFeatureExtractorTest {
    @Test fun allAnalyticFixturesMatchPinnedTorchaudioReference() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val read = { name: String -> assets.open(name).use { it.readBytes() } }
        for (name in MetricKwsParity.cases(read)) {
            Log.i("MetricKwsParity", MetricKwsParity.check(name, read))
        }
    }
}
