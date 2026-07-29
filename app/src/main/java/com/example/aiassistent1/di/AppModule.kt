package com.example.aiassistent1.di

import android.content.Context
import androidx.room.Room
import com.example.aiassistent1.data.local.ChatDatabase
import com.example.aiassistent1.data.repository.RoomChatRepository
import com.example.aiassistent1.domain.interfaces.ChatRepository

object AppModule {
	fun provideChatRepository(context: Context): ChatRepository = RoomChatRepository(
		Room.databaseBuilder(
			context.applicationContext,
			ChatDatabase::class.java,
			"ai_assistant.db",
		).build().chatMessageDao(),
	)
}