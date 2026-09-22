package com.example.aiassistent1.data.local

import androidx.room.*

@Fts4(contentEntity = TranscriptLineEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "transcript_search")
data class TranscriptSearchEntity(val text: String)
