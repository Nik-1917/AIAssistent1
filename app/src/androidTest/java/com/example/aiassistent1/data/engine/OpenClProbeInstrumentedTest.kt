package com.example.aiassistent1.data.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.domain.context.ModelContextBuilder
import com.example.aiassistent1.domain.model.CalendarAddParams
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.parser.AssistantResponseParser
import com.example.aiassistent1.domain.provider.SystemPromptProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4::class)
class OpenClProbeInstrumentedTest {
    @Test fun runDiagnostic() {
        val args = InstrumentationRegistry.getArguments()
        val operation = args.getString("openclProbe")
        assumeTrue("Explicit diagnostic mode required", operation != null)
        require(operation in setOf("driver", "cpu", "opencl"))
        val run = requireNotNull(args.getString("run"))
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,48}")))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bound = CompletableFuture<ILlamaWorker>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bound.complete(ILlamaWorker.Stub.asInterface(service))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val intent = Intent().setClassName(ctx, "${ctx.packageName}.data.engine.OpenClProbeService")
        if (!ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            // MIUI can throttle background starts of :llm after the preceding instrumentation force-stop.
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 " +
                    "-n ${ctx.packageName}/androidx.activity.ComponentActivity")
            ParcelFileDescriptor.AutoCloseInputStream(shell).bufferedReader().use { it.readText() }
            ctx.startActivity(Intent().setClassName(ctx, "android.app.Activity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var connected = false
            while (!connected && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
                connected = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
            assertTrue("Unable to start the diagnostic :llm process", connected)
        }
        val owner = UUID.randomUUID().toString()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = bound.get(20, TimeUnit.SECONDS)
            val calendar = args.getString("scenario") == "calendar"
            val system = SystemPromptProvider().getSystemPrompt(calendar, LocalDateTime.of(2026, 11, 18, 12, 0))
            val user = ChatMessage(role = MessageRole.USER,
                content = if (calendar) "Создай событие с названием Встреча" else "Привет")
            val userContent = ModelContextBuilder().build(listOf(user),
                appendChatStyleInstruction = !calendar, isCalendarMode = calendar).single().content
            val input = Bundle().apply {
                putString("run", run); putString("model", args.getString("gpuModel"))
                putString("scenario", if (calendar) "calendar" else "chat")
                putString("prompt", "<|im_start|>system\n$system\n<|im_end|>\n<|im_start|>user\n$userContent\n<|im_end|>\n<|im_start|>assistant\n")
                putInt("layers", args.getString("gpuLayers")?.toInt() ?: if (operation == "opencl") 1 else 0)
                putInt("limit", args.getString("tokenLimit")?.toInt() ?: 160)
                putBoolean("warmCompare", args.getString("warmCompare") == "true")
            }
            val call = executor.submit<Bundle> { worker.execute(owner, operation, input, null) }
            val result = try {
                JSONObject(call.get(300, TimeUnit.SECONDS).getString("report")!!)
            } catch (failure: ExecutionException) {
                if (failure.cause !is RemoteException) throw failure
                recordInterruption(ctx, run, owner, "WORKER_DIED", failure.cause.toString())
            } catch (failure: TimeoutException) {
                runCatching { worker.abort(owner) }
                recordInterruption(ctx, run, owner, "TIMEOUT", "300 s")
            }
            assertEquals("${ctx.packageName}:llm", result.getString("process"))
            if (operation == "driver" && args.getString("expectHidden") == "true") {
                assertEquals("FAILED", result.getString("status"))
                assertEquals("driver.dlopen", result.getString("stage"))
                assertTrue(result.getString("error").contains("libOpenCL.so"))
                return
            }
            assertEquals(result.toString(), "PASS", result.getString("status"))
            if (operation == "driver") assertEquals(16, result.getInt("verifiedValues"))
            else {
                assertTrue(result.getString("reply").isNotBlank())
                if (operation == "opencl") {
                    val totalLayers = result.getInt("totalLayers")
                    assertTrue(totalLayers > 0)
                    assertEquals(minOf(input.getInt("layers"), totalLayers), result.getInt("offloadedLayers"))
                    assertTrue(result.getString("prefillMatrixKernel").isNotBlank())
                    assertTrue(result.getString("decodeMatrixKernel").isNotBlank())
                } else assertEquals(0, result.getInt("offloadedLayers"))
                if (calendar) {
                    assertEquals("EOS", result.getString("stopReason"))
                    assertCalendarReply(result.getString("reply"))
                    if (input.getBoolean("warmCompare")) {
                        assertEquals("EOS", result.getString("warmStopReason"))
                        assertCalendarReply(result.optString("warmReply", result.getString("reply")))
                    }
                } else assertTrue(AssistantResponseParser().parseChatReply(result.getString("reply")).getOrThrow().isNotBlank())
            }
        } finally {
            executor.shutdownNow()
            ctx.unbindService(connection)
        }
    }

    private fun assertCalendarReply(reply: String) {
        val command = AssistantResponseParser().parseResult(reply).getOrThrow()
        assertEquals("calendar_add", command.intent)
        assertEquals("Встреча", (command.params as CalendarAddParams).title)
    }

    private fun recordInterruption(ctx: Context, run: String, owner: String, status: String, error: String): JSONObject {
        val file = AtomicFile(File(ctx.cacheDir, "opencl-probe-$run.json"))
        val saved = runCatching { JSONObject(file.readFully().toString(Charsets.UTF_8)) }.getOrNull()
            ?.takeIf { it.optString("invocation") == owner } ?: JSONObject().put("run", run).put("invocation", owner)
        saved.put("status", status).put("error", error)
        val out = file.startWrite()
        try { out.write(saved.toString(2).toByteArray()); file.finishWrite(out) }
        catch (failure: Throwable) { file.failWrite(out); throw failure }
        return saved
    }
}
