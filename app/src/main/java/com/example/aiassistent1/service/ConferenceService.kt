package com.example.aiassistent1.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.aiassistent1.MainActivity
import com.example.aiassistent1.R
import com.example.aiassistent1.di.AppModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lock: PowerManager.WakeLock? = null
    private var observer: Job? = null
    private val manager by lazy { AppModule.provideConferenceManager(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            manager.stopConference()
            if (observer?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Запись конференций", NotificationManager.IMPORTANCE_LOW))
            startForeground(ID, notification("Подготовка микрофона"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (observer?.isActive == true) return START_NOT_STICKY
            lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:conference")
                .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L + 180_000) }
            manager.startConference(intent?.getStringExtra("title") ?: "Конференция")
            observer = scope.launch {
                manager.state.map { Triple(it.recording, it.finishing, it.elapsedMs / 1000) }.distinctUntilChanged().collect { (recording, finishing, seconds) ->
                    if (!recording && !finishing) {
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    } else {
                        val text = if (finishing) "Сохранение записи…" else "Запись • ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                        getSystemService(NotificationManager::class.java).notify(ID, notification(text))
                    }
                }
            }
        } catch (error: Exception) {
            manager.reportServiceFailure(error.message ?: "Не удалось запустить службу записи")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Конференция").setContentText(text)
        .setOngoing(true).setSilent(true)
        .setContentIntent(PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Остановить", PendingIntent.getService(this, 31, Intent(this, ConferenceService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)).build()
    override fun onDestroy() {
        manager.stopConference()
        observer?.cancel()
        lock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "conferences"
        private const val ID = 1002
        private const val STOP = "conference.stop"
        fun start(context: Context, title: String) {
            ContextCompat.startForegroundService(context, Intent(context, ConferenceService::class.java).putExtra("title", title))
        }
    }
}
