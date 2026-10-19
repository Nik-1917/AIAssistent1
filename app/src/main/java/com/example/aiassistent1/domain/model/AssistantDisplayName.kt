package com.example.aiassistent1.domain.model

/** Display-name validation. NAME mode additionally validates and atomically updates the call name. */
object AssistantDisplayName {
    const val DEFAULT = "AI Assistant"
    const val MAX_LENGTH = 40

    fun normalize(value: String): String {
        val name = value.trim().replace(Regex("\\s+"), " ")
        require(name.length <= MAX_LENGTH && name.all { it.isLetterOrDigit() || it in " -'’" }) {
            "Имя должно содержать не более 40 букв, цифр, пробелов, дефисов или апострофов"
        }
        return name.ifEmpty { DEFAULT }
    }

    fun fromTranscript(transcript: String): String {
        val name = transcript.trim { it.isWhitespace() || it in ".,!?;:…«»\"" }
        require(name.isNotBlank()) { "Не удалось распознать имя. Повторите запись слова." }
        return normalize(name)
    }
}
