package com.example.aiassistent1.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import com.example.aiassistent1.MainActivity
import com.example.aiassistent1.R
import com.example.aiassistent1.data.provider.RecordingSampleClock
import com.example.aiassistent1.di.AppModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Reads the permitted playback mix only while a software-AEC microphone session needs it. */
class PlaybackReferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { AppModule.providePlaybackCaptureController(this) }
    private val processing by lazy { AppModule.provideAudioProcessingManager(this) }
    private var projection: MediaProjection? = null
    private var referenceJob: Job? = null
    private var sessionId = 0L
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Обработка звука динамика", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Подавление звука других приложений")
            .setContentText("Звук обрабатывается в памяти, без сохранения записи").setContentIntent(open)
            .setOngoing(true).setSilent(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (error: Exception) {
            controller.finish(intent?.getLongExtra(SESSION, 0) ?: 0, "Android не разрешил запустить обработку звука: ${error.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val id = intent?.getLongExtra(SESSION, 0) ?: 0
        if (!controller.isRequested(id)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (projection != null && sessionId == id) {
            // A repeated start for one session never consumes its token a second time.
            controller.connected(id)
            return START_NOT_STICKY
        }
        val previousJob = referenceJob
        previousJob?.cancel()
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null
        sessionId = id
        referenceJob = scope.launch { try {
            previousJob?.join()
            if (!controller.isRequested(id)) { stopSelf(startId); return@launch }
            val data = requireNotNull(intent?.let { IntentCompat.getParcelableExtra(it, RESULT_DATA, Intent::class.java) })
            val active = requireNotNull(getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(RESULT_CODE, 0), data)) { "Android не выдал доступ к звуку" }
            projection = active
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (sessionId == id && projection === active) {
                        controller.finish(id, "Android остановил доступ к звуку других приложений. Для включения нужно новое разрешение.")
                        stopSelf()
                    }
                }
            }
            projectionCallback = callback
            active.registerCallback(callback, Handler(Looper.getMainLooper()))
            controller.connected(id)
            processing.referenceNeeded.collectLatest { needed -> if (needed) captureReference(active) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            controller.finish(id, "Не удалось подключить звук других приложений: ${error.message}")
            stopSelf(startId)
        } }
        return START_NOT_STICKY
    }

    private suspend fun captureReference(active: MediaProjection) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(this@PlaybackReferenceService, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Нет разрешения на запись аудио")
        val configuration = AudioPlaybackCaptureConfiguration.Builder(active)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Захват звука 16 кГц недоступен" }
        val recorder = AudioRecord.Builder().setAudioPlaybackCaptureConfig(configuration)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 4096)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            val pcm = ShortArray(160)
            val clock = RecordingSampleClock()
            while (currentCoroutineContext().isActive) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Ошибка захвата звука: $count" }
                if (count == 0) { delay(5); continue }
                processing.onOtherAppSamples(FloatArray(count) { pcm[it] / 32768f }, clock.firstSampleTimeNs(recorder, count))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            processing.clearOtherApps()
        }
    }

    override fun onDestroy() {
        referenceJob?.cancel()
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null
        scope.cancel()
        processing.clearOtherApps()
        controller.finish(sessionId)
        super.onDestroy()
    }

    companion object {
        const val SESSION = "session"
        const val RESULT_CODE = "result_code"
        const val RESULT_DATA = "result_data"
        private const val CHANNEL = "playback_reference"
        private const val NOTIFICATION = 1004
    }
}
