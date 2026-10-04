package com.example.aiassistent1.data.model

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import com.example.aiassistent1.domain.model.DeviceContextLimit
import com.example.aiassistent1.domain.model.DeviceMemorySnapshot
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.MemoryRuntimeSettings
import com.example.aiassistent1.domain.model.ModelMemoryCalculator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface ModelMemoryGuard {
    val changes: StateFlow<Long>
    fun assess(file: File, params: GenerationParams): DeviceContextLimit
    fun beforeLoad(): DeviceMemorySnapshot?
    fun loaded(file: File, params: GenerationParams, before: DeviceMemorySnapshot?)
    fun unloaded()
    fun refresh()
}

/** Shared by settings and the engine. All sampling/file access is performed off the UI thread. */
class DeviceModelMemoryGuard(
    private val snapshot: () -> DeviceMemorySnapshot?,
) : ModelMemoryGuard {
    private data class Loaded(val privateDirtyBefore: Long, val maximumPrivateBytes: Long)
    private val revision = MutableStateFlow(0L)
    override val changes = revision.asStateFlow()
    @Volatile private var loaded: Loaded? = null

    override fun assess(file: File, params: GenerationParams): DeviceContextLimit {
        val metadata = GgufMetadataReader.readMetadata(file)?.memory
        val memory = snapshot()
        val allocation = loaded
        val credit = if (memory != null && allocation != null) {
            // Track lazy KV/workspace page faults too. Never add file-backed clean pages to availMem.
            (memory.processPrivateDirtyBytes - allocation.privateDirtyBefore)
                .coerceIn(0, allocation.maximumPrivateBytes)
        } else 0L
        return ModelMemoryCalculator.calculate(metadata, params.memoryRuntimeSettings(), memory, credit)
    }

    override fun beforeLoad(): DeviceMemorySnapshot? = snapshot()

    override fun loaded(file: File, params: GenerationParams, before: DeviceMemorySnapshot?) {
        val metadata = GgufMetadataReader.readMetadata(file)?.memory
        val cost = metadata?.let { ModelMemoryCalculator.cost(it, params.memoryRuntimeSettings(), params.contextSize) }
        val baseline = loaded?.privateDirtyBefore ?: before?.processPrivateDirtyBytes
        loaded = if (baseline != null && cost != null) {
            // A GPU copy may be private; CPU mmap weights remain in the reclaimable file cache.
            Loaded(baseline.coerceAtLeast(0),
                cost.workingBytes + if (params.gpuLayers != 0) metadata.fileBytes else 0)
        } else null
        refresh()
    }

    override fun unloaded() {
        loaded = null
        refresh()
    }

    override fun refresh() { revision.update { it + 1 } }

    companion object {
        fun forAndroid(context: Context): DeviceModelMemoryGuard {
            val manager = context.applicationContext.getSystemService(ActivityManager::class.java)
            return DeviceModelMemoryGuard {
                try {
                    val info = ActivityManager.MemoryInfo()
                    val process = Debug.MemoryInfo()
                    manager.getMemoryInfo(info)
                    Debug.getMemoryInfo(process)
                    DeviceMemorySnapshot(info.totalMem, info.availMem, info.threshold, info.lowMemory,
                        process.totalPrivateDirty.toLong() * 1024, Process.is64Bit())
                } catch (_: RuntimeException) {
                    null
                }
            }
        }
    }
}

private fun GenerationParams.memoryRuntimeSettings() = MemoryRuntimeSettings(batchSizeAuto, batchSize, gpuLayers)
