package com.example.aiassistent1.domain.model

import kotlin.math.abs
import kotlin.math.sqrt

enum class WakeWordEngine {
    LEGACY_ASR, METRIC_KWS_SHADOW, METRIC_KWS;

    companion object {
        fun fromStored(value: String?): WakeWordEngine = entries.firstOrNull { it.name == value } ?: LEGACY_ASR
    }
}

/** Metadata belongs to an audited artifact, never to guessed defaults from a paper. */
data class MetricKwsConfig(
    val modelVersion: String,
    val modelSha256: String,
    val featureVersion: String,
    val embeddingSize: Int,
    val keywordThreshold: Float,
    val sampleRate: Int = 16_000,
    val minSamples: Int = 8_000,
    val maxSamples: Int = 128_000,
) {
    init {
        require(modelVersion.length in 1..128 && featureVersion.length in 1..128)
        require(modelSha256.matches(Regex("[0-9a-f]{64}")))
        require(embeddingSize in 1..4096 && keywordThreshold.isFinite() && keywordThreshold in -1f..1f)
        require(sampleRate == 16_000 && minSamples >= 8_000 && maxSamples in minSamples..128_000)
    }
}

/** Speaker data stays in voice_profile.bin; revision binds this record to that profile. */
data class MetricKwsProfile(
    val config: MetricKwsConfig,
    val voiceRevision: Long,
    val createdAt: Long,
    val embedding: FloatArray,
) {
    fun validate() {
        require(voiceRevision >= 0 && createdAt >= 0)
        require(embedding.size == config.embeddingSize)
        require(KeywordEmbedding.isValid(embedding))
        val norm = sqrt(embedding.sumOf { it.toDouble() * it })
        require(abs(norm - 1.0) <= 0.001) { "KWS embedding must be normalized" }
    }
}

object KeywordEmbedding {
    fun isValid(value: FloatArray): Boolean = value.size in 1..4096 &&
        value.all(Float::isFinite) && value.any { it != 0f }

    fun normalize(value: FloatArray): FloatArray {
        require(isValid(value)) { "Некорректный отпечаток ключевой фразы" }
        val norm = sqrt(value.sumOf { it.toDouble() * it })
        return FloatArray(value.size) { (value[it] / norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float? {
        if (!isValid(a) || !isValid(b) || a.size != b.size) return null
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
        }
        return (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
    }

    fun matches(score: Float?, threshold: Float): Boolean = score != null && score.isFinite() &&
        threshold.isFinite() && threshold in -1f..1f && score in -1f..1f && score >= threshold
}

object MetricKwsAudio {
    /** Conservative enrollment guard, not a claim that the encoder supports this duration. */
    fun validate(samples: FloatArray, config: MetricKwsConfig) {
        require(samples.size in config.minSamples..config.maxSamples) { "Фраза слишком короткая или длинная. Повторите запись." }
        require(samples.all { it.isFinite() && abs(it) <= 1f }) { "Некорректная запись. Повторите запись." }
        val energy = samples.sumOf { it.toDouble() * it } / samples.size
        require(energy >= 0.00001) { "Запись слишком тихая. Повторите запись." }
        require(samples.count { abs(it) >= 0.999f }.toDouble() / samples.size <= 0.01) {
            "Звук перегружен. Отодвиньте телефон и повторите запись."
        }
    }
}

sealed interface MetricKwsDecision {
    data class Legacy(val reason: String) : MetricKwsDecision
    data class Shadow(val keywordScore: Float, val keywordMatched: Boolean) : MetricKwsDecision
    data object Reject : MetricKwsDecision
    data object Accept : MetricKwsDecision
}
