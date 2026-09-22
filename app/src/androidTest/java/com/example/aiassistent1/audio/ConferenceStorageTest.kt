package com.example.aiassistent1.audio

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.paging.PagingSource
import com.example.aiassistent1.data.local.*
import com.example.aiassistent1.data.repository.ConferenceRepositoryImpl
import com.example.aiassistent1.domain.model.TranscriptLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConferenceStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun limitsEvictOnlyOldTranscriptAndPagingNeverLoadsWholeCollection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ConferenceDatabase::class.java).build()
        val audio = File(context.cacheDir, "retained-audio.mp3").apply { writeText("fixture") }
        try {
            val dao = db.conferenceDao()
            val repository = ConferenceRepositoryImpl(db)
            val text = "я".repeat(8192) // 16 KiB UTF-8 per row.
            var oldestId = 0L
            db.withTransaction {
                repeat(20) { index ->
                    val id = dao.insertConference(ConferenceEntity(title = "Old $index", filePath = audio.absolutePath,
                        startTime = index.toLong(), status = "complete", transcriptBytes = TranscriptLimits.PER_CONFERENCE))
                    if (index == 0) oldestId = id
                    repeat(320) { line -> dao.insertTranscriptLine(TranscriptLineEntity(conferenceId = id, text = text, timestamp = line * 1000L)) }
                }
            }
            assertEquals(TranscriptLimits.GLOBAL, dao.totalTextBytes())
            val active = repository.createConference(ConferenceEntity(title = "Active", filePath = audio.absolutePath, startTime = 100))
            assertTrue(repository.addTranscriptLine(TranscriptLineEntity(conferenceId = active, text = "Решение принято", timestamp = 1500)))
            assertTrue(audio.exists())
            assertTrue(repository.getConferenceById(oldestId)!!.transcriptEvicted)
            assertEquals(0L, dao.textBytes(oldestId))
            val source = repository.getTranscriptLinesPaging(active, "решение")
            val result = source.load(PagingSource.LoadParams.Refresh(null, 40, false))
            assertTrue(result is PagingSource.LoadResult.Page)
            assertEquals(1, (result as PagingSource.LoadResult.Page).data.size)
            assertEquals(1500L, result.data.single().timestamp)
            // Fill exactly 5 MiB, then ensure one more byte is rejected.
            val limitId = dao.insertConference(ConferenceEntity(title = "Limit", filePath = "", startTime = 101))
            db.withTransaction {
                repeat(320) { dao.insertTranscriptLine(TranscriptLineEntity(conferenceId = limitId, text = text, timestamp = it.toLong())) }
                dao.addTextBytes(limitId, TranscriptLimits.PER_CONFERENCE)
            }
            assertFalse(repository.addTranscriptLine(TranscriptLineEntity(conferenceId = limitId, text = "x", timestamp = 321)))
        } finally { db.close(); audio.delete() }
    }

    @Test fun migrationPreservesPrototypeDataAndBuildsSearchIndex() = runBlocking {
        val name = "audio-migration-test.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                old.execSQL("CREATE TABLE conferences(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, filePath TEXT NOT NULL, startTime INTEGER NOT NULL, duration INTEGER NOT NULL, summary TEXT)")
                old.execSQL("CREATE TABLE transcript_lines(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conferenceId INTEGER NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL, speaker TEXT, FOREIGN KEY(conferenceId) REFERENCES conferences(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_transcript_lines_conferenceId ON transcript_lines(conferenceId)")
                old.execSQL("INSERT INTO conferences VALUES(1,'Старая запись','/audio.mp3',1,2000,NULL)")
                old.execSQL("INSERT INTO transcript_lines VALUES(1,1,'Привет',1000,NULL)")
                old.version = 1
            }
            val migrated = Room.databaseBuilder(context, ConferenceDatabase::class.java, name)
                .addMigrations(ConferenceDatabase.MIGRATION_1_2).build()
            try {
                assertEquals(12L, migrated.conferenceDao().textBytes(1))
                val r = ConferenceRepositoryImpl(migrated)
                val result = r.getTranscriptLinesPaging(1, "Прив").load(PagingSource.LoadParams.Refresh(null, 10, false))
                assertEquals("Привет", (result as PagingSource.LoadResult.Page).data.single().text)
                assertEquals("interrupted", r.getConferenceById(1)!!.status)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
