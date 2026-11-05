package com.abybijo.openflow.whisper

import java.io.File

/**
 * Thin Kotlin wrapper over whisper.cpp (JNI).
 * If the native library cannot be loaded, [isAvailable] is false and the
 * app shows a clear error instead of silently using another recogniser.
 *
 * Transcription returns diagnostics as well as text, so an empty result
 * is never a mystery on-device: segments, no-speech probability and
 * milliseconds are exposed after every call.
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

    var lastSegments: Int = 0
        private set
    var lastNoSpeechProb: Float = 1f
        private set
    var lastTranscribeMs: Long = 0L
        private set

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

    fun transcribe(pcm: FloatArray): String = transcribeDiag(pcm, lenient = false)

    /** Transcribe, keeping the last diagnostics. Lenient = Retry mode:
     *  accept quieter / less certain speech instead of discarding it. */
    fun transcribeDiag(pcm: FloatArray, lenient: Boolean): String {
        if (handle == 0L) {
            lastSegments = 0; lastNoSpeechProb = 1f; lastTranscribeMs = 0L
            return ""
        }
        val raw = try {
            nativeTranscribeDiag(handle, pcm, lenient) ?: ""
        } catch (_: Exception) {
            ""
        }
        // Format from JNI: segments|noSpeechProb|ms|text (text may contain |)
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4) {
            lastSegments = 0; lastNoSpeechProb = 1f; lastTranscribeMs = 0L
            return raw
        }
        lastSegments = parts[0].toIntOrNull() ?: 0
        lastNoSpeechProb = parts[1].toFloatOrNull() ?: 1f
        lastTranscribeMs = parts[2].toLongOrNull() ?: 0L
        return parts[3]
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
    private external fun nativeTranscribeDiag(handle: Long, pcm: FloatArray, lenient: Boolean): String?
    private external fun nativeFree(handle: Long)
}
