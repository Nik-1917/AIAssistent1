package com.example.aiassistent1.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.aiassistent1.MainActivity
import com.example.aiassistent1.R
import kotlinx.coroutines.CompletableDeferred

/** Keeps user-started work alive across screen-off and activity background transitions. */
class GenerationForegroundService : Service() {
    private var cpuLock: PowerManager.WakeLock? = null
    private var proximityLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        try {
            updateForeground()
        } catch (error: Exception) {
            sessions.toList().forEach { it.ready.completeExceptionally(error) }
            sessions.clear()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.generation_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW),
        )
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (sessions.any { it.kind == Kind.Speech }) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (sessions.any { it.kind == Kind.Microphone }) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.generation_notification_title))
            .setContentText(getString(R.string.generation_notification_text))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true)
            .setSilent(true)
            .build()
        // Even a cancelled, queued startForegroundService request must be promoted first.
        startForeground(NOTIFICATION_ID, notification, types)
        if (sessions.isEmpty()) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            instance = null
            stopSelf()
            return
        }
        val power = getSystemService(PowerManager::class.java)
        if (cpuLock == null) {
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:assistantCpu")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (proximityLock == null && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "$packageName:assistantProximity")
                .apply { setReferenceCounted(false); acquire() }
        }
        sessions.forEach { it.ready.complete(Unit) }
    }

    private fun releaseLocks() {
        proximityLock?.let { if (it.isHeld) it.release() }
        proximityLock = null
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            sessions.forEach { it.ready.completeExceptionally(IllegalStateException("Фоновый сервис остановлен")) }
            sessions.clear()
        }
        releaseLocks()
        super.onDestroy()
    }

    enum class Kind { Generation, Speech, Microphone }

    class Session internal constructor(internal val kind: Kind) : AutoCloseable {
        internal val ready = CompletableDeferred<Unit>()
        suspend fun awaitReady() = ready.await()

        @MainThread
        override fun close() {
            if (sessions.remove(this)) {
                ready.cancel()
                instance?.updateForeground()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 1001
        private val sessions = linkedSetOf<Session>()
        private var instance: GenerationForegroundService? = null

        /** Call while visible for initial microphone access; retain the session across dialogue turns. */
        @MainThread
        fun acquire(context: Context, kind: Kind): Session {
            val session = Session(kind)
            sessions.add(session)
            try {
                val running = instance
                if (running != null) running.updateForeground()
                else ContextCompat.startForegroundService(context, Intent(context, GenerationForegroundService::class.java))
            } catch (error: Exception) {
                sessions.remove(session)
                session.ready.completeExceptionally(error)
                throw error
            }
            return session
        }
    }
}
