package com.abybijo.openflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abybijo.openflow.whisper.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val selectedModelId: String = "tiny",
    val downloadedIds: Set<String> = emptySet(),
    val isDownloading: Boolean = false,
    val downloadFraction: Float = 0f,
    val downloadDownloadedMb: Double = 0.0,
    val downloadTotalMb: Double = 0.0,
    val downloadStatus: String? = null,
    val isRecording: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val amplitude: Float = 0f,
    val currentText: String = "",
    val totalWords: Long = 0L,
    val history: List<HistoryEntry> = emptyList(),
    val error: String? = null,
    val onboardingDone: Boolean = false,
    val themeMode: String = "system"
) {
    val selectedModel: WhisperModel get() = ModelCatalog.byId(selectedModelId)
    val isSelectedDownloaded: Boolean get() = downloadedIds.contains(selectedModelId)
    val currentWordCount: Int get() = countWords(currentText)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var elapsedJob: Job? = null
    private var recorder: Recorder? = null
    private var engine: WhisperEngine? = null

    init {
        viewModelScope.launch {
            val selected = Store.loadSelectedModel(context)
            val downloaded = Store.loadDownloaded(context)
            // Only keep models whose file is actually present on disk.
            val present = downloaded.filter { id ->
                Store.modelFile(context, ModelCatalog.byId(id)).exists()
            }.toSet()
            if (present != downloaded) Store.saveDownloaded(context, present)
            _state.update {
                it.copy(
                    selectedModelId = selected,
                    downloadedIds = present,
                    totalWords = Store.loadTotalWords(context),
                    history = Store.loadHistory(context),
                    onboardingDone = Store.loadOnboardingDone(context),
                    themeMode = Store.loadThemeMode(context)
                )
            }
        }
    }

    fun selectModel(id: String) {
        _state.update { it.copy(selectedModelId = id, error = null) }
        viewModelScope.launch { Store.saveSelectedModel(context, id) }
    }

    fun downloadSelected() {
        val model = _state.value.selectedModel
        if (_state.value.isDownloading) return
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(isDownloading = true, downloadFraction = 0f, downloadStatus = "Downloading from Hugging Face…", error = null)
                    }
                }
                val target = Store.modelFile(context, model)
                ModelDownloader.download(
                    model = model,
                    target = target,
                    onProgress = { progress ->
                        val totalMb = if (progress.totalBytes > 0) progress.totalBytes / 1_000_000.0 else model.sizeMb.toDouble()
                        _state.update {
                            it.copy(
                                downloadFraction = progress.fraction,
                                downloadDownloadedMb = progress.downloadedBytes / 1_000_000.0,
                                downloadTotalMb = totalMb,
                                downloadStatus = "Downloading from Hugging Face…"
                            )
                        }
                    },
                    isCancelled = { downloadJob?.isCancelled == true }
                )
                val updated = _state.value.downloadedIds + model.id
                Store.saveDownloaded(context, updated)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(isDownloading = false, downloadFraction = 1f, downloadedIds = updated, downloadStatus = "Downloaded & verified")
                    }
                }
            } catch (e: InterruptedException) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isDownloading = false, downloadStatus = "Download cancelled") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isDownloading = false, error = e.message ?: "Download failed") }
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun deleteModel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_state.value.isRecording && id == _state.value.selectedModelId) {
                withContext(Dispatchers.Main) { stopRecording() }
            }
            Store.modelFile(context, ModelCatalog.byId(id)).delete()
            val updated = _state.value.downloadedIds - id
            Store.saveDownloaded(context, updated)
            engine?.close()
            engine = null
            withContext(Dispatchers.Main) {
                _state.update { it.copy(downloadedIds = updated, downloadFraction = 0f, downloadStatus = null) }
            }
        }
    }

    fun startRecording() {
        val state = _state.value
        if (state.isRecording) return
        if (!state.isSelectedDownloaded) {
            _state.update { it.copy(error = "Download the selected model first.") }
            return
        }
        if (!WhisperEngine.isAvailable) {
            _state.update { it.copy(error = "Whisper engine is not available in this build. The native library failed to load.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val modelFile = Store.modelFile(context, state.selectedModel)
            val eng = engine ?: WhisperEngine().also { engine = it }
            val loaded = eng.load(modelFile)
            if (!loaded) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(error = "Could not load the downloaded Whisper model. Try deleting and re-downloading it.") }
                }
                return@launch
            }
            val rec = Recorder(
                onAmplitude = { amp ->
                    _state.update { it.copy(amplitude = amp) }
                },
                onChunk = { pcm ->
                    viewModelScope.launch(Dispatchers.Default) {
                        val text = eng.transcribe(pcm).trim()
                        if (text.isNotBlank()) {
                            val words = countWords(text).toLong()
                            val newTotal = _state.value.totalWords + words
                            Store.saveTotalWords(context, newTotal)
                            withContext(Dispatchers.Main) {
                                _state.update { current ->
                                    val joined = if (current.currentText.isBlank()) text else current.currentText + " " + text
                                    current.copy(currentText = joined, totalWords = newTotal)
                                }
                            }
                        }
                    }
                }
            )
            recorder = rec
            try {
                rec.start()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(error = "Microphone could not be started: ${e.message}") }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isRecording = true, error = null) }
            }
            elapsedJob = viewModelScope.launch {
                var seconds = 0L
                while (true) {
                    delay(1000)
                    seconds += 1
                    _state.update { it.copy(elapsedSeconds = seconds) }
                }
            }
        }
    }

    fun stopRecording() {
        recorder?.stop()
        recorder = null
        elapsedJob?.cancel()
        elapsedJob = null
        _state.update { it.copy(isRecording = false, elapsedSeconds = 0L, amplitude = 0f) }
    }

    fun toggleRecording() {
        if (_state.value.isRecording) stopRecording() else startRecording()
    }

    /** Delete the current dictation, but save it to history first.
     *  The lifetime total word count is never reduced by a delete. */
    fun deleteCurrent() {
        val state = _state.value
        if (state.currentText.isBlank()) return
        if (state.isRecording) stopRecording()
        val entry = HistoryEntry(
            id = System.currentTimeMillis(),
            timestamp = System.currentTimeMillis(),
            modelName = state.selectedModel.name,
            text = state.currentText.trim(),
            wordCount = state.currentWordCount
        )
        val updated = listOf(entry) + state.history
        Store.saveHistory(context, updated)
        _state.update { it.copy(currentText = "", history = updated) }
    }

    fun deleteHistoryEntry(id: Long) {
        val updated = _state.value.history.filterNot { it.id == id }
        Store.saveHistory(context, updated)
        _state.update { it.copy(history = updated) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun completeOnboarding() {
        _state.update { it.copy(onboardingDone = true) }
        viewModelScope.launch { Store.saveOnboardingDone(context, true) }
    }

    fun showTutorialAgain() {
        _state.update { it.copy(onboardingDone = false) }
        viewModelScope.launch { Store.saveOnboardingDone(context, false) }
    }

    fun setThemeMode(mode: String) {
        _state.update { it.copy(themeMode = mode) }
        viewModelScope.launch { Store.saveThemeMode(context, mode) }
    }

    fun clearHistory() {
        Store.clearHistory(context)
        _state.update { it.copy(history = emptyList()) }
    }

    override fun onCleared() {
        recorder?.stop()
        engine?.close()
        super.onCleared()
    }
}
