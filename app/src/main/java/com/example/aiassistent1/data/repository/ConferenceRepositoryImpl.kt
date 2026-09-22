package com.example.aiassistent1.data.repository

import androidx.room.withTransaction
import com.example.aiassistent1.data.local.*
import com.example.aiassistent1.domain.model.TranscriptLimits

class ConferenceRepositoryImpl(private val database: ConferenceDatabase) {
    private val dao = database.conferenceDao()
    suspend fun createConference(conference: ConferenceEntity) = dao.insertConference(conference)
    suspend fun getConferenceById(id: Long) = dao.getConferenceById(id)
    fun observeConference(id: Long) = dao.observeConference(id)
    fun getAllConferences() = dao.getAllConferences()
    fun conferencesPaging(query: String) = dao.conferencesPaging(escape(query))
    fun getTranscriptLinesPaging(id: Long, query: String = ""): androidx.paging.PagingSource<Int, TranscriptLineEntity> {
        val terms = Regex("[\\p{L}\\p{N}]+").findAll(query.take(200)).map { "\"${it.value}*\"" }.toList()
        return if (terms.isEmpty()) dao.transcriptPaging(id, "") else dao.searchPaging(id, terms.joinToString(" AND "))
    }
    suspend fun readPage(id: Long, afterId: Long, limit: Int = 32) = dao.readPage(id, afterId, limit)
    suspend fun finish(id: Long, status: String, duration: Long, error: String?) = dao.finish(id, status, duration, error)
    suspend fun saveSummary(id: Long, summary: String) = dao.saveSummary(id, summary.take(16_000))
    suspend fun recoverInterrupted() = dao.recoverInterrupted()
    suspend fun updateProgress(id: Long, duration: Long) = dao.updateProgress(id, duration)
    suspend fun deleteConference(id: Long) = database.withTransaction {
        check(dao.getConferenceById(id)?.status != "recording") { "Сначала остановите запись" }
        dao.deleteConference(id)
    }
    /** Only transcript text is evicted. Recorded audio and its metadata are retained. */
    suspend fun addTranscriptLine(line: TranscriptLineEntity): Boolean = database.withTransaction {
        val bytes = TranscriptLimits.bytes(line.text)
        require(bytes <= TranscriptLimits.MAX_LINE_BYTES)
        if (dao.textBytes(line.conferenceId) + bytes > TranscriptLimits.PER_CONFERENCE) return@withTransaction false
        while (dao.totalTextBytes() + bytes > TranscriptLimits.GLOBAL) {
            val oldest = dao.oldestTranscript(line.conferenceId) ?: return@withTransaction false
            dao.clearTranscript(oldest)
            dao.markEvicted(oldest)
        }
        dao.insertTranscriptLine(line)
        dao.addTextBytes(line.conferenceId, bytes)
        true
    }
    private fun escape(query: String) = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
