package com.abybijo.openflow

import android.content.Context
import com.abybijo.openflow.whisper.WhisperEngine
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Groq takes at most 25 MB per file; 600 s of 16 kHz mono is 19.2 MB, safely under. */
private const val GROQ_CHUNK_SECONDS = 600L
private const val GROQ_SINGLE_MAX_BYTES = 20L * 1024L * 1024L

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
 *
 * The on-device path takes the samples straight from the recorder
 * ([samples], in memory) and makes ONE whisper_full call — whisper.cpp
 * windows 30 seconds internally, so no manual chunking is needed and
 * no chunk files ever touch the disk. Groq audio is still chunked
 * into 600-second pieces (under its 25 MB file limit) and merged in
 * order. A chunk is never dropped silently — if any chunk fails,
 * the whole transcription reports the failure.
 *
 * [samples] is the same audio as [wavFile]; the WAV file is still
 * recorded in parallel because Groq fallback needs it for upload.
 * When [samples] is null the local path decodes the file in memory
 * instead (still one call, still no chunking).
 */
suspend fun transcribeWithFallback(
    context: Context,
    engine: String,
    modelId: String,
    wavFile: File,
    samples: FloatArray? = null,
    onFallback: (suspend (String) -> Unit)? = null
): Pair<TranscribeResult, Boolean> {
    if (engine == "local") {
        when (val local = transcribeLocal(context, modelId, wavFile, samples)) {
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
    return transcribeGroqInChunks(context, wavFile)
}

/**
 * Trim the silence off both ends of a recording, using the same
 * speech measurement the recorder uses live (RMS per 100 ms frame
 * against [WavRecorder.SPEECH_RMS_THRESHOLD]). Pauses never reach
 * the model — openWispr's VAD trim does the same with a Silero
 * model; this amplitude version is lighter (no ONNX dependency, no
 * extra download) and fails safe: when nothing passes the threshold
 * the audio comes back untouched, and 0.3 s of padding on both ends
 * keeps word onsets and tails intact. Internal pauses are kept —
 * only the dead air at the start and end is cut.
 */
fun trimSilence(samples: FloatArray): FloatArray {
    if (samples.isEmpty()) return samples
    val frame = WavRecorder.SAMPLE_RATE / 10 // 100 ms frames
    val pad = (WavRecorder.SAMPLE_RATE * 0.3f).toInt() // 0.3 s padding
    val minKept = WavRecorder.SAMPLE_RATE / 2 // never keep less than 0.5 s
    fun frameIsSpeech(start: Int): Boolean {
        val end = minOf(start + frame, samples.size)
        if (end <= start) return false
        var sumSq = 0.0
        for (i in start until end) {
            val f = samples[i].toDouble()
            sumSq += f * f
        }
        return kotlin.math.sqrt(sumSq / (end - start)).toFloat() >= WavRecorder.SPEECH_RMS_THRESHOLD
    }
    var firstSpeech = -1
    var lastSpeech = -1
    var i = 0
    while (i < samples.size) {
        if (frameIsSpeech(i)) {
            if (firstSpeech < 0) firstSpeech = i
            lastSpeech = i
        }
        i += frame
    }
    if (firstSpeech < 0 || lastSpeech < 0) return samples // nothing detected: return it all
    val start = (firstSpeech - pad).coerceAtLeast(0)
    val end = (lastSpeech + frame + pad).coerceAtMost(samples.size)
    if (end - start < minKept) return samples
    return samples.copyOfRange(start, end)
}

/** On-device transcription of any length in a single in-memory call. */
private suspend fun transcribeLocal(
    context: Context,
    modelId: String,
    wavFile: File,
    samples: FloatArray?
): TranscribeResult {
    val raw = samples?.takeIf { it.isNotEmpty() } ?: wavToFloatSamples(wavFile)
    if (raw.isEmpty()) return TranscribeResult.Error("No audio was recorded.")
    val trimmed = trimSilence(raw)
    return LocalWhisperEngine(context, modelId).transcribeSamples(trimmed)
}

/** Groq transcription of any length, chunked under the 25 MB file limit and merged in order. */
private suspend fun transcribeGroqInChunks(
    context: Context,
    wavFile: File
): Pair<TranscribeResult, Boolean> {
    val key = KeyStore.getGroqKey(context)
        ?: return TranscribeResult.Error("No Groq API key saved. Add one in Settings.") to true
    if (wavFile.length() <= GROQ_SINGLE_MAX_BYTES) {
        return GroqClient.transcribe(key, wavFile) to true
    }
    val chunks = withContext(Dispatchers.IO) { splitWav(context, wavFile, GROQ_CHUNK_SECONDS) }
    if (chunks.isEmpty()) return TranscribeResult.Error("No audio was recorded.") to true
    try {
        val parts = mutableListOf<String>()
        for (chunk in chunks) {
            when (val r = GroqClient.transcribe(key, chunk)) {
                is TranscribeResult.Ok -> if (r.text.isNotBlank()) parts.add(r.text)
                else -> return r to true
            }
        }
        val merged = parts.joinToString(" ").trim()
        return if (merged.isBlank()) TranscribeResult.Error("Didn't catch that. Please try speaking again.") to true
        else TranscribeResult.Ok(merged) to true
    } finally {
        chunks.forEach { try { it.delete() } catch (_: Exception) { } }
    }
}

/**
 * Split a 16 kHz mono WAV into chunk WAVs of [chunkSeconds] each,
 * written to the cache dir. Boundaries fall on exact sample
 * boundaries, so no audio is dropped or duplicated; a word cut by a
 * boundary may come back partial in one chunk, but every word the
 * engine hears is kept, in order. The caller deletes the chunks.
 * File I/O is streamed (never the whole file in memory).
 */
private fun splitWav(context: Context, wavFile: File, chunkSeconds: Long): List<File> {
    val bytesPerSecond = (WavRecorder.SAMPLE_RATE * 2).toLong()
    val chunkBytes = chunkSeconds * bytesPerSecond
    val totalPcm = (wavFile.length() - 44).coerceAtLeast(0)
    if (totalPcm <= 0L) return emptyList()
    val chunks = mutableListOf<File>()
    var offset = 0L
    var index = 0
    try {
        RandomAccessFile(wavFile, "r").use { raf ->
            while (offset < totalPcm) {
                val thisChunk = minOf(chunkBytes, totalPcm - offset)
                val out = File(context.cacheDir, "chunk_${System.currentTimeMillis()}_$index.wav")
                FileOutputStream(out).use { o ->
                    o.write(wavHeader(thisChunk.toInt()))
                    raf.seek(44 + offset)
                    val buf = ByteArray(64 * 1024)
                    var remaining = thisChunk
                    while (remaining > 0) {
                        val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        o.write(buf, 0, read)
                        remaining -= read
                    }
                }
                chunks.add(out)
                offset += thisChunk
                index++
            }
        }
    } catch (_: Exception) {
        chunks.forEach { try { it.delete() } catch (_: Exception) { } }
        return emptyList()
    }
    return chunks
}

/** A plain 44-byte PCM WAV header for [pcmBytes] of 16 kHz mono audio. */
private fun wavHeader(pcmBytes: Int): ByteArray {
    val total = pcmBytes + 36
    val byteRate = WavRecorder.SAMPLE_RATE * 2
    val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray())
    buf.putInt(total)
    buf.put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray())
    buf.putInt(16)
    buf.putShort(1.toShort()) // PCM
    buf.putShort(1.toShort()) // mono
    buf.putInt(WavRecorder.SAMPLE_RATE)
    buf.putInt(byteRate)
    buf.putShort(2.toShort()) // block align
    buf.putShort(16.toShort()) // bits per sample
    buf.put("data".toByteArray())
    buf.putInt(pcmBytes)
    return buf.array()
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
 * re-reading a 32–466 MB file every time. Takes samples in memory —
 * one whisper_full call, whatever the length.
 */
class LocalWhisperEngine(
    private val context: Context,
    private val modelId: String
) {
    suspend fun transcribeSamples(samples: FloatArray): TranscribeResult =
        withContext(Dispatchers.Default) {
            val model = ModelCatalog.byId(modelId)
            if (!ModelDownloader.isDownloaded(context, model)) {
                return@withContext TranscribeResult.LocalModelMissing
            }
            if (!WhisperEngine.isAvailable) {
                return@withContext TranscribeResult.Error("On-device engine is not available on this phone.")
            }
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
}

/**
 * Decode a 16 kHz mono PCM WAV into float samples in -1..1. Only a
 * safety net for the local path when the recorder's in-memory samples
 * are unavailable; the normal path never reads the file back. Our own
 * recorder writes a plain 44-byte header, but the "data" chunk is
 * scanned for anyway so an extra chunk never shifts the audio.
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
