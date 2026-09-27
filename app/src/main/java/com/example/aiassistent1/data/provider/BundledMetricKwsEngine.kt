package com.example.aiassistent1.data.provider

import android.content.Context
import com.example.aiassistent1.domain.interfaces.MetricKwsEngine
import com.example.aiassistent1.domain.model.MetricKwsConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lazy, per-consumer native session. Selection never asserts field-quality acceptance. */
internal class BundledMetricKwsEngine(
    private val readModel: suspend () -> ByteArray,
) : MetricKwsEngine {
    private val mutex = Mutex()
    private var delegate: OnnxMetricKwsEngine? = null
    @Volatile private var failed = false
    @Volatile private var closed = false
    override val config: MetricKwsConfig? get() = CONFIG.takeUnless { failed || closed }
    override val unavailableReason: String? get() = when {
        closed -> "Обработка персонального слова завершена"
        failed -> "Не удалось загрузить v3. Доступна прежняя активация по имени."
        else -> null
    }
    override val activationValidated = false
    override val experimentalActivationAllowed = true

    private suspend fun loaded(): OnnxMetricKwsEngine {
        check(!closed && !failed) { unavailableReason ?: "Модель недоступна" }
        delegate?.let { return it }
        try {
            val bytes = readModel()
            try {
                require(bytes.size == MODEL_BYTES) { "Повреждён файл модели v3" }
                return OnnxMetricKwsEngine.createForExperiment(bytes, CONFIG).also { delegate = it }
            } finally { bytes.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failed = true; throw error }
        catch (error: LinkageError) { failed = true; throw error }
    }

    override suspend fun prepare() = withContext(Dispatchers.Default) {
        mutex.withLock { loaded(); Unit }
    }

    override suspend fun embedding(samples: FloatArray): FloatArray = withContext(Dispatchers.Default) {
        mutex.withLock { loaded().embedding(samples) }
    }

    override suspend fun close() = withContext(Dispatchers.Default + NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                delegate?.close()
                delegate = null
            }
        }
    }

    companion object {
        const val ASSET = "metric_kws/v3/encoder.onnx"
        const val MODEL_BYTES = 1_004_112
        val CONFIG = MetricKwsConfig(
            modelVersion = "ru-mswc-personal-metric-v3",
            modelSha256 = "5d4828f3a8aea2eff1f677b1ab9c51acef0aa732c55f1c425d8d95da41d009a2",
            featureVersion = MetricKwsFeatureExtractor.FEATURE_VERSION,
            embeddingSize = 64,
            keywordThreshold = 0.5827946662902832f,
        )

        fun fromAssets(context: Context): BundledMetricKwsEngine {
            val assets = context.applicationContext.assets
            return BundledMetricKwsEngine {
                withContext(Dispatchers.IO) { assets.open(ASSET).use { it.readBytes() } }
            }
        }
    }
}
