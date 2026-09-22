package com.example.aiassistent1.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ConferenceEntity::class, TranscriptLineEntity::class, TranscriptSearchEntity::class],
    version = 3,
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
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `transcript_search` USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`transcript_lines`)")
                db.execSQL("INSERT INTO transcript_search(transcript_search) VALUES ('rebuild')")
            }
        }
        // Version 2 was present in intermediate, uncommitted builds. Keep their recordings too.
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                val columns = mutableSetOf<String>()
                db.query("PRAGMA table_info(conferences)").use { cursor ->
                    while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                if ("transcriptBytes" !in columns) db.execSQL("ALTER TABLE conferences ADD COLUMN transcriptBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE conferences SET transcriptBytes = (SELECT COALESCE(SUM(length(CAST(text AS BLOB))), 0) FROM transcript_lines WHERE conferenceId = conferences.id)")
                db.execSQL("DROP TABLE IF EXISTS transcript_search")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `transcript_search` USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`transcript_lines`)")
                db.execSQL("INSERT INTO transcript_search(transcript_search) VALUES ('rebuild')")
            }
        }
    }
}
