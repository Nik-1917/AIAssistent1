package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.interfaces.MetricKwsEngine
import com.example.aiassistent1.domain.model.KeywordEmbedding
import com.example.aiassistent1.domain.model.MetricKwsConfig
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Experimental encoder using the ORT already packaged by Sherpa. No microphone or downloads.
 * AppModule intentionally does not select it. Acoustic quality and live activation remain gated.
 * The fixed frontend centrally crops/pads to one second; this is not multiword support.
 */
internal class OnnxMetricKwsEngine private constructor(
    override val config: MetricKwsConfig,
    private var handle: Long,
) : MetricKwsEngine {
    private val mutex = Mutex()
    private val extractor = MetricKwsFeatureExtractor()
    override val unavailableReason: String? = null
    override val activationValidated: Boolean = false

    override suspend fun embedding(samples: FloatArray): FloatArray = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(handle != 0L) { "KWS encoder is closed" }
            require(samples.size in config.minSamples..config.maxSamples) { "Unsupported KWS audio duration" }
            val features = extractor.extract(samples, config.sampleRate)
            try {
                KeywordEmbedding.normalize(MetricKwsNative.infer(handle, features))
            } finally {
                features.fill(0f)
            }
        }
    }

    override suspend fun close() = withContext(Dispatchers.Default + NonCancellable) {
        mutex.withLock {
            if (handle != 0L) {
                MetricKwsNative.close(handle)
                handle = 0L
            }
        }
    }

    companion object {
        /** Caller must supply an audited artifact and its pinned metadata, never a user model. */
        suspend fun createForExperiment(model: ByteArray, config: MetricKwsConfig): OnnxMetricKwsEngine {
            var createdHandle = 0L
            try {
                return withContext(Dispatchers.Default) {
                    require(model.size in 1..2_097_152) { "Invalid KWS model size" }
                    require(config.embeddingSize == 64 && config.featureVersion == MetricKwsFeatureExtractor.FEATURE_VERSION)
                    // Own bytes during hashing/loading; caller mutation cannot bypass the digest.
                    val owned = model.copyOf()
                    try {
                        val expected = config.modelSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(owned), expected)) {
                            "KWS model SHA-256 mismatch"
                        }
                        createdHandle = MetricKwsNative.create(owned)
                        OnnxMetricKwsEngine(config, createdHandle)
                    } finally {
                        owned.fill(0)
                    }
                }
            } catch (failure: Throwable) {
                // withContext can cancel on return after a successful native allocation.
                if (createdHandle != 0L) MetricKwsNative.close(createdHandle)
                throw failure
            }
        }
    }
}

/** Fixed float32 ABI: [1,101,40] -> [1,64]. Handles are validated in native code too. */
internal object MetricKwsNative {
    init { System.loadLibrary("assistant_metric_kws") }
    external fun create(model: ByteArray): Long
    external fun infer(handle: Long, features: FloatArray): FloatArray
    external fun close(handle: Long)
    external fun runtimeVersion(): String
}
