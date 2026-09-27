package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.MetricKwsEngine
import com.example.aiassistent1.domain.interfaces.MetricKwsProfileStore
import com.example.aiassistent1.domain.interfaces.PersonalKeywordStatus
import com.example.aiassistent1.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val onProfileChanged: suspend () -> Unit = {},
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
        withEnrollmentSamples(capture) { enroll(it) }
    }

    /** ASR supplies only an editable display label. It is never consulted by evaluate(). */
    suspend fun enrollNamedFrom(
        capture: suspend () -> FloatArray,
        recognize: suspend (FloatArray) -> String,
        saveDisplayName: suspend (String) -> Unit,
    ) {
        withEnrollmentSamples(capture) { samples ->
            MetricKwsAudio.validate(samples, checkNotNull(engine.config))
            val transcript = try { recognize(samples) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                throw IllegalStateException("Не удалось распознать имя. Повторите запись слова.", error)
            }
            val name = AssistantDisplayName.fromTranscript(transcript)
            enroll(samples) { saveDisplayName(name) }
        }
    }

    private suspend fun withEnrollmentSamples(capture: suspend () -> FloatArray,
        action: suspend (FloatArray) -> Unit) {
        check(available) { unavailableReason ?: "Модель недоступна" }
        mutex.withLock { check(!closed); engine.prepare() }
        val samples = capture()
        try { action(samples) } finally { samples.fill(0f) }
    }

    /** One completed VAD segment; word enrollment is independent of speaker enrollment. */
    suspend fun enroll(samples: FloatArray) = enroll(samples) {}

    private suspend fun enroll(samples: FloatArray, onProfileSaved: suspend () -> Unit) = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!closed) { "Обработка ключевой фразы завершена" }
            val config = checkNotNull(engine.config) { engine.unavailableReason ?: "Модель недоступна" }
            MetricKwsAudio.validate(samples, config)
            val embedding = KeywordEmbedding.normalize(engine.embedding(samples))
            check(embedding.size == config.embeddingSize) { "Размер отпечатка не соответствует модели" }
            currentCoroutineContext().ensureActive()
            val previous = profiles.load()
            val revision = (previous?.profileRevision ?: 0) + 1
            // Close the previous command window before replacing the word. If settings fail, retain the old word.
            onProfileChanged()
            currentCoroutineContext().ensureActive()
            // Finish both writes once commit starts. A failed settings write restores the old word.
            withContext(NonCancellable) {
                profiles.save(MetricKwsProfile(config, revision, clock(), embedding))
                try { onProfileSaved() }
                catch (error: Exception) {
                    try { if (previous == null) profiles.delete() else profiles.save(previous) }
                    catch (restoreError: Exception) { error.addSuppressed(restoreError) }
                    throw error
                }
            }
        }
    }

    suspend fun evaluate(samples: FloatArray, mode: WakeWordEngine): MetricKwsDecision {
        if (mode == WakeWordEngine.LEGACY_ASR) return MetricKwsDecision.Legacy("legacy_selected")
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                if (closed) return@withLock MetricKwsDecision.Unavailable("closed")
                val config = engine.config ?: return@withLock MetricKwsDecision.Unavailable("model_unavailable")
                if (mode == WakeWordEngine.METRIC_KWS && !activationAllowed)
                    return@withLock MetricKwsDecision.Unavailable("validation_required")
                try {
                    val profile = profiles.load() ?: return@withLock MetricKwsDecision.Unavailable("profile_missing_or_corrupt")
                    profile.validate()
                    if (profile.config != config)
                        return@withLock MetricKwsDecision.Unavailable("reenrollment_required")
                    try { MetricKwsAudio.validate(samples, config) }
                    catch (_: IllegalArgumentException) { return@withLock MetricKwsDecision.Reject }
                    val candidate = engine.embedding(samples)
                    val score = KeywordEmbedding.cosine(profile.embedding, candidate)
                        ?: return@withLock MetricKwsDecision.Unavailable("invalid_embedding")
                    val matched = KeywordEmbedding.matches(score, config.keywordThreshold)
                    // Shadow never calls speaker enrollment, feedback, ASR or activation.
                    if (mode == WakeWordEngine.METRIC_KWS_SHADOW)
                        return@withLock MetricKwsDecision.Shadow(score, matched)
                    if (matched) MetricKwsDecision.Accept else MetricKwsDecision.Reject
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { MetricKwsDecision.Unavailable("runtime_error") }
                catch (_: LinkageError) { MetricKwsDecision.Unavailable("runtime_unavailable") }
            }
        }.also { diagnostic.value = it }
    }

    suspend fun status(prepareModel: Boolean = true): PersonalKeywordStatus = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed || !available) return@withLock PersonalKeywordStatus(false, false, false,
                unavailableReason ?: "Модель недоступна")
            try {
                if (prepareModel) engine.prepare()
                val profile = profiles.load()
                    ?: return@withLock PersonalKeywordStatus(true, false, false, "Персональное слово не записано")
                profile.validate()
                if (profile.config != engine.config)
                    return@withLock PersonalKeywordStatus(true, false, false, "Требуется перезапись персонального слова")
                PersonalKeywordStatus(true, true, activationAllowed,
                    if (activationAllowed) "Персональное слово сохранено; готово к активации"
                    else "Модель доступна только для проверки")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PersonalKeywordStatus(false, false, false, unavailableReason ?: "Не удалось проверить модель и профиль") }
            catch (_: LinkageError) { PersonalKeywordStatus(false, false, false, "Аудиобиблиотека недоступна; используйте активацию по имени") }
        }
    }

    suspend fun profileStatus(): String = status().message

    suspend fun delete() = mutex.withLock {
        onProfileChanged()
        profiles.delete()
    }
    suspend fun close() = mutex.withLock {
        if (!closed) { closed = true; engine.close() }
    }
}
