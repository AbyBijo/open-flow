package com.abybijo.openflow

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * The "du du" start chime, like Wispr Flow: two short beeps when a
 * dictation starts. ToneGenerator, no audio asset — the app has no
 * binary assets to ship. Music stream, so the phone's silent mode
 * mutes it. Best effort only: any failure is swallowed, a chime
 * must never break starting a recording.
 */
object DictationChime {
    fun play(context: Context) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            val main = Handler(Looper.getMainLooper())
            main.postDelayed({
                try { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 110) } catch (_: Exception) { }
            }, 130)
            main.postDelayed({
                try { toneGen.release() } catch (_: Exception) { }
            }, 450)
        } catch (_: Exception) {
            // No chime — recording still starts.
        }
    }
}
