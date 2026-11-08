package com.abybijo.openflow

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class BubbleColors(val first: Long, val second: Long)

val BUBBLE_COLORS = listOf(
    BubbleColors(0xFF5B5BF0, 0xFF8B5CF6),
    BubbleColors(0xFF0EA5E9, 0xFF6366F1),
    BubbleColors(0xFF10B981, 0xFF06B6D4),
    BubbleColors(0xFFF97316, 0xFFEC4899),
    BubbleColors(0xFF334155, 0xFF0F172A)
)

class MainViewModel(private val context: Context) : ViewModel() {
    val prefs: StateFlow<Prefs> = Store.prefsFlow(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Prefs())

    val hasKey = MutableStateFlow(KeyStore.hasGroqKey(context))

    fun refreshKeyState() {
        hasKey.value = KeyStore.hasGroqKey(context)
    }

    fun setTheme(theme: String) = viewModelScope.launch { Store.setTheme(context, theme) }
    fun setBubbleEnabled(enabled: Boolean) = viewModelScope.launch { Store.setBubbleEnabled(context, enabled) }
    fun setBubbleSize(size: Int) = viewModelScope.launch { Store.setBubbleSize(context, size) }
    fun setBubbleOpacity(opacity: Int) = viewModelScope.launch { Store.setBubbleOpacity(context, opacity) }
    fun setBubbleColor(index: Int) = viewModelScope.launch { Store.setBubbleColor(context, index) }
    fun setBubbleShape(shape: String) = viewModelScope.launch { Store.setBubbleShape(context, shape) }
    fun setSnapToEdge(snap: Boolean) = viewModelScope.launch { Store.setSnapToEdge(context, snap) }
    fun setShowWaveform(show: Boolean) = viewModelScope.launch { Store.setShowWaveform(context, show) }
    fun setAutoPunctuation(on: Boolean) = viewModelScope.launch { Store.setAutoPunctuation(context, on) }
    fun setHaptic(on: Boolean) = viewModelScope.launch { Store.setHaptic(context, on) }

    fun completeOnboarding() = viewModelScope.launch { Store.setOnboardingDone(context) }

    fun saveKey(key: String) {
        KeyStore.saveGroqKey(context, key)
        hasKey.value = true
    }

    /** Apply auto-punctuation, then record the dictation in Recent + today's usage. */
    fun recordDictation(rawText: String, audioSeconds: Int) {
        val text = applyPunctuation(rawText)
        viewModelScope.launch { Store.addDictation(context, text, audioSeconds) }
    }

    fun applyPunctuation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        if (!prefs.value.autoPunctuation) return trimmed
        return if (trimmed.last() in listOf('.', '!', '?')) trimmed else "$trimmed."
    }

    /** Transcribe a finished WAV file with the stored key. */
    suspend fun transcribeFile(wavFile: File): TranscribeResult {
        val key = KeyStore.getGroqKey(context)
            ?: return TranscribeResult.Error("No Groq API key saved. Add one in Settings.")
        return GroqClient.transcribe(key, wavFile)
    }
}
