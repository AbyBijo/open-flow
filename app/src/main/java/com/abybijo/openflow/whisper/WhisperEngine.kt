package com.abybijo.openflow.whisper

import java.io.File

/**
 * Thin Kotlin wrapper over whisper.cpp (JNI).
 * If the native library cannot be loaded, [isAvailable] is false and the
 * app shows a clear error instead of silently using another recogniser.
 */
class WhisperEngine {
    companion object {
        val isAvailable: Boolean = try {
            System.loadLibrary("openflow_whisper")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private var handle: Long = 0L
    private var loadedPath: String? = null

    fun load(modelFile: File): Boolean {
        if (!isAvailable) return false
        if (loadedPath == modelFile.absolutePath && handle != 0L) return true
        close()
        val result = try {
            nativeInit(modelFile.absolutePath)
        } catch (_: Exception) {
            0L
        }
        handle = result
        loadedPath = if (result != 0L) modelFile.absolutePath else null
        return result != 0L
    }

    fun transcribe(pcm: FloatArray): String {
        if (handle == 0L) return ""
        return try {
            nativeTranscribe(handle, pcm) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun close() {
        if (handle != 0L) {
            try {
                nativeFree(handle)
            } catch (_: Exception) {
            }
            handle = 0L
            loadedPath = null
        }
    }

    private external fun nativeInit(path: String): Long
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray): String?
    private external fun nativeFree(handle: Long)
}
