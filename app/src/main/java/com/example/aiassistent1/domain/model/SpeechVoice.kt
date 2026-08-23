package com.example.aiassistent1.domain.model

enum class SpeechVoice(val storageId: String, val title: String) {
    IRINA("irina", "Ирина"),
    DENIS("denis", "Денис"),
    DMITRI("dmitri", "Дмитрий");

    companion object {
        fun fromStorageId(value: String?): SpeechVoice =
            entries.firstOrNull { it.storageId == value } ?: IRINA
    }
}
