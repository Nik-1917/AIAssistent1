package com.example.aiassistent1.data.provider

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.example.aiassistent1.domain.interfaces.CalendarProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

class AndroidCalendarProvider(
    private val context: Context
) : CalendarProvider {

    override suspend fun openCalendar(): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = CalendarContract.CONTENT_URI
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override suspend fun addEvent(
        title: String,
        dateTime: String,
        durationMin: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val calendarId = getDefaultCalendarId() ?: throw IllegalStateException("Календарь не найден")
            
            val startMillis: Long = LocalDateTime.parse(dateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            
            val endMillis = startMillis + (durationMin * 60 * 1000)

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                throw IllegalStateException("Не удалось добавить событие в календарь")
            }
        }
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null
        )

        return cursor?.use {
            if (it.moveToFirst()) {
                val idColumn = it.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryColumn = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val accountColumn = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val visibleColumn = it.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                
                var bestId: Long? = null
                
                do {
                    val id = it.getLong(idColumn)
                    val isPrimary = it.getInt(primaryColumn) == 1
                    val account = it.getString(accountColumn)
                    val visible = it.getInt(visibleColumn) == 1
                    
                    if (isPrimary && visible) return@use id
                    
                    if (bestId == null || (account?.contains("google", ignoreCase = true) == true && visible)) {
                        bestId = id
                    }
                } while (it.moveToNext())
                
                bestId
            } else null
        }
    }
}
