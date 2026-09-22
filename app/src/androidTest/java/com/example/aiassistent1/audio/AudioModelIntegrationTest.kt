package com.example.aiassistent1.audio

import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.provider.*
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.model.WakeWordTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioModelIntegrationTest {
    @Test fun bundledModelsRecognizeSyntheticRussianNameAndExtractSpeakerEmbedding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val synthesizer = AppModule.provideSpeechSynthesizer(context)
        val recognizer = AppModule.provideSpeechRecognizer(context)
        val speaker = AppModule.provideSpeakerIdentifier(context)
        try {
            val speech = synthesizer.synthesize("Ассистент, проверка звука.").getOrThrow()
            val pcm = PcmResampler.to16k(speech.samples, speech.sampleRate)
            val transcript = recognizer.recognize(pcm).getOrThrow()
            android.util.Log.i("AudioV4Test", "Synthetic activation transcript: $transcript")
            assertNotNull("Name not detected in: $transcript", WakeWordTokens.removePrefix(transcript, "Ассистент"))
            val embedding = speaker.computeEmbedding(pcm)
            assertNotNull(embedding)
            assertTrue(speaker.verify(embedding!!, embedding))
        } finally { synthesizer.close(); recognizer.close(); speaker.close() }
    }
}
