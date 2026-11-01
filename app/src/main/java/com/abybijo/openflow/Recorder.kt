package com.abybijo.openflow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Continuous 16 kHz mono PCM recorder for Whisper, built for the
 * VAD-utterance pattern (see research page):
 *
 * - Reads in ~100 ms frames (1600 samples) on its own thread, never
 *   blocked by inference. The ViewModel decides utterance boundaries
 *   from frame RMS (speech vs silence), it does NOT transcribe on a
 *   fixed timer. A fixed 3 s timer was the old design and is why
 *   dictation came back empty, cut-off or duplicated.
 * - Audio source is VOICE_RECOGNITION (tuned for speech recognition,
 *   with noise suppression/AGC disabled by default for that source),
 *   falling back to MIC if VOICE_RECOGNITION is unavailable.
 * - Fails loudly: bad buffer size, uninitialised recorder, or a
 *   negative read all throw, instead of silently recording zeros.
 */
class Recorder(
    private val onAmplitude: (Float) -> Unit,
    private val onFrame: (FloatArray, Float) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 1600 // 100 ms
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
        if (minBuffer <= 0) {
            throw IllegalStateException("AudioRecord: unsupported sample rate/channel/encoding (min buffer $minBuffer)")
        }
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE * 2)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            try { audioRecord.release() } catch (_: Exception) {}
            throw IllegalStateException("AudioRecord could not be initialised — check microphone permission and that no other app is holding the mic.")
        }
        record = audioRecord
        running = true
        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            try { audioRecord.release() } catch (_: Exception) {}
            throw IllegalStateException("AudioRecord started but is not recording — microphone may be in use by another app.")
        }
        thread = Thread {
            val shorts = ShortArray(FRAME_SAMPLES)
            while (running) {
                val read = try {
                    audioRecord.read(shorts, 0, shorts.size)
                } catch (_: Exception) {
                    break
                }
                if (read < 0) break
                if (read == 0) continue
                val frame = FloatArray(read)
                var peak = 0f
                var sumSq = 0.0
                for (i in 0 until read) {
                    val sample = shorts[i] / 32768f
                    frame[i] = sample
                    val a = abs(sample)
                    if (a > peak) peak = a
                    sumSq += (sample * sample).toDouble()
                }
                val rms = sqrt(sumSq / read).toFloat()
                onAmplitude(peak)
                onFrame(frame, rms)
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
