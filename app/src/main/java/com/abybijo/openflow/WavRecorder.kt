package com.abybijo.openflow

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Records 16 kHz mono PCM to a WAV file. VOICE_RECOGNITION source first,
 * MIC as fallback. Recording runs until the user stops it — there is no
 * time cap. The samples are kept in memory as well as on disk, so the
 * on-device engine can transcribe them in one call without a file
 * round-trip (the WAV file is still written: Groq fallback needs it
 * for upload, and history/stats need the recorded duration).
 *
 * The recorder also watches every 100 ms frame for speech (RMS
 * against [SPEECH_RMS_THRESHOLD]) and remembers where speech last
 * happened. That one measurement serves two features: trimming the
 * silence off the ends before transcription, and stopping the
 * recording automatically after a long pause.
 */
class WavRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        /** Real speech peaks at roughly 1300–2900 on VOICE_RECOGNITION; 6000 is a safe full scale. */
        const val FULL_SCALE_PEAK = 6000
        /** Silent bars never fall below this level. */
        const val LEVEL_FLOOR = 0.20f
        /**
         * A 100 ms frame counts as speech when its RMS reaches this
         * level (samples in -1..1). Quiet-room noise sits well below
         * it; normal speech sits well above. Deliberately low, and
         * both uses of it fail safe: the trim returns the full audio
         * when nothing passes it, and auto-stop only arms after
         * speech has started.
         */
        const val SPEECH_RMS_THRESHOLD = 0.008f
        /** Auto-stop fires after this many seconds of continuous silence. */
        const val AUTO_STOP_SILENCE_SECONDS = 5f
    }

    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    private var pcmBytes = 0
    /** Peak absolute sample of the most recent 100 ms buffer, for the live waveform. */
    @Volatile private var lastPeak = 0
    /** Every captured 100 ms frame, in order — the in-memory copy of the recording. */
    private val sampleChunks = ArrayList<ShortArray>()
    /** Samples captured so far (frames * 1600). */
    @Volatile private var totalSamples = 0
    /** Sample index just past the end of the last speech frame; -1 until any speech is seen. */
    @Volatile private var lastSpeechSample = -1
    /** True once any frame has passed the speech threshold. */
    @Volatile private var speechStarted = false

    /**
     * Live mic level, 0..1. Square-root mapping against a full-scale peak of
     * 6000 — VOICE_RECOGNITION records quiet by design, so scaling against
     * the 16-bit full scale (32767) would pin every bar to the floor. A
     * floor of 0.20 keeps silent bars from collapsing into dots.
     */
    fun amplitude(): Float {
        val peak = lastPeak.coerceIn(0, FULL_SCALE_PEAK)
        return sqrt(peak.toFloat() / FULL_SCALE_PEAK).coerceIn(0f, 1f).coerceAtLeast(LEVEL_FLOOR)
    }

    fun start(outputFile: File): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE * 2)
        val source = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).also { if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("not initialised") }
        } catch (_: Exception) {
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                ).also { if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("not initialised") }
            } catch (_: Exception) {
                return false
            }
        }
        recorder = source
        pcmBytes = 0
        lastPeak = 0
        sampleChunks.clear()
        totalSamples = 0
        lastSpeechSample = -1
        speechStarted = false
        recording = true
        try {
            FileOutputStream(outputFile).use { it.write(ByteArray(44)) }
        } catch (_: Exception) {
            return false
        }
        recordingThread = Thread {
            val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms frames
            val out = FileOutputStream(outputFile, true)
            try {
                source.startRecording()
                while (recording) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var peak = 0
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val abs = kotlin.math.abs(buffer[i].toInt())
                            if (abs > peak) peak = abs
                            val f = buffer[i] / 32768f
                            sumSq += (f * f).toDouble()
                        }
                        lastPeak = peak
                        // One shared speech measurement: the same RMS
                        // threshold drives the silence trim and the
                        // long-pause auto-stop, so they can never
                        // disagree about what counts as speech.
                        val rms = kotlin.math.sqrt(sumSq / read).toFloat()
                        if (rms >= SPEECH_RMS_THRESHOLD) {
                            speechStarted = true
                            lastSpeechSample = totalSamples + read
                        }
                        sampleChunks.add(buffer.copyOf(read))
                        totalSamples += read
                        val bytes = ByteBuffer.allocate(read * 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) bytes.putShort(buffer[i])
                        out.write(bytes.array())
                        pcmBytes += read * 2
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
                // Stop cleanly; whatever was captured is still usable.
            } finally {
                try { out.close() } catch (_: Exception) { }
                try { source.stop() } catch (_: Exception) { }
                try { source.release() } catch (_: Exception) { }
            }
        }.also { it.start() }
        return true
    }

    /** Stops recording, finalises the WAV header, returns recorded seconds. */
    fun stop(outputFile: File): Float {
        recording = false
        try { recordingThread?.join(2000) } catch (_: Exception) { }
        recordingThread = null
        recorder = null
        val seconds = pcmBytes.toFloat() / (SAMPLE_RATE * 2)
        try {
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.seek(0)
                raf.write(wavHeader(pcmBytes))
            }
        } catch (_: Exception) { }
        return seconds
    }

    fun isRecording(): Boolean = recording

    /**
     * All captured audio as float samples in -1..1, in order. Call
     * only after [stop] — the recording thread has joined by then, so
     * the chunks are stable. The on-device engine transcribes this
     * array in one call; no file is read back for it.
     */
    fun samples(): FloatArray {
        val total = totalSamples
        if (total <= 0) return FloatArray(0)
        val out = FloatArray(total)
        var off = 0
        for (chunk in sampleChunks) {
            for (i in chunk.indices) out[off++] = chunk[i] / 32768f
        }
        return out
    }

    /**
     * Seconds of continuous silence since the last speech frame. 0
     * while speech is ongoing, and 0 until any speech has been heard
     * at all — the long-pause auto-stop only arms once the user has
     * actually started talking, so thinking before the first word
     * never stops a recording.
     */
    fun silenceSeconds(): Float {
        if (!speechStarted || lastSpeechSample < 0) return 0f
        return ((totalSamples - lastSpeechSample).coerceAtLeast(0)).toFloat() / SAMPLE_RATE
    }

    private fun wavHeader(pcmBytes: Int): ByteArray {
        val total = pcmBytes + 36
        val byteRate = SAMPLE_RATE * 2
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(total)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1.toShort()) // PCM
        buf.putShort(1.toShort()) // mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(byteRate)
        buf.putShort(2.toShort()) // block align
        buf.putShort(16.toShort()) // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(pcmBytes)
        return buf.array()
    }
}
