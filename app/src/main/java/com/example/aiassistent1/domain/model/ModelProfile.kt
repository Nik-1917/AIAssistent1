package com.example.aiassistent1.domain.model

enum class ModelProfile(val storageKey: String) {
    CALENDAR("calendar"),
    CHAT("chat");

    val defaults: GenerationParams
        get() = when (this) {
            CALENDAR -> GenerationParams(temperature = 0.07f, batchSize = 64)
            CHAT -> GenerationParams(contextSize = 2048, maxTokens = 512, temperature = 0.70f, topP = 0.80f,
                batchSize = 128, contextResponseRatio = ContextResponseRatio.FOUR_TO_ONE)
        }

    companion object {
        fun forCalendarMode(isCalendarMode: Boolean): ModelProfile = if (isCalendarMode) CALENDAR else CHAT
    }
}

data class ModelParameterProfiles(
    val calendar: GenerationParams = ModelProfile.CALENDAR.defaults,
    val chat: GenerationParams = ModelProfile.CHAT.defaults,
) {
    operator fun get(profile: ModelProfile): GenerationParams = when (profile) {
        ModelProfile.CALENDAR -> calendar
        ModelProfile.CHAT -> chat
    }
}
