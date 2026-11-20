package com.example.aiassistent1.data.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal object OpenClProbeNative {
    init { System.loadLibrary("assistant_opencl_probe") }
    external fun driver(report: OpenClProbeReport)
    external fun infer(path: ByteArray, prompt: ByteArray, layers: Int, limit: Int, warm: Boolean, report: OpenClProbeReport)
}

/** A driver abort can bypass Kotlin. Commit each checkpoint before entering the next native operation. */
internal class OpenClProbeReport(private val file: AtomicFile, val json: JSONObject) {
    fun field(key: String, bytes: ByteArray) = put(key, bytes.toString(Charsets.UTF_8))
    fun put(key: String, value: String) {
        json.put(key, value)
        val out = file.startWrite()
        try {
            out.write(json.toString(2).toByteArray())
            file.finishWrite(out)
        } catch (failure: Throwable) {
            file.failWrite(out)
            throw failure
        }
    }
}

/** Opt-in instrumentation target in the application's real :llm process. */
class OpenClProbeService : Service() {
    @Volatile private var activeOwner: String? = null
    private val binder = object : ILlamaWorker.Stub() {
        @Synchronized override fun execute(owner: String, operation: String, args: Bundle, stream: ILlamaStream?): Bundle {
            val run = requireNotNull(args.getString("run"))
            require(run.matches(Regex("[a-zA-Z0-9_-]{1,48}")))
            val file = AtomicFile(File(cacheDir, "opencl-probe-$run.json"))
            val report = OpenClProbeReport(file, JSONObject().put("run", run).put("operation", operation)
                .put("invocation", owner).put("startedAtUtcMillis", System.currentTimeMillis())
                .put("pid", Process.myPid()).put("process", android.app.Application.getProcessName()))
            activeOwner = owner
            try {
                report.put("status", "RUNNING")
                if (operation == "driver") {
                    OpenClProbeNative.driver(report)
                } else {
                    require(operation == "cpu" || operation == "opencl")
                    val dir = requireNotNull(getExternalFilesDir("models")).canonicalFile
                    val model = File(dir, requireNotNull(args.getString("model"))).canonicalFile
                    require(model.parentFile == dir && model.extension == "gguf" && model.isFile) { "Model unavailable" }
                    val layers = args.getInt("layers")
                    require(if (operation == "cpu") layers == 0 else layers in 1..128)
                    val limit = args.getInt("limit")
                    require(limit in 1..256)
                    report.put("model", model.name)
                    report.put("requestedLayers", layers.toString())
                    report.put("tokenLimit", limit.toString())
                    report.put("scenario", requireNotNull(args.getString("scenario")))
                    OpenClProbeNative.infer(model.path.toByteArray(), requireNotNull(args.getString("prompt")).toByteArray(),
                        layers, limit, args.getBoolean("warmCompare"), report)
                }
            } catch (e: Exception) {
                report.put("error", e.message ?: e.javaClass.simpleName)
            } catch (e: UnsatisfiedLinkError) {
                report.put("error", e.message ?: "Native diagnostic unavailable")
            } finally {
                if (report.json.optString("status") == "RUNNING") report.put("status", "FAILED")
                activeOwner = null
            }
            return Bundle().apply { putString("report", report.json.toString()) }
        }
        override fun cancel(owner: String) = abort(owner)
        override fun abort(owner: String) { if (activeOwner == owner) Process.killProcess(Process.myPid()) }
    }
    override fun onBind(intent: Intent) = binder
}
