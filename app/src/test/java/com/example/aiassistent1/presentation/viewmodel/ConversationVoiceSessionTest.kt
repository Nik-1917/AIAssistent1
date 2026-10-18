package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.AppNavigationState
import com.example.aiassistent1.domain.model.VoiceActivationMode
import org.junit.Assert.*
import org.junit.Test

class ConversationVoiceSessionTest {
    private val calendarChat = AppNavigationState(AppDestination.CHAT, true)
    private val calendar = AppNavigationState(AppDestination.CALENDAR, true)
    private val general = AppNavigationState(AppDestination.CHAT, false)

    @Test fun calendarPageAndCalendarConversationKeepTheSameVoiceSessionInEveryActivationMode() {
        for (mode in VoiceActivationMode.entries) {
            val session = ConversationVoiceSession()
            assertFalse(session.moveTo(calendarChat.chatId))
            val capture = session.ticket(calendarChat.chatId)
            for (page in listOf(calendar, calendarChat, calendar, calendarChat)) {
                assertFalse("$mode: same conversation must not end capture", session.moveTo(page.chatId))
                assertTrue("$mode: keep input and the activation window", session.accepts(capture, page.chatId))
            }
        }
    }

    @Test fun crossingBetweenGeneralAndEitherCalendarPageRevokesOldInputAndPlayback() {
        for ((from, to) in listOf(general to calendarChat, general to calendar,
            calendarChat to general, calendar to general)) {
            val session = ConversationVoiceSession()
            session.moveTo(from.chatId)
            val input = session.ticket(from.chatId)
            val pendingReply = session.ticket(from.chatId)
            assertTrue(session.moveTo(to.chatId))
            assertFalse(session.accepts(input, to.chatId))
            assertFalse(session.accepts(pendingReply, to.chatId))
            assertNull(session.ticket(from.chatId))
            assertNotNull(session.ticket(to.chatId))
        }
    }

    @Test fun leavingAndReturningCannotReviveDelayedAsrOrAGenerationReply() {
        for (from in listOf(general, calendarChat)) {
            val session = ConversationVoiceSession()
            session.moveTo(from.chatId)
            val delayed = session.ticket(from.chatId)
            session.moveTo(if (from.isCalendarMode) general.chatId else calendarChat.chatId)
            session.moveTo(from.chatId)
            assertFalse(session.accepts(delayed, from.chatId))
            assertTrue(session.accepts(session.ticket(from.chatId), from.chatId))
        }
    }

    @Test fun staleVisiblePageCannotStartCaptureDuringACrossModeTransition() {
        val session = ConversationVoiceSession()
        session.moveTo(general.chatId)
        session.moveTo(calendar.chatId)
        assertNull(session.ticket(general.chatId))
        assertFalse(session.accepts(null, calendar.chatId))
    }

    @Test fun legacyCalendarPageWithChatFlagStillUsesCalendarCommands() {
        assertEquals(calendar, AppNavigationState(AppDestination.CALENDAR, false).normalized())
        assertEquals(general, general.normalized())
        assertEquals(calendarChat, calendarChat.normalized())
    }
}
