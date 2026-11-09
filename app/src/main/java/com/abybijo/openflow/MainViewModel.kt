package com.abybijo.openflow

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
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

/** Bubble size presets (dp): Small / Medium / Large / Extra Large. No sliders. */
val BUBBLE_SIZE_PRESETS = listOf(48, 58, 68, 80)
val BUBBLE_SIZE_NAMES = listOf("Small", "Medium", "Large", "Extra Large")

/** Bubble opacity presets (%): Subtle / Balanced / Solid. No sliders. */
val BUBBLE_OPACITY_PRESETS = listOf(60, 80, 100)
val BUBBLE_OPACITY_NAMES = listOf("Subtle", "Balanced", "Solid")

class MainViewModel(private val context: Context) : ViewModel() {
    /**
     * One shared upstream for prefs and prefsReady. Two separate
     * DataStore collections could deliver their first values a frame
     * apart — prefsReady true while prefs still held the default
     * (onboarding not done) — and the app would flash the wrong
     * screen. Here the ready flag flips inside the same emission that
     * delivers the loaded prefs, so the UI can never observe one
     * without the other.
     */
    private val _prefsReady = MutableStateFlow(false)
    val prefs: StateFlow<Prefs> = Store.prefsFlow(context)
        .onEach { _prefsReady.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Prefs())

    /** True once the stored preferences have actually loaded (first DataStore emission). */
    val prefsReady: StateFlow<Boolean> = _prefsReady

    val hasKey = MutableStateFlow(KeyStore.hasGroqKey(context))

    /** On-disk download state, refreshed from filesDir/models/. */
    private val diskDownloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())

    /**
     * Download state per model: the live service state wins over the
     * on-disk state, so a running download shows its progress and a
     * finished one shows as downloaded.
     */
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> =
        combine(diskDownloadStates, ModelDownloadStates.flow) { disk, live -> disk + live }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** True when the last transcribeFile call actually used Groq (demo usage accounting). */
    var lastTranscribeUsedGroq: Boolean = true
        private set

    init {
        refreshModelStates()
        // A finished download changes the on-disk truth; re-scan then.
        viewModelScope.launch {
            ModelDownloadStates.flow.collect { live ->
                if (live.values.any { it is ModelDownloadState.Downloaded || it is ModelDownloadState.Failed }) {
                    refreshModelStates()
                }
            }
        }
    }

    fun refreshModelStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val states = ModelCatalog.all.associate { model ->
                model.id to if (ModelDownloader.isDownloaded(context, model)) {
                    ModelDownloadState.Downloaded
                } else {
                    val partial = ModelDownloader.partialSize(context, model)
                    if (partial > 0L) ModelDownloadState.Paused(partial)
                    else ModelDownloadState.NotDownloaded
                }
            }
            diskDownloadStates.value = states
        }
    }

    fun downloadModel(modelId: String) {
        val model = ModelCatalog.byId(modelId)
        viewModelScope.launch(Dispatchers.IO) {
            if (ModelDownloader.isDownloaded(context, model)) {
                ModelDownloadStates.update(modelId, ModelDownloadState.Downloaded)
                return@launch
            }
            if (!ModelDownloader.hasEnoughSpace(context, model)) {
                ModelDownloadStates.update(
                    modelId,
                    ModelDownloadState.Failed(
                        "Not enough storage for ${model.name} (${model.sizeMb} MB). Free some space and try again."
                    )
                )
                return@launch
            }
            ContextCompat.startForegroundService(
                context, ModelDownloadService.downloadIntent(context, modelId)
            )
        }
    }

    fun pauseDownload(modelId: String) {
        context.startService(ModelDownloadService.pauseIntent(context))
    }

    fun cancelDownload(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            context.startService(ModelDownloadService.cancelIntent(context))
            ModelDownloader.deletePartial(context, ModelCatalog.byId(modelId))
            ModelDownloadStates.update(modelId, ModelDownloadState.NotDownloaded)
            refreshModelStates()
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ModelDownloader.deleteModel(context, ModelCatalog.byId(modelId))
            ModelDownloadStates.update(modelId, ModelDownloadState.NotDownloaded)
            refreshModelStates()
        }
    }

    fun setEngine(engine: String) {
        if (engine != "local") releaseLocalEngine()
        viewModelScope.launch { Store.setEngine(context, engine) }
        if (engine == "local") {
            // Warm the selected model in the background, so the first
            // on-device dictation does not pay the load cost.
            viewModelScope.launch { warmLocalEngine(context, prefs.value.localModelId) }
        }
    }

    fun setLocalModel(modelId: String) {
        releaseLocalEngine()
        viewModelScope.launch { Store.setLocalModelId(context, modelId) }
        viewModelScope.launch { warmLocalEngine(context, modelId) }
    }

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
    fun setCleanTranscript(on: Boolean) = viewModelScope.launch { Store.setCleanTranscript(context, on) }
    fun setReviewBeforeInsert(on: Boolean) = viewModelScope.launch { Store.setReviewBeforeInsert(context, on) }
    fun setStopOnLongPause(on: Boolean) = viewModelScope.launch { Store.setStopOnLongPause(context, on) }
    fun setHaptic(on: Boolean) = viewModelScope.launch { Store.setHaptic(context, on) }

    fun completeOnboarding() = viewModelScope.launch { Store.setOnboardingDone(context) }

    fun setOnboardingStep(step: Int) = viewModelScope.launch { Store.setOnboardingStep(context, step) }

    fun setOnboardingEngine(engine: String) = viewModelScope.launch { Store.setOnboardingEngine(context, engine) }

    fun saveKey(key: String) {
        KeyStore.saveGroqKey(context, key)
        hasKey.value = true
    }

    /** Apply auto-punctuation, then record the dictation in Recent + today's usage. */
    fun recordDictation(rawText: String, audioSeconds: Int, countUsage: Boolean = true) {
        val text = applyPunctuation(rawText)
        viewModelScope.launch { Store.addDictation(context, text, audioSeconds, countUsage) }
    }

    fun applyPunctuation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        if (!prefs.value.autoPunctuation) return trimmed
        return if (trimmed.last() in listOf('.', '!', '?')) trimmed else "$trimmed."
    }

    /** Transcribe a finished WAV file with the selected engine (Groq or on-device).
     * [samples] is the same audio in memory — the on-device path uses it
     * directly and never reads the file back. */
    suspend fun transcribeFile(wavFile: File, samples: FloatArray? = null): TranscribeResult {
        val (result, usedGroq) = transcribeWithFallback(
            context, prefs.value.engine, prefs.value.localModelId, wavFile, samples
        )
        lastTranscribeUsedGroq = usedGroq
        return result
    }
}
