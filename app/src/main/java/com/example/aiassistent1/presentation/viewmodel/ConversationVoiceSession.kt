package com.example.aiassistent1.presentation.viewmodel

/** A page change within one conversation keeps its ticket, including an open activation window. */
internal class ConversationVoiceSession {
    data class Ticket(val chatId: String, internal val revision: Long)

    var chatId: String? = null
        private set
    private var revision = 0L

    /** Returns true only when leaving an already established conversation. */
    fun moveTo(chatId: String): Boolean {
        if (this.chatId == chatId) return false
        val leavingConversation = this.chatId != null
        this.chatId = chatId
        revision++
        return leavingConversation
    }

    fun ticket(chatId: String): Ticket? = if (this.chatId == chatId) Ticket(chatId, revision) else null

    /** Clearing history ends old async input/replies without leaving the selected conversation. */
    fun restart(chatId: String): Ticket? {
        if (this.chatId != chatId) return null
        revision++
        return ticket(chatId)
    }

    fun accepts(ticket: Ticket?, chatId: String): Boolean = ticket != null && ticket == this.ticket(chatId)
}

data class VoiceModeNotice(val id: Long, val text: String)
