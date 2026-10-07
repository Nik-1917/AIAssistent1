package com.example.aiassistent1.data.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.data.model.GgufMetadataReader
import com.example.aiassistent1.data.model.ModelMemoryGuard
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.DeviceContextLimit
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.GenerationTask
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelAllocationException
import com.example.aiassistent1.domain.model.ModelProfile
import com.llamatik.library.platform.GenStream
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production RAM checks on the imported GGUF; never loads weights or writes user data. */
@RunWith(AndroidJUnit4::class)
class RequestPreflightInstrumentedTest {
    @Test fun firstAndRepeatedRefusalsNeverReachTheNativeRuntime() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = InstrumentationRegistry.getArguments().getString("preflightModel")
        assumeTrue("Pass preflightModel filename", !name.isNullOrBlank())
        val file = requireNotNull(GgufMetadataReader.resolveInDirectory(
            requireNotNull(context.getExternalFilesDir("models")), requireNotNull(name)))
        assertTrue(file.isFile)
        val report = JSONObject().put("model", name).put("fileBytes", file.length())
            .put("scope", "engine availability/direct flow, no ViewModel or UI latency")
            .put("cases", JSONArray())
        val real = DeviceModelMemoryGuard.forAndroid(context)
        var lastAssessment: DeviceContextLimit? = null
        val guard = object : ModelMemoryGuard by real {
            override fun assess(file: File, params: GenerationParams): DeviceContextLimit =
                real.assess(file, params).also { lastAssessment = it }
        }
        val runtime = ForbiddenRuntime()
        val provider = object : ModelProvider { override suspend fun getModelPath() = Result.success(file.path) }
        val engine = LlamatikEngine(provider, ModelProfile.CHAT.defaults,
            Executors.newSingleThreadExecutor(), runtime, memoryGuard = guard)
        val messages = listOf(ChatMessage(role = MessageRole.USER, content = "Ответь одним словом: готово."))
        var readyCalls = 0
        suspend fun refusal(label: String, task: GenerationTask, direct: Boolean) {
            val started = System.nanoTime()
            val error = try {
                if (direct) engine.generateForTask(messages, task) { readyCalls++ }.collect {}
                else engine.checkModelAvailability(task)
                null
            } catch (error: ModelAllocationException) { error }
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            val row = JSONObject().put("case", label).put("task", task.name).put("milliseconds", elapsed)
                .put("failure", error?.reason?.name ?: JSONObject.NULL)
                .put("requiredContext", error?.requiredContext ?: JSONObject.NULL)
            lastAssessment?.let {
                row.put("assessment", it.status.name).put("availableBytes", it.availableBytes)
                    .put("reserveBytes", it.reserveBytes).put("maximumContext", it.maximumContext)
            }
            report.getJSONArray("cases").put(row)
            assumeTrue("Current RAM assessment permits this model; no native probe is authorized here", error != null)
            assertNull(error!!.requiredContext)
            assertEquals(0, readyCalls)
            assertEquals(0, runtime.calls)
        }
        try {
            // Do not read metadata or prepare the tokenizer before the first measured check.
            refusal("first availability check", GenerationTask.CHAT, false)
            refusal("repeated availability check", GenerationTask.CHAT, false)
            refusal("direct chat request", GenerationTask.CHAT, true)
            refusal("direct calendar request", GenerationTask.CALENDAR, true)
            report.put("readyCalls", readyCalls).put("nativeCalls", runtime.calls).put("status", "PASSED")
        } catch (error: org.junit.AssumptionViolatedException) {
            report.put("status", "SKIPPED").put("error", error.toString())
            throw error
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            throw error
        } finally {
            engine.close()
            File(context.cacheDir, "request-preflight-device.json").writeText(report.toString(2))
            Log.i("RequestPreflightTest", report.toString())
        }
    }

    private class ForbiddenRuntime : LlamaRuntime {
        var calls = 0
        private fun unexpected(): Nothing { calls++; error("Early refusal reached the native runtime") }
        override fun beginRequest() = unexpected()
        override fun prepareTokenizer(path: String) = unexpected()
        override fun countTokens(prompt: String): Int = unexpected()
        override fun updateParams(params: GenerationParams, threads: Int, batchSize: Int) = unexpected()
        override fun load(path: String): Boolean = unexpected()
        override fun generateStream(prompt: String, stream: GenStream) = unexpected()
        override fun cancel() = Unit
        override fun shutdown() = Unit
    }
}
