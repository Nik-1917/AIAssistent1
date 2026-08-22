package com.example.aiassistent1.presentation.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollRestorationPolicyTest {
    @Test
    fun `does not scroll to newest message while restoring saved position`() {
        assertFalse(
            shouldAutoScrollToNewestMessage(
                hasRestoredPosition = true,
                isAutoScrollEnabled = true,
                restoredLastMessageId = "last-message",
                lastMessageId = "last-message",
            ),
        )
    }

    @Test
    fun `scrolls to a message that arrives after restoration`() {
        assertTrue(
            shouldAutoScrollToNewestMessage(
                hasRestoredPosition = true,
                isAutoScrollEnabled = true,
                restoredLastMessageId = "last-message",
                lastMessageId = "new-message",
            ),
        )
    }
}
