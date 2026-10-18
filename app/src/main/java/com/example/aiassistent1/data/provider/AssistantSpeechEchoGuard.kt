package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.interfaces.SpeakerIdentifier
import com.example.aiassistent1.domain.model.VoiceEmbedding
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bounded, in-memory identity of the narrator. It never enrolls or persists a user's voice. */
internal class AssistantSpeechEchoGuard(private val speaker: SpeakerIdentifier? = null,
    private val clock: () -> Long = System::nanoTime) {
    internal class Reference(val text: List<String>, val pcm: FloatArray, val preparedAt: Long, val playbackId: Long) {
        var firstNs = Long.MAX_VALUE
        var endNs = Long.MIN_VALUE
        var embeddingReady = false
        var embedding: FloatArray? = null
    }
    class Context internal constructor(internal val references: List<Reference>) {
        val overlapsNarrator: Boolean get() = references.isNotEmpty()
    }
    data class Assessment(val context: Context, val similarity: Double?, val narrator: Boolean,
        val distinctVoice: Boolean) {
        val needsTextCheck: Boolean get() = context.overlapsNarrator && !narrator && !distinctVoice
    }
    data class Diagnostics(val evaluated: Int = 0, val rejected: Int = 0, val similarity: Double? = null,
        val reason: String = "none")

    private val references = linkedMapOf<Long, Reference>()
    private var nextId = 0L
    private var nextPlaybackId = 0L
    private val modelMutex = Mutex()
    private val mutableDiagnostics = MutableStateFlow(Diagnostics())
    val diagnostics = mutableDiagnostics.asStateFlow()

    @Synchronized fun beginPlayback(): Long = ++nextPlaybackId

    @Synchronized fun prepare(text: String, samples: FloatArray, playbackId: Long = 0): Long {
        prune()
        val id = ++nextId
        // A speaker signature needs only a short reference, not a copy of the whole reply.
        references[id] = Reference(tokens(text), samples.copyOf(minOf(samples.size, 48_000)), clock(), playbackId)
        while (references.size > 12) references.remove(references.keys.first())
        return id
    }

    @Synchronized fun queued(id: Long, firstSampleNs: Long, count: Int) {
        references[id]?.let {
            it.firstNs = minOf(it.firstNs, firstSampleNs)
            it.endNs = maxOf(it.endNs, firstSampleNs + count * PlaybackEchoReference.NANOS_PER_SAMPLE)
        }
    }

    @Synchronized fun outputStopped(timeNs: Long) {
        references.values.forEach { if (it.firstNs != Long.MAX_VALUE) it.endNs = minOf(it.endNs, timeNs) }
    }

    /** Snapshot at capture time: delayed ASR cannot lose its reference or match a future reply. */
    @Synchronized fun snapshot(startNs: Long, endNs: Long): Context {
        require(startNs <= endNs)
        prune()
        return Context(references.values.filter {
            it.firstNs != Long.MAX_VALUE && it.firstNs <= endNs + TIMING_MARGIN_NS &&
                it.endNs + ECHO_TAIL_NS >= startNs
        })
    }

    suspend fun assess(samples: FloatArray, context: Context): Assessment {
        if (!context.overlapsNarrator) return Assessment(context, null, narrator = false, distinctVoice = false)
        val similarity = modelMutex.withLock {
            val incoming = embedding(samples)
            if (incoming == null) null else {
                val scores = context.references.mapNotNull { reference ->
                    if (!reference.embeddingReady) {
                        reference.embedding = embedding(reference.pcm)
                        reference.embeddingReady = true
                    }
                    reference.embedding?.let { cosine(incoming, it) }
                }
                scores.maxOrNull()
            }
        }
        val assessment = Assessment(context, similarity, narrator = similarity != null && similarity >= NARRATOR_THRESHOLD,
            distinctVoice = similarity != null && similarity <= DISTINCT_THRESHOLD)
        record(assessment, assessment.narrator, if (assessment.narrator) "narrator_voice" else "voice_checked")
        return assessment
    }

    /** Text is a fallback for short/uncertain fragments; a distinct voice may repeat the narrator. */
    fun rejectsText(assessment: Assessment, transcript: String): Boolean {
        if (assessment.narrator) return true
        if (!assessment.needsTextCheck) return false
        val candidate = tokens(transcript)
        // A VAD utterance can contain the end of one TTS sentence and the beginning of the next.
        // Join only captured references from the same reply; never invent a match between replies.
        val rejected = candidate.isNotEmpty() && assessment.context.references.groupBy { it.playbackId }.values.any { references ->
            val text = references.flatMap { it.text }
            candidate.size <= text.size && (0..text.size - candidate.size).any { start ->
                candidate.indices.all { index -> sameToken(candidate[index], text[start + index]) }
            }
        }
        if (rejected) record(assessment, true, "narrator_text")
        return rejected
    }

    private suspend fun embedding(samples: FloatArray): FloatArray? = try {
        speaker?.computeEmbedding(samples)?.takeIf(VoiceEmbedding::isValid)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    catch (_: LinkageError) { null }

    private fun record(assessment: Assessment, rejected: Boolean, reason: String) {
        val previous = mutableDiagnostics.value
        mutableDiagnostics.value = Diagnostics(previous.evaluated + if (reason == "narrator_text") 0 else 1,
            previous.rejected + if (rejected) 1 else 0, assessment.similarity, reason)
    }

    private fun prune() {
        val now = clock()
        references.entries.removeAll { (_, value) ->
            now - (if (value.endNs == Long.MIN_VALUE) value.preparedAt else value.endNs) > HISTORY_NS
        }
    }

    private companion object {
        const val HISTORY_NS = 30_000_000_000L
        const val ECHO_TAIL_NS = 400_000_000L
        const val TIMING_MARGIN_NS = 120_000_000L
        // Source rejection, not authentication. The owner's optional Voice ID keeps its own policy.
        const val NARRATOR_THRESHOLD = 0.72
        const val DISTINCT_THRESHOLD = 0.35
        val separator = Regex("[^\\p{L}\\p{N}]+")
        fun tokens(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е').split(separator)
            .filter(String::isNotEmpty).take(256)

        fun cosine(a: FloatArray, b: FloatArray): Double? {
            if (a.size != b.size) return null
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
            return (dot / sqrt(aa * bb)).takeIf { it.isFinite() }
        }

        fun sameToken(a: String, b: String): Boolean {
            if (a == b) return true
            val length = minOf(a.length, b.length)
            if (length < 5) return false
            val limit = if (length >= 8) 2 else 1
            if (kotlin.math.abs(a.length - b.length) > limit) return false
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in a.indices) {
                current[0] = i + 1
                var minimum = current[0]
                for (j in b.indices) {
                    current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                        previous[j] + if (a[i] == b[j]) 0 else 1)
                    minimum = minOf(minimum, current[j + 1])
                }
                if (minimum > limit) return false
                val swap = previous; previous = current; current = swap
            }
            return previous[b.length] <= limit
        }
    }
}
