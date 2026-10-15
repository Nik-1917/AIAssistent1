package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AssistantSpeechEchoGuardTest {
    private val second = 1_000_000_000L
    private var now = 10 * second
    private fun guard(withSpeaker: Boolean = false) = AssistantSpeechEchoGuard(if (withSpeaker) speaker else null) { now }
    private fun playing(guard: AssistantSpeechEchoGuard, text: String = "Солнечная система."): AssistantSpeechEchoGuard.Context {
        val id = guard.prepare(text, FloatArray(16_000) { .1f })
        guard.queued(id, now, 16_000)
        return guard.snapshot(now, now + second)
    }

    @Test fun narratorVoiceIsRejectedEvenWhenAsrMisrecognizesItsWords() = runTest {
        val guard = guard(withSpeaker = true)
        val source = guard.assess(FloatArray(16_000) { .2f }, playing(guard))
        assertTrue(source.narrator)
        assertTrue(guard.rejectsText(source, "ошибка распознавания"))
        assertEquals(1, guard.diagnostics.value.rejected)
    }

    @Test fun distinctVoiceCanRepeatAWordActuallySpokenByTheNarrator() = runTest {
        val guard = guard(withSpeaker = true)
        val source = guard.assess(FloatArray(16_000) { .3f }, playing(guard))
        assertTrue(source.distinctVoice)
        assertFalse(guard.rejectsText(source, "солнечная"))
    }

    @Test fun uncertainShortNarratorFragmentIsRejectedButANewCommandIsAccepted() = runTest {
        val guard = guard()
        val source = guard.assess(FloatArray(4000), playing(guard))
        assertTrue(guard.rejectsText(source, "СОЛНЕЧНАЯ!"))
        assertTrue(guard.rejectsText(source, "солнечна система"))
        assertFalse(guard.rejectsText(source, "проверка перебивания ответь коротко"))
        assertFalse(guard.rejectsText(source, "стоп"))
    }

    @Test fun ambiguousSpeakerEvidenceStillRequiresTheNarratorTextCheck() = runTest {
        val guard = guard(withSpeaker = true)
        val source = guard.assess(FloatArray(16_000) { .4f }, playing(guard))
        assertTrue(source.needsTextCheck)
        assertTrue(guard.rejectsText(source, "солнечная система"))
        assertFalse(guard.rejectsText(source, "отмени это и расскажи о Земле"))
    }

    @Test fun preparedAndFutureOutputCannotRejectEarlierUserSpeech() = runTest {
        val guard = guard()
        val id = guard.prepare("проверка", FloatArray(16_000))
        assertFalse(guard.snapshot(now, now + second).overlapsNarrator)
        guard.queued(id, now + 2 * second, 16_000)
        val source = guard.assess(FloatArray(4000), guard.snapshot(now, now + second))
        assertFalse(source.context.overlapsNarrator)
        assertFalse(guard.rejectsText(source, "проверка"))
    }

    @Test fun stoppedOutputKeepsAnEchoTailAndDoesNotBlockLaterSpeech() = runTest {
        val guard = guard()
        val id = guard.prepare("проверка", FloatArray(16_000))
        guard.queued(id, now, 16_000)
        guard.outputStopped(now + second / 2)
        assertTrue(guard.snapshot(now + second * 3 / 4, now + second).overlapsNarrator)
        assertFalse(guard.snapshot(now + second, now + 2 * second).overlapsNarrator)
    }

    @Test fun delayedRecognitionRetainsOnlyTheOutputCapturedWithItsUtterance() = runTest {
        val guard = guard()
        val captured = playing(guard)
        now += 32 * second
        playing(guard, "Проверка перебивания.")
        assertFalse(guard.snapshot(10 * second, 11 * second).overlapsNarrator)
        val source = guard.assess(FloatArray(4000), captured)
        assertTrue(guard.rejectsText(source, "солнечная"))
        assertFalse(guard.rejectsText(source, "проверка перебивания"))
    }

    @Test fun withoutOverlappingOutputEvenOneWordIsValidInput() = runTest {
        val guard = guard()
        val source = guard.assess(FloatArray(4000), guard.snapshot(now, now + second))
        assertFalse(guard.rejectsText(source, "да"))
        assertFalse(source.needsTextCheck)
        assertEquals(0, guard.diagnostics.value.evaluated)
    }

    private val speaker = object : SpeakerIdentifier {
        override suspend fun prepare() = Unit
        override suspend fun computeEmbedding(samples: FloatArray): FloatArray? = FloatArray(16).also { vector ->
            when (samples.firstOrNull()) {
                .1f, .2f -> vector[0] = 1f
                .3f -> vector[1] = 1f
                .4f -> { vector[0] = .5f; vector[1] = .8660254f }
                else -> return null
            }
        }
        override fun verify(embedding1: FloatArray, embedding2: FloatArray, threshold: Float) = false
        override fun close() = Unit
    }
}
