package com.example.aiassistent1.data.engine

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.presentation.viewmodel.ChatReplyTextStream
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in physical-device check; does not change saved chats, calendar or preferences. */
@RunWith(AndroidJUnit4::class)
class ChatReplyNativeInstrumentedTest {
    @Test fun realChessReplyAndNativeCancellationOnFifthRepeat() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("chatSafetyModel")
        assumeTrue("Pass chatSafetyModel filename", !name.isNullOrBlank())
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(requireNotNull(app.getExternalFilesDir("models")), requireNotNull(name))
        assertTrue(file.isFile)
        val engine = LlamatikEngine(object : ModelProvider {
            override suspend fun getModelPath() = Result.success(file.path)
        }, DeviceModelMemoryGuard.forAndroid(app), ModelProfile.CHAT.defaults.copy(
            cpuThreadsAuto = false, cpuThreads = 4))
        val report = JSONObject().put("model", name)
        try {
            // Exact historical request and preceding reply from the diagnosed device session.
            val messages = listOf(
                ChatMessage(role = MessageRole.SYSTEM, content = "cегодня 2026-10-18 01:01 день недели воскресенье"),
                ChatMessage(role = MessageRole.USER, content = "расскажи какие ходы бывают в шахматах\n\nотвечай очень вежливо используй эмодзи"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Хорошо! Ход в шахмате может включать несколько вариантов, в зависимости от текущей фигуры и стадии игры. Например, если текущий ход является калькуляцией, то можно рассмотреть три варианта: уступка в одном ходе, уступка в двух ходах или уступка в трех ходах. Если ход является фактом, то обычно два хода для решения и два хода для подтверждения."),
                ChatMessage(role = MessageRole.USER, content = "а король как ходит\n\nотвечай очень вежливо используй эмодзи"),
            )
            var stopped = false
            var finished = false
            var visibleBeforeEnd = false
            val raw = StringBuilder()
            val started = SystemClock.elapsedRealtime()
            var firstVisibleMillis: Long? = null
            val source = engine.generateForTask(messages, GenerationTask.CHAT)
                .onEach { raw.append(it) }.onCompletion { finished = true }
            val snapshots = withTimeout(180_000) {
                ChatReplyTextStream(AssistantResponseParser()).snapshots(source, onRepetition = {
                    stopped = true; engine.cancelGeneration()
                }).onEach {
                    if (firstVisibleMillis == null) firstVisibleMillis = SystemClock.elapsedRealtime() - started
                    if (!finished) visibleBeforeEnd = true
                }.toList()
            }
            assertTrue(snapshots.isNotEmpty())
            assertTrue(snapshots.last().isNotBlank())
            assertFalse(snapshots.last().startsWith("{"))
            assertFalse(snapshots.last().contains("\"response\":"))
            assertTrue("Text should appear while the native source is still open", visibleBeforeEnd)
            report.put("realReply", snapshots.last()).put("rawPrefix", raw.take(600).toString())
                .put("rawCharacters", raw.length).put("repetitionStopped", stopped)
                .put("firstVisibleMillis", firstVisibleMillis)
                .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                .put("visibleBeforeEnd", visibleBeforeEnd)
            File(app.cacheDir, "chat-reply-safety-device.json").writeText(report.toString(2))

            // Deterministic integration: inject five symbols at the output of a real native producer.
            // The guard must stop that producer, not merely stop rendering or wait for its EOS.
            var callbackCount = 0
            var producerJoined = false
            var nativeChunks = 0
            val cancellationStarted = SystemClock.elapsedRealtime()
            val live = engine.generateForTask(messages, GenerationTask.CHAT).map {
                nativeChunks++
                "{\"unknown\":\"Проверка остановки\",\"emote\":\"✿✿✿✿✿лишнее"
            }.onCompletion { producerJoined = true }
            val guarded = withTimeout(180_000) {
                ChatReplyTextStream(AssistantResponseParser()).snapshots(live, onRepetition = {
                    callbackCount++; engine.cancelGeneration()
                }).toList()
            }
            assertEquals("Проверка остановки", guarded.last())
            assertEquals(1, callbackCount)
            assertEquals(1, nativeChunks)
            assertTrue(producerJoined)
            assertEquals(ModelState.Ready, engine.state.value)
            report.put("nativeCancellationCallbackCount", callbackCount)
                .put("nativeCancellationProducerJoined", producerJoined)
                .put("nativeCancellationChunksConsumed", nativeChunks)
                .put("nativeCancellationElapsedMillis", SystemClock.elapsedRealtime() - cancellationStarted)
            File(app.cacheDir, "chat-reply-safety-device.json").writeText(report.toString(2))
        } finally { engine.close() }
    }
}
