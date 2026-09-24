package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.MetricKwsEngine
import com.example.aiassistent1.domain.interfaces.MetricKwsProfileStore
import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serializes enrollment, inference, removal and engine shutdown. Owns no capture resources. */
class PersonalKeywordActivation(
    private val engine: MetricKwsEngine,
    private val profiles: MetricKwsProfileStore,
    private val preferences: suspend () -> AudioPreferences,
    private val verifySpeaker: suspend (FloatArray) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val selectLegacy: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var closed = false
    private val diagnostic = MutableStateFlow<MetricKwsDecision?>(null)
    val lastDiagnostic = diagnostic.asStateFlow()
    val available: Boolean get() = engine.config != null
    val activationValidated: Boolean get() = engine.activationValidated && available
    val unavailableReason: String? get() = engine.unavailableReason

    suspend fun enrollFrom(capture: suspend () -> FloatArray) {
        check(available) { unavailableReason ?: "Модель недоступна" }
        val prefs = preferences()
        check(prefs.voiceIdEnabled && !prefs.needsEnrollment) { "Сначала включите и настройте Voice ID" }
        val samples = capture()
        try { enroll(samples) } finally { samples.fill(0f) }
    }

    /** One completed VAD segment; verifies the existing owner without replacing their profile. */
    suspend fun enroll(samples: FloatArray) = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!closed) { "Обработка ключевой фразы завершена" }
            val config = checkNotNull(engine.config) { engine.unavailableReason ?: "Модель недоступна" }
            val before = preferences()
            check(before.voiceIdEnabled && !before.needsEnrollment) { "Сначала включите и настройте Voice ID" }
            MetricKwsAudio.validate(samples, config)
            val embedding = KeywordEmbedding.normalize(engine.embedding(samples))
            check(embedding.size == config.embeddingSize) { "Размер отпечатка не соответствует модели" }
            check(verifySpeaker(samples)) { "Голос не подтверждён. Повторите запись." }
            val after = preferences()
            check(after.voiceIdEnabled && !after.needsEnrollment && after.revision == before.revision) {
                "Настройки голоса изменились. Повторите запись."
            }
            currentCoroutineContext().ensureActive()
            profiles.save(MetricKwsProfile(config, before.revision, clock(), embedding))
        }
    }

    suspend fun evaluate(samples: FloatArray, mode: WakeWordEngine): MetricKwsDecision {
        if (mode == WakeWordEngine.LEGACY_ASR) return MetricKwsDecision.Legacy("legacy_selected")
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                if (closed) return@withLock MetricKwsDecision.Legacy("closed")
                val config = engine.config ?: return@withLock MetricKwsDecision.Legacy("model_unavailable")
                if (mode == WakeWordEngine.METRIC_KWS && !engine.activationValidated)
                    return@withLock MetricKwsDecision.Legacy("validation_required")
                try {
                    val before = preferences()
                    if (!before.voiceIdEnabled || before.needsEnrollment)
                        return@withLock MetricKwsDecision.Legacy("voice_id_required")
                    val profile = profiles.load() ?: return@withLock MetricKwsDecision.Legacy("profile_missing_or_corrupt")
                    profile.validate()
                    if (profile.config != config || profile.voiceRevision != before.revision)
                        return@withLock MetricKwsDecision.Legacy("reenrollment_required")
                    try { MetricKwsAudio.validate(samples, config) }
                    catch (_: IllegalArgumentException) { return@withLock MetricKwsDecision.Reject }
                    val candidate = engine.embedding(samples)
                    val score = KeywordEmbedding.cosine(profile.embedding, candidate)
                        ?: return@withLock MetricKwsDecision.Legacy("invalid_embedding")
                    val matched = KeywordEmbedding.matches(score, config.keywordThreshold)
                    // Shadow never calls speaker enrollment, feedback, ASR or activation.
                    if (mode == WakeWordEngine.METRIC_KWS_SHADOW)
                        return@withLock MetricKwsDecision.Shadow(score, matched)
                    if (!matched || !verifySpeaker(samples)) return@withLock MetricKwsDecision.Reject
                    val after = preferences()
                    if (!after.voiceIdEnabled || after.needsEnrollment || after.revision != before.revision ||
                        after.wakeWordEngine != mode)
                        return@withLock MetricKwsDecision.Reject
                    MetricKwsDecision.Accept
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { MetricKwsDecision.Legacy("runtime_error") }
                catch (_: LinkageError) { MetricKwsDecision.Legacy("runtime_unavailable") }
            }
        }.also { diagnostic.value = it }
    }

    suspend fun profileStatus(): String = mutex.withLock {
        if (!available) return@withLock unavailableReason ?: "Модель недоступна"
        val profile = profiles.load() ?: return@withLock "Ключевая фраза не настроена"
        val prefs = preferences()
        if (profile.config != engine.config || profile.voiceRevision != prefs.revision)
            "Требуется перезапись ключевой фразы"
        else if (!prefs.voiceIdEnabled || prefs.needsEnrollment) "Требуется настроенный Voice ID"
        else "Ключевая фраза сохранена; Voice ID включён"
    }

    suspend fun delete() = mutex.withLock {
        selectLegacy()
        profiles.delete()
    }
    suspend fun close() = mutex.withLock {
        if (!closed) { closed = true; engine.close() }
    }
}
