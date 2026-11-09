package com.abybijo.openflow

import android.content.Context
import com.abybijo.openflow.whisper.WhisperEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One speech-to-text engine behind one function, so the bubble, the
 * demo and history never care whether the words came from Groq's
 * cloud or from a Whisper model running on the phone. Both engines
 * answer with the same [TranscribeResult], so the caller's `when`
 * stays exactly the same.
 */
interface SttEngine {
    suspend fun transcribe(wavFile: File): TranscribeResult
}

/** Groq Cloud: whisper-large-v3-turbo with the user's own key. */
class GroqEngine(private val key: String) : SttEngine {
    override suspend fun transcribe(wavFile: File): TranscribeResult =
        GroqClient.transcribe(key, wavFile)
}

/**
 * Transcribe with the selected engine. When On-device is selected but
 * the model is missing or local transcription fails, Groq is used for
 * that utterance instead — the dictation is never lost. Returns the
 * result and whether Groq was the engine that produced it (usage
 * accounting counts Groq only). [onFallback] reports the switch so
 * the caller can tell the user.
 */
suspend fun transcribeWithFallback(
    context: Context,
    engine: String,
    modelId: String,
    wavFile: File,
    onFallback: (suspend (String) -> Unit)? = null
): Pair<TranscribeResult, Boolean> {
    if (engine == "local") {
        when (val local = LocalWhisperEngine(context, modelId).transcribe(wavFile)) {
            is TranscribeResult.Ok -> return local to false
            is TranscribeResult.LocalModelMissing -> {
                onFallback?.invoke("On-device model not downloaded — used Groq instead.")
            }
            is TranscribeResult.Error -> {
                onFallback?.invoke("On-device failed — used Groq instead.")
            }
            else -> { /* InvalidKey/RateLimited cannot come from local; fall through to Groq. */ }
        }
    }
    val key = KeyStore.getGroqKey(context)
        ?: return TranscribeResult.Error("No Groq API key saved. Add one in Settings.") to true
    return GroqClient.transcribe(key, wavFile) to true
}

/** Free the cached on-device model (engine switch, service shutdown). */
fun releaseLocalEngine() {
    LocalWhisperCache.release()
}

/**
 * Preload the selected on-device model into memory ahead of the first
 * dictation, so the first transcription does not pay the load cost.
 * Best-effort and silent: returns quietly when the model is not
 * downloaded or the native engine is unavailable. The warmed model is
 * the same cached one [LocalWhisperEngine] transcribes with.
 */
suspend fun warmLocalEngine(context: Context, modelId: String) {
    withContext(Dispatchers.Default) {
        try {
            val model = ModelCatalog.byId(modelId)
            if (!ModelDownloader.isDownloaded(context, model)) return@withContext
            if (!WhisperEngine.isAvailable) return@withContext
            LocalWhisperCache.warm(context, model)
        } catch (_: Exception) {
            // Warming must never surface an error; the first dictation
            // simply loads the model then.
        }
    }
}

/**
 * On-device Whisper: a downloaded model file, transcribed fully
 * offline on the CPU. The loaded model stays cached across
 * dictations, so the second utterance starts at once instead of
 * re-reading a 32–466 MB file every time.
 */
class LocalWhisperEngine(
    private val context: Context,
    private val modelId: String
) : SttEngine {
    override suspend fun transcribe(wavFile: File): TranscribeResult =
        withContext(Dispatchers.Default) {
            val model = ModelCatalog.byId(modelId)
            if (!ModelDownloader.isDownloaded(context, model)) {
                return@withContext TranscribeResult.LocalModelMissing
            }
            if (!WhisperEngine.isAvailable) {
                return@withContext TranscribeResult.Error("On-device engine is not available on this phone.")
            }
            val samples = wavToFloatSamples(wavFile)
            if (samples.isEmpty()) {
                return@withContext TranscribeResult.Error("No audio was recorded.")
            }
            val text = LocalWhisperCache.transcribe(context, model, samples)
            if (text.isBlank()) {
                TranscribeResult.Error("On-device transcription returned no text.")
            } else {
                // Whisper marks non-speech with brackets ([BLANK_AUDIO],
                // [Music]); those are not dictation and never reach the field.
                TranscribeResult.Ok(
                    text.replace(Regex("\\[[^\\]]*]"), "").trim()
                )
            }
        }

    /**
     * Decode a 16 kHz mono PCM WAV into float samples in -1..1. Our
     * own recorder writes a plain 44-byte header, but the "data" chunk
     * is scanned for anyway so an extra chunk never shifts the audio.
     */
    private fun wavToFloatSamples(wavFile: File): FloatArray {
        val bytes = try {
            wavFile.readBytes()
        } catch (_: Exception) {
            return FloatArray(0)
        }
        var dataStart = 44
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataStart = i + 8
                break
            }
            if (chunkSize <= 0) break
            i += 8 + chunkSize
        }
        if (dataStart >= bytes.size) return FloatArray(0)
        val count = (bytes.size - dataStart) / 2
        val out = FloatArray(count)
        val buf = ByteBuffer.wrap(bytes, dataStart, count * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until count) out[n] = buf.short / 32768f
        return out
    }
}

/** Process-wide cache: one loaded model, shared by every dictation. */
private object LocalWhisperCache {
    private val engine = WhisperEngine()
    private var loadedId: String? = null

    @Synchronized
    fun transcribe(context: Context, model: WhisperModel, samples: FloatArray): String {
        if (loadedId != model.id) {
            val file = ModelDownloader.modelFile(context, model)
            if (!engine.load(file)) {
                loadedId = null
                return ""
            }
            loadedId = model.id
        }
        return engine.transcribe(samples)
    }

    /** Load the model now, without transcribing — warming for later. */
    @Synchronized
    fun warm(context: Context, model: WhisperModel) {
        if (loadedId == model.id) return
        val file = ModelDownloader.modelFile(context, model)
        if (engine.load(file)) {
            loadedId = model.id
        }
    }

    @Synchronized
    fun release() {
        engine.close()
        loadedId = null
    }
}
