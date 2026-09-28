package com.example.aiassistent1.domain.interfaces

import com.example.aiassistent1.domain.model.CalendarDraftRecord

interface CalendarDraftRepository {
    suspend fun load(): List<CalendarDraftRecord>
    suspend fun save(drafts: List<CalendarDraftRecord>)
}
