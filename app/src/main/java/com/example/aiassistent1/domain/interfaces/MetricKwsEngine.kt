package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.MetricKwsConfig
import com.example.aiassistent1.domain.model.MetricKwsProfile

/** Offline utterance encoder. No microphone, ASR, feedback or settings ownership. */
interface MetricKwsEngine {
    val config: MetricKwsConfig?
    val unavailableReason: String?
    /** Field acceptance evidence, distinct from permission to opt into an experiment. */
    val activationValidated: Boolean
    /** Only a pinned, reviewed bundle may permit explicit experimental opt-in. */
    val experimentalActivationAllowed: Boolean get() = false
    suspend fun prepare() = Unit
    suspend fun embedding(samples: FloatArray): FloatArray
    suspend fun close()
}

data class PersonalKeywordStatus(
    val modelAvailable: Boolean,
    val profileReady: Boolean,
    val canActivate: Boolean,
    val message: String,
)

/** UI operations on the existing input provider; never creates another microphone. */
interface PersonalKeywordControls {
    fun observeActivationIssue(): kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.emptyFlow()
    fun observeKeywordRecording(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun keywordStatus(): PersonalKeywordStatus
    suspend fun enrollKeyword()
    suspend fun deleteKeyword()
    suspend fun selectActivationMode(mode: com.example.aiassistent1.domain.model.VoiceActivationMode)
}

data class VoiceProfileStatus(val profileReady: Boolean, val message: String)

interface VoiceProfileControls {
    fun observeVoiceRecording(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun voiceProfileStatus(): VoiceProfileStatus
    suspend fun enrollVoice()
    suspend fun deleteVoice()
    suspend fun selectVoiceId(enabled: Boolean)
}

interface MetricKwsProfileStore {
    suspend fun load(): MetricKwsProfile?
    suspend fun save(profile: MetricKwsProfile)
    suspend fun delete()
}
