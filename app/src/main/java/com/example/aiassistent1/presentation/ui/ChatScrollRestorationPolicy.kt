package com.example.aiassistent1.presentation.ui

internal fun shouldAutoScrollToNewestMessage(
    hasRestoredPosition: Boolean,
    isAutoScrollEnabled: Boolean,
    restoredLastMessageId: String?,
    lastMessageId: String?,
): Boolean = hasRestoredPosition &&
    isAutoScrollEnabled &&
    lastMessageId != null &&
    lastMessageId != restoredLastMessageId
