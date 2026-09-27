package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.MetricKwsEngine
import com.example.aiassistent1.domain.interfaces.MetricKwsProfileStore
import com.example.aiassistent1.domain.interfaces.PersonalKeywordStatus
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
    private val ownerReady: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private var closed = false
    private val diagnostic = MutableStateFlow<MetricKwsDecision?>(null)
    val lastDiagnostic = diagnostic.asStateFlow()
    val available: Boolean get() = engine.config != null
    val activationValidated: Boolean get() = engine.activationValidated && available
    val activationAllowed: Boolean get() = available && (engine.activationValidated || engine.experimentalActivationAllowed)
    val unavailableReason: String? get() = engine.unavailableReason

    suspend fun enrollFrom(capture: suspend () -> FloatArray) {
        check(available) { unavailableReason ?: "Модель недоступна" }
        val prefs = preferences()
        check(prefs.voiceIdEnabled && !prefs.needsEnrollment && ownerReady()) { "Сначала включите и настройте Voice ID" }
        mutex.withLock { check(!closed); engine.prepare() }
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
                if (mode == WakeWordEngine.METRIC_KWS && !activationAllowed)
                    return@withLock MetricKwsDecision.Legacy("validation_required")
                try {
                    val before = preferences()
                    if (!before.voiceIdEnabled || before.needsEnrollment || !ownerReady())
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

    suspend fun status(): PersonalKeywordStatus = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed || !available) return@withLock PersonalKeywordStatus(false, false, false,
                unavailableReason ?: "Модель недоступна")
            try {
                engine.prepare()
                val prefs = preferences()
                if (!prefs.voiceIdEnabled || prefs.needsEnrollment || !ownerReady())
                    return@withLock PersonalKeywordStatus(true, false, false, "Сначала включите и настройте Voice ID")
                val profile = profiles.load()
                    ?: return@withLock PersonalKeywordStatus(true, false, false, "Персональное слово не записано")
                profile.validate()
                if (profile.config != engine.config || profile.voiceRevision != prefs.revision)
                    return@withLock PersonalKeywordStatus(true, false, false, "Требуется перезапись персонального слова")
                PersonalKeywordStatus(true, true, activationAllowed,
                    if (activationAllowed) "Персональное слово сохранено; Voice ID настроен"
                    else "Модель доступна только для проверки")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PersonalKeywordStatus(false, false, false, unavailableReason ?: "Не удалось проверить модель и профиль") }
            catch (_: LinkageError) { PersonalKeywordStatus(false, false, false, "Аудиобиблиотека недоступна; используйте активацию по имени") }
        }
    }

    suspend fun profileStatus(): String = status().message

    suspend fun delete() = mutex.withLock {
        selectLegacy()
        profiles.delete()
    }
    suspend fun close() = mutex.withLock {
        if (!closed) { closed = true; engine.close() }
    }
}
