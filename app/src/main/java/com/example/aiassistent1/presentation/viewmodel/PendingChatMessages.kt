package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.ChatMessage

/** Main-dispatcher state that keeps live text until Room has observed the terminal version. */
internal class PendingChatMessages {
    private data class Entry(val message: ChatMessage, val isStreaming: Boolean)
    private val entries = linkedMapOf<String, Entry>()

    fun put(message: ChatMessage, isStreaming: Boolean = false) {
        entries[message.id] = Entry(message, isStreaming)
    }

    fun find(id: String): ChatMessage? = entries[id]?.message

    fun remove(id: String) {
        entries.remove(id)
    }

    fun clear(chatId: String) {
        entries.entries.removeAll { it.value.message.chatId == chatId }
    }

    fun merge(chatId: String, persisted: List<ChatMessage>): List<ChatMessage> {
        persisted.forEach { message ->
            val pending = entries[message.id]
            if (pending != null && !pending.isStreaming && pending.message == message) {
                entries.remove(message.id)
            }
        }
        val local = entries.values.filter { it.message.chatId == chatId }.associateBy { it.message.id }
        val persistedIds = persisted.mapTo(mutableSetOf()) { it.id }
        return (persisted.map { local[it.id]?.message ?: it } +
            local.values.filterNot { it.message.id in persistedIds }.map { it.message })
            .sortedBy { it.createdAtEpochMillis }
    }
}
