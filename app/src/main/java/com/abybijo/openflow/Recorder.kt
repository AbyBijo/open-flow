package com.abybijo.openflow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/**
 * 16 kHz mono PCM recorder for Whisper.
 * Emits audio in ~6 second chunks so dictation appears live, chunk by chunk,
 * instead of waiting for the user to stop speaking.
 */
class Recorder(
    private val onAmplitude: (Float) -> Unit,
    private val onChunk: (FloatArray) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = SAMPLE_RATE * 6
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, 4096)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        record = audioRecord
        running = true
        audioRecord.startRecording()
        thread = Thread {
            val shorts = ShortArray(1024)
            val accumulated = ArrayList<Float>()
            while (running) {
                val read = try {
                    audioRecord.read(shorts, 0, shorts.size)
                } catch (_: Exception) {
                    break
                }
                if (read <= 0) continue
                var peak = 0f
                for (i in 0 until read) {
                    val sample = shorts[i] / 32768f
                    accumulated.add(sample)
                    val a = abs(sample)
                    if (a > peak) peak = a
                }
                onAmplitude(peak)
                if (accumulated.size >= CHUNK_SAMPLES) {
                    val chunk = FloatArray(accumulated.size) { accumulated[it] }
                    accumulated.clear()
                    onChunk(chunk)
                }
            }
            // Final partial chunk, if it holds at least half a second of speech.
            if (accumulated.size >= SAMPLE_RATE / 2) {
                val chunk = FloatArray(accumulated.size) { accumulated[it] }
                onChunk(chunk)
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        try {
            thread?.join(2000)
        } catch (_: Exception) {
        }
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        thread = null
    }
}
