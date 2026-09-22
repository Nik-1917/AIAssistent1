package com.example.aiassistent1.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ConferenceEntity::class, TranscriptLineEntity::class, TranscriptSearchEntity::class],
    version = 2,
    exportSchema = true
)
abstract class ConferenceDatabase : RoomDatabase() {
    abstract fun conferenceDao(): ConferenceDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conferences ADD COLUMN status TEXT NOT NULL DEFAULT 'interrupted'")
                db.execSQL("ALTER TABLE conferences ADD COLUMN error TEXT")
                db.execSQL("ALTER TABLE conferences ADD COLUMN transcriptEvicted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conferences ADD COLUMN transcriptBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE conferences SET transcriptBytes = (SELECT COALESCE(SUM(length(CAST(text AS BLOB))), 0) FROM transcript_lines WHERE conferenceId = conferences.id)")
                db.execSQL("DROP INDEX IF EXISTS index_transcript_lines_conferenceId")
                db.execSQL("CREATE INDEX index_transcript_lines_conferenceId_timestamp_id ON transcript_lines(conferenceId, timestamp, id)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS transcript_search USING FTS4(text TEXT NOT NULL, content='transcript_lines', tokenize=unicode61)")
                db.execSQL("INSERT INTO transcript_search(transcript_search) VALUES ('rebuild')")
            }
        }
    }
}
