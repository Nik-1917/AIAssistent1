package com.example.aiassistent1.data.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SharedMemory
import com.example.aiassistent1.domain.model.ComputeMode
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.InferenceBackend
import com.llamatik.library.platform.GenStream
import kotlin.concurrent.thread

/** Owns native state only. Preferences, Room and audio stay with their existing owners. */
class LlamaWorkerService : Service() {
    @Volatile private var activeOwner: String? = null
    @Volatile private var destroyed = false

    private companion object {
        // JNI state is process-wide. A new binding may arrive while an old instance is releasing it.
        val operationLock = Any()
        @Volatile var nativeService: LlamaWorkerService? = null
    }

    private val binder = object : ILlamaWorker.Stub() {
        override fun execute(owner: String, operation: String, arguments: Bundle, stream: ILlamaStream?): Bundle =
            synchronized(operationLock) {
                val reply = Bundle()
                try {
                    check(!destroyed) { "Рабочий сервис модели закрыт" }
                    if (operation != "hello") {
                        if (operation == "shutdown") {
                            if (activeOwner == owner) releaseNative()
                        } else {
                            if (activeOwner != owner) {
                                NativeLlamaRuntime.shutdown()
                                activeOwner = owner
                                nativeService = this@LlamaWorkerService
                                NativeLlamaRuntime.beginRequest()
                            }
                            executeNative(operation, arguments, stream, reply)
                        }
                    }
                } catch (failure: Exception) {
                    reply.putString("error", failure.message ?: "Native inference failed")
                }
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                reply.putLong("privateDirtyBytes", memory.totalPrivateDirty.toLong() * 1024)
                reply.putInt("pid", Process.myPid())
                reply
            }

        override fun cancel(owner: String) {
            if (nativeService === this@LlamaWorkerService && activeOwner == owner) NativeLlamaRuntime.cancel()
        }
        override fun abort(owner: String) {
            if (nativeService === this@LlamaWorkerService && activeOwner == owner) Process.killProcess(Process.myPid())
        }
    }

    private fun executeNative(operation: String, args: Bundle, stream: ILlamaStream?, reply: Bundle) {
        when (operation) {
            "begin" -> NativeLlamaRuntime.beginRequest()
            "gpuInfo" -> {
                val info = AutomaticLlamaBridge.gpuDriverInfo(backend(args).nativeId)
                reply.putString("identity", info[0])
                reply.putString("unsupported", info[1])
            }
            "gpu" -> {
                val device = NativeLlamaRuntime.gpuDevice("", backend(args))
                if (device != null) {
                    reply.putString("device", device.name)
                    reply.putString("identity", device.identity)
                    device.freeBytes?.let { reply.putLong("freeBytes", it) }
                    reply.putLong("totalBytes", device.totalBytes)
                    device.maxAllocationBytes?.let { reply.putLong("maxAllocationBytes", it) }
                }
            }
            "prepare" -> {
                NativeLlamaRuntime.prepareTokenizer(requireNotNull(args.getString("path")))
                reply.putBoolean("allocationValid", AutomaticLlamaBridge.backendInfo().getOrNull(5) == "1")
            }
            "count" -> reply.putInt("count", NativeLlamaRuntime.countTokens(readInput(args)))
            "load", "resize", "generate", "continue" -> {
                val params = GenerationParams(
                    contextSize = args.getInt("context"), maxTokens = args.getInt("maxTokens"),
                    temperature = args.getFloat("temperature"), topP = args.getFloat("topP"),
                    topK = args.getInt("topK"), repeatPenalty = args.getFloat("penalty"),
                    gpuLayers = args.getInt("gpuLayers"), automaticMemory = args.getBoolean("automaticMemory"),
                    inferenceBackend = backend(args),
                    computeMode = ComputeMode.entries.firstOrNull { it.name == args.getString("computeMode") } ?: ComputeMode.AUTO,
                )
                val threads = args.getInt("threads")
                val batch = args.getInt("batch")
                NativeLlamaRuntime.updateParams(params, threads, batch)
                when (operation) {
                    "load" -> reply.putBoolean("loaded", NativeLlamaRuntime.load(requireNotNull(args.getString("path"))))
                    "resize" -> reply.putBoolean("loaded", NativeLlamaRuntime.resizeContext(params.contextSize, batch, threads))
                    else -> {
                        val callback = object : GenStream {
                            override fun onDelta(text: String) { stream?.onDelta(text) }
                            override fun onComplete() = Unit
                            override fun onError(message: String) { error(message) }
                        }
                        if (operation == "continue") NativeLlamaRuntime.continueStream(callback)
                        else NativeLlamaRuntime.generateStream(readInput(args), callback)
                        NativeLlamaRuntime.lastGeneration?.let { result ->
                            reply.putLongArray("generation", longArrayOf(result.reason.ordinal.toLong(),
                                result.promptTokens.toLong(), result.generatedTokens.toLong(), result.contextSize.toLong()))
                        }
                    }
                }
                reply.putStringArray("backend", AutomaticLlamaBridge.backendInfo())
                reply.putLongArray("timings", NativeLlamaRuntime.lastTimings)
            }
            else -> error("Unknown inference operation")
        }
    }

    private fun backend(args: Bundle) = InferenceBackend.valueOf(requireNotNull(args.getString("inferenceBackend")))

    @Suppress("DEPRECATION")
    private fun readInput(args: Bundle): String {
        val memory = requireNotNull(args.getParcelable<SharedMemory>("input"))
        return memory.use {
            val buffer = it.mapReadOnly()
            try {
                val bytes = ByteArray(args.getInt("inputBytes"))
                buffer.get(bytes)
                bytes.toString(Charsets.UTF_8)
            } finally {
                SharedMemory.unmap(buffer)
            }
        }
    }

    override fun onBind(intent: Intent) = binder

    private fun releaseNative() {
        if (nativeService === this) {
            NativeLlamaRuntime.shutdown()
            nativeService = null
        }
        activeOwner = null
    }

    override fun onDestroy() {
        destroyed = true
        if (nativeService === this) NativeLlamaRuntime.cancel()
        // The UI client can disappear during an in-flight call. Drain it off the service main thread.
        thread(name = "llm-release", isDaemon = true) {
            synchronized(operationLock) { releaseNative() }
        }
        super.onDestroy()
    }
}
