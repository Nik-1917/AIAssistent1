package com.example.aiassistent1.data.local

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConferenceDao {
    @Insert suspend fun insertConference(conference: ConferenceEntity): Long
    @Update suspend fun updateConference(conference: ConferenceEntity)
    @Insert suspend fun insertTranscriptLine(line: TranscriptLineEntity)
    @Query("SELECT * FROM conferences ORDER BY startTime DESC, id DESC")
    fun getAllConferences(): Flow<List<ConferenceEntity>>
    @Query("SELECT * FROM conferences WHERE title LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY startTime DESC, id DESC")
    fun conferencesPaging(query: String): PagingSource<Int, ConferenceEntity>
    @Query("SELECT * FROM conferences WHERE id = :id")
    suspend fun getConferenceById(id: Long): ConferenceEntity?
    @Query("SELECT * FROM conferences WHERE id = :id")
    fun observeConference(id: Long): Flow<ConferenceEntity?>
    @Query("SELECT * FROM transcript_lines WHERE conferenceId = :conferenceId AND text LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY timestamp, id")
    fun transcriptPaging(conferenceId: Long, query: String): PagingSource<Int, TranscriptLineEntity>
    @Query("SELECT t.* FROM transcript_lines t JOIN transcript_search ON t.id = transcript_search.rowid WHERE t.conferenceId = :id AND transcript_search MATCH :query ORDER BY t.timestamp, t.id")
    fun searchPaging(id: Long, query: String): PagingSource<Int, TranscriptLineEntity>
    @Query("SELECT * FROM transcript_lines WHERE conferenceId = :conferenceId AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun readPage(conferenceId: Long, afterId: Long, limit: Int): List<TranscriptLineEntity>
    @Query("SELECT transcriptBytes FROM conferences WHERE id = :id")
    suspend fun textBytes(id: Long): Long
    @Query("SELECT COALESCE(SUM(transcriptBytes), 0) FROM conferences")
    suspend fun totalTextBytes(): Long
    @Query("SELECT c.id FROM conferences c WHERE c.id != :activeId AND c.status != 'recording' AND EXISTS(SELECT 1 FROM transcript_lines t WHERE t.conferenceId = c.id) ORDER BY c.startTime, c.id LIMIT 1")
    suspend fun oldestTranscript(activeId: Long): Long?
    @Query("DELETE FROM transcript_lines WHERE conferenceId = :id")
    suspend fun clearTranscript(id: Long)
    @Query("UPDATE conferences SET transcriptEvicted = 1, summary = NULL, transcriptBytes = 0 WHERE id = :id")
    suspend fun markEvicted(id: Long)
    @Query("UPDATE conferences SET transcriptBytes = transcriptBytes + :bytes WHERE id = :id")
    suspend fun addTextBytes(id: Long, bytes: Long)
    @Query("UPDATE conferences SET duration = :duration WHERE id = :id AND status = 'recording'")
    suspend fun updateProgress(id: Long, duration: Long)
    @Query("UPDATE conferences SET status = :status, duration = :duration, error = :error WHERE id = :id")
    suspend fun finish(id: Long, status: String, duration: Long, error: String?)
    @Query("UPDATE conferences SET summary = :summary WHERE id = :id")
    suspend fun saveSummary(id: Long, summary: String)
    @Query("UPDATE conferences SET status = 'interrupted', error = 'Запись прервана завершением приложения' WHERE status = 'recording'")
    suspend fun recoverInterrupted()
    @Query("DELETE FROM conferences WHERE id = :id")
    suspend fun deleteConference(id: Long)
}
