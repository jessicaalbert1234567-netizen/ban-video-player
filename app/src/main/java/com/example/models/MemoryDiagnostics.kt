package com.example.models

import android.os.Debug
import android.util.Log
import java.io.File

object MemoryDiagnostics {

    const val TAG_MEMORY = "MEMORY"
    const val TAG_MODEL_LOAD = "MODEL_LOAD"
    const val TAG_ONNX = "ONNX"
    const val TAG_MODEL_MANAGER = "MODEL_MANAGER"
    const val TAG_MODEL_INSTALL = "MODEL_INSTALL"
    const val TAG_MODEL_VERIFY = "MODEL_VERIFY"
    const val TAG_ASR = "ASR"
    const val TAG_TRANSLATION = "TRANSLATION"
    const val TAG_TTS = "TTS"

    data class MemorySnapshot(
        val nativeAllocated: Long,
        val nativeTotal: Long,
        val nativeFree: Long,
        val jvmMax: Long,
        val jvmTotal: Long,
        val jvmFree: Long,
        val threadName: String
    )

    fun captureSnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val nativeAllocated = try { Debug.getNativeHeapAllocatedSize() } catch (_: Throwable) { 0L }
        val nativeTotal = try { Debug.getNativeHeapSize() } catch (_: Throwable) { 0L }
        val nativeFree = try { Debug.getNativeHeapFreeSize() } catch (_: Throwable) { 0L }

        return MemorySnapshot(
            nativeAllocated = nativeAllocated,
            nativeTotal = nativeTotal,
            nativeFree = nativeFree,
            jvmMax = runtime.maxMemory(),
            jvmTotal = runtime.totalMemory(),
            jvmFree = runtime.freeMemory(),
            threadName = Thread.currentThread().name
        )
    }

    private fun safeLog(tag: String, msg: String, error: Throwable? = null) {
        try {
            if (error != null) {
                Log.e(tag, msg, error)
            } else {
                Log.d(tag, msg)
            }
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    fun logSnapshot(tag: String, label: String) {
        val s = captureSnapshot()
        val mb = 1024L * 1024L
        safeLog(
            tag,
            "[$label] Thread: '${s.threadName}' | " +
                    "NativeAlloc: ${s.nativeAllocated / mb}MB / NativeTotal: ${s.nativeTotal / mb}MB / NativeFree: ${s.nativeFree / mb}MB | " +
                    "JvmTotal: ${s.jvmTotal / mb}MB / JvmFree: ${s.jvmFree / mb}MB / JvmMax: ${s.jvmMax / mb}MB"
        )
    }

    fun logHeapSnapshot(tag: String, label: String) {
        logSnapshot(tag, label)
    }

    inline fun <T> trackModelLoad(
        tag: String,
        modelName: String,
        modelFile: File?,
        block: () -> T
    ): T {
        val fileName = modelFile?.name ?: "unknown"
        val fileSize = if (modelFile != null && modelFile.exists()) modelFile.length() else 0L
        val threadName = Thread.currentThread().name
        val startTime = System.currentTimeMillis()

        try {
            Log.i(TAG_MODEL_LOAD, "MODEL INITIALIZATION START: $modelName ($fileName, $fileSize bytes) on thread '$threadName'")
        } catch (_: Throwable) {
            println("[$TAG_MODEL_LOAD] MODEL INITIALIZATION START: $modelName ($fileName, $fileSize bytes) on thread '$threadName'")
        }
        logSnapshot(TAG_MEMORY, "BEFORE $modelName ($fileName)")

        try {
            val result = block()
            val elapsed = System.currentTimeMillis() - startTime
            logSnapshot(TAG_MEMORY, "AFTER $modelName ($fileName)")
            try {
                Log.i(TAG_MODEL_LOAD, "MODEL INITIALIZATION END: $modelName in ${elapsed}ms on thread '$threadName'")
            } catch (_: Throwable) {
                println("[$TAG_MODEL_LOAD] MODEL INITIALIZATION END: $modelName in ${elapsed}ms on thread '$threadName'")
            }
            return result
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - startTime
            try {
                Log.e(TAG_MODEL_LOAD, "MODEL INITIALIZATION FAILED: $modelName after ${elapsed}ms on thread '$threadName': ${t.message}", t)
            } catch (_: Throwable) {
                println("[$TAG_MODEL_LOAD] MODEL INITIALIZATION FAILED: $modelName after ${elapsed}ms on thread '$threadName': ${t.message}")
            }
            throw t
        }
    }
}
