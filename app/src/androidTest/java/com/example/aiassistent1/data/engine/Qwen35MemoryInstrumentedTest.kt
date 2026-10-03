package com.example.aiassistent1.data.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiassistent1.data.model.DeviceModelMemoryGuard
import com.example.aiassistent1.data.model.GgufMetadataReader
import com.example.aiassistent1.domain.interfaces.ModelProvider
import com.example.aiassistent1.domain.model.*
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device smoke test. It neither saves settings nor executes calendar commands. */
@RunWith(AndroidJUnit4::class)
class Qwen35MemoryInstrumentedTest {
    @Test fun loadsImportedQwen35AndProducesText() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = InstrumentationRegistry.getArguments().getString("qwen35Model")
        assumeTrue("Pass qwen35Model with an imported GGUF filename", !name.isNullOrBlank())
        val context = instrumentation.targetContext
        val file = requireNotNull(GgufMetadataReader.resolveInDirectory(
            requireNotNull(context.getExternalFilesDir("models")), requireNotNull(name)))
        assertTrue("Imported model not found", file.isFile)
        val metadata = requireNotNull(GgufMetadataReader.readMetadata(file)?.memory)
        assertEquals("qwen35", metadata.architecture)
        val guard = DeviceModelMemoryGuard.forAndroid(context)
        val automatic = guard.assess(file, GenerationParams())
        assertNotEquals(MemoryLimitStatus.METADATA_UNAVAILABLE, automatic.status)
        assertEquals(MemoryEstimateProfile.QWEN35, automatic.estimateProfile)
        // Keep native smoke work bounded; this is a loading test, not a maximum-context stress test.
        val params = GenerationParams(contextSize = 512, maxTokens = 128, batchSizeAuto = false, batchSize = 64,
            cpuThreadsAuto = false, cpuThreads = 4, gpuLayers = 0, temperature = 0f, topK = 1)
        val before = requireNotNull(guard.beforeLoad())
        val assessment = guard.assess(file, params)
        assertTrue(assessment.explanation, assessment.canLoad)
        val estimate = requireNotNull(ModelMemoryCalculator.cost(metadata, MemoryRuntimeSettings(false, 64), 512))
        val report = JSONObject().put("architecture", metadata.architecture).put("fileBytes", file.length())
            .put("automaticStatus", automatic.status.name).put("automaticMaximumContext", automatic.maximumContext)
            .put("maximumContext", assessment.maximumContext).put("context", 512).put("batch", 64)
            .put("estimatedBytes", estimate.totalBytes).put("availableBytesBefore", before.availableBytes)
        val engine = LlamatikEngine(object : ModelProvider {
            override suspend fun getModelPath() = Result.success(file.path)
        }, guard, params)
        try {
            engine.ensureLoaded().getOrThrow()
            assertEquals(ModelState.Ready, engine.state.value)
            val afterLoad = requireNotNull(guard.beforeLoad())
            report.put("privateDirtyBytesBefore", before.processPrivateDirtyBytes)
                .put("privateDirtyBytesAfterLoad", afterLoad.processPrivateDirtyBytes)
                .put("availableBytesAfterLoad", afterLoad.availableBytes)
            val response = withTimeout(180_000L) {
                engine.generate(listOf(ChatMessage(role = MessageRole.USER, content = "Скажи: проверка завершена.")))
                    .toList().joinToString("")
            }
            assertTrue("Native model returned no text", response.isNotBlank())
            report.put("response", response).put("status", "PASSED")
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.toString())
            throw error
        } finally {
            engine.close()
            File(context.cacheDir, "qwen35-memory-smoke.json").writeText(report.toString(2))
            Log.i("Qwen35MemorySmoke", report.toString())
        }
    }
}
