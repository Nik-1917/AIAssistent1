package com.example.aiassistent1.data.provider

import com.example.aiassistent1.domain.interfaces.MetricKwsEngine
import com.example.aiassistent1.domain.model.MetricKwsConfig

/** Deliberate release gate: no unaudited weights or approximate DSP in the application. */
class UnavailableMetricKwsEngine : MetricKwsEngine {
    override val config: MetricKwsConfig? = null
    override val unavailableReason = "Персональная активация пока недоступна: модель не прошла проверку лицензии и русской речи."
    override val activationValidated = false
    override suspend fun embedding(samples: FloatArray): FloatArray = error(unavailableReason)
    override suspend fun close() = Unit
}
