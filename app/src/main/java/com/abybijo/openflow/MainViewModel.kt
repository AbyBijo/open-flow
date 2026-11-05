package com.abybijo.openflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val isLoadingModel: Boolean = false,
    val isModelLoaded: Boolean = false,
    // Verified model-in-memory proof, shown on Dictate: file size on
    // disk, and what the load actually did (loaded in X s, or the exact
    // reason it failed). A silent preload that fails is how dictation
    // silently did nothing in v1.2 — this must never be silent again.
    val modelFileMb: Double = 0.0,
    val modelLoadDetail: String? = null,
    val isTranscribing: Boolean = false,
    val whisperDiag: String? = null,
    val canRetryLast: Boolean = false,
    val isDownloadPaused: Boolean = false,
    val statusMessage: String? = null,
    val importedFiles: List<String> = emptyList(),
    val customModelFile: String? = null,
    val cacheEnabled: Boolean = true,
    // Diagnostics, shown on Dictate so a blank screen is never a mystery:
    // if frames stay at 0 the mic is not delivering audio; if utterances
    // rise but text stays empty, Whisper returned empty for real audio.
    val framesReceived: Long = 0L,
    val utterancesTranscribed: Int = 0,
    val lastRms: Float = 0f,
    val lastRawResult: String? = null,
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
    // A custom imported file, when chosen, is the current model regardless
    // of the catalogue selection.
    val currentModelName: String get() = customModelFile ?: selectedModel.name
    val isCurrentModelReady: Boolean get() = customModelFile != null || isSelectedDownloaded
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
    // Whisper's context is NOT thread-safe: two chunks transcribed at once
    // corrupt each other and return empty text. Serialise all transcription.
    // This was a real failure mode for larger/quantized models, where one
    // chunk can take longer to transcribe than the next chunk takes to record.
    private val transcribeMutex = Mutex()

    init {
        viewModelScope.launch {
            val selected = Store.loadSelectedModel(context)
            val downloaded = Store.loadDownloaded(context)
            // Only keep models whose file is actually present on disk.
            val present = downloaded.filter { id ->
                Store.modelFile(context, ModelCatalog.byId(id)).exists()
            }.toSet()
            if (present != downloaded) Store.saveDownloaded(context, present)
            val imported = Store.loadImportedFiles(context)
                .filter { Store.importedFile(context, it).exists() }.sorted()
            val custom = Store.loadCustomModelFile(context)
                ?.takeIf { Store.importedFile(context, it).exists() }
            if (custom == null && Store.loadCustomModelFile(context) != null) {
                Store.saveCustomModelFile(context, null)
            }
            _state.update {
                it.copy(
                    selectedModelId = selected,
                    downloadedIds = present,
                    importedFiles = imported,
                    customModelFile = custom,
                    cacheEnabled = Store.loadCacheEnabled(context),
                    totalWords = Store.loadTotalWords(context),
                    history = Store.loadHistory(context),
                    onboardingDone = Store.loadOnboardingDone(context),
                    themeMode = Store.loadThemeMode(context)
                )
            }
            // Active caching: preload the current model in the background
            // so the first mic tap does not pay the model-load time.
            preloadCurrentModel()
        }
    }

    private fun currentModelFile(): java.io.File {
        val custom = _state.value.customModelFile
        return if (custom != null) Store.importedFile(context, custom)
        else Store.modelFile(context, _state.value.selectedModel)
    }

    /** Load the current model into memory now, if it is already on disk.
     *  This is verified, not assumed: the file is checked (exists, size
     *  vs the catalogue size for known models — a truncated download must
     *  never be loaded as if it were a real model), the load is timed,
     *  and success or the exact failure reason is shown on Dictate. */
    fun preloadCurrentModel() {
        val state = _state.value
        if (!state.isCurrentModelReady || state.isModelLoaded || state.isLoadingModel) return
        val file = currentModelFile()
        val fileMb = if (file.exists()) file.length() / 1_000_000.0 else 0.0
        _state.update { it.copy(modelFileMb = fileMb, isLoadingModel = true, statusMessage = "Loading ${state.currentModelName} model into memory…") }
        if (!WhisperEngine.isAvailable) {
            _state.update {
                it.copy(
                    isLoadingModel = false, isModelLoaded = false,
                    modelLoadDetail = "Native Whisper library did not load in this build — the model cannot be put into memory.",
                    error = "Whisper engine is not available in this build. The native library failed to load."
                )
            }
            return
        }
        if (!file.exists() || file.length() <= 0L) {
            _state.update {
                it.copy(
                    isLoadingModel = false, isModelLoaded = false,
                    modelLoadDetail = "Model file is missing or empty on disk.",
                    error = "Model file is missing. Download or import it again in Models."
                )
            }
            return
        }
        // Known catalogue model, wrong size = truncated / corrupt file.
        // Quantised models have no pinned SHA, so size is the check.
        val expectedMb = state.selectedModel.sizeMb.toDouble()
        val isKnown = state.customModelFile == null
        if (isKnown && fileMb < expectedMb * 0.85) {
            _state.update {
                it.copy(
                    isLoadingModel = false, isModelLoaded = false,
                    modelLoadDetail = "Model file is incomplete: ${"%.1f".format(fileMb)} MB on disk, expected about ${expectedMb.toInt()} MB.",
                    error = "Model file looks incomplete (${"%.1f".format(fileMb)} MB, expected ${expectedMb.toInt()} MB). Delete it in Models and download it again."
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val eng = engine ?: WhisperEngine().also { engine = it }
            val loaded = try {
                eng.load(file)
            } catch (_: OutOfMemoryError) {
                false
            } catch (_: Exception) {
                false
            }
            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        isLoadingModel = false,
                        isModelLoaded = loaded,
                        modelFileMb = fileMb,
                        modelLoadDetail = if (loaded) "Loaded into memory: ${state.currentModelName} · ${"%.1f".format(fileMb)} MB · ${"%.1f".format(seconds)} s" else "Load failed: ${state.currentModelName} · ${"%.1f".format(fileMb)} MB on disk · native load returned no model (file may be corrupt, or the phone ran out of memory for this model — try Tiny).",
                        statusMessage = if (loaded) "Model loaded into memory — dictation is ready." else null,
                        error = if (loaded) null else "Could not load the model into memory. Try Tiny, or delete and re-download this model."
                    )
                }
            }
        }
    }

    /** Force the model to be loaded into memory again from disk.
     *  Used by the "Reload model" button on Dictate. */
    fun reloadModel() {
        engine?.close()
        engine = null
        _state.update { it.copy(isModelLoaded = false, modelLoadDetail = null, error = null) }
        preloadCurrentModel()
    }

    fun setCacheEnabled(enabled: Boolean) {
        _state.update { it.copy(cacheEnabled = enabled) }
        viewModelScope.launch {
            Store.saveCacheEnabled(context, enabled)
            if (!enabled) {
                engine?.close()
                engine = null
                _state.update { it.copy(isModelLoaded = false) }
            } else {
                preloadCurrentModel()
            }
        }
    }

    fun selectModel(id: String) {
        // Selecting a catalogue model replaces a custom imported file.
        engine?.close()
        engine = null
        _state.update { it.copy(selectedModelId = id, customModelFile = null, isModelLoaded = false, error = null) }
        viewModelScope.launch {
            Store.saveSelectedModel(context, id)
            Store.saveCustomModelFile(context, null)
            preloadCurrentModel()
        }
    }

    fun useImportedFile(fileName: String) {
        engine?.close()
        engine = null
        _state.update { it.copy(customModelFile = fileName, isModelLoaded = false, error = null) }
        viewModelScope.launch {
            Store.saveCustomModelFile(context, fileName)
            preloadCurrentModel()
        }
    }

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var displayName: String? = null
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) displayName = cursor.getString(idx)
                    }
                }
                val safeName = (displayName ?: "imported-model.bin")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .takeIf { it.endsWith(".bin", ignoreCase = true) } ?: "${(displayName ?: "imported-model").replace(Regex("[^A-Za-z0-9._-]"), "_")}.bin"
                val target = Store.importedFile(context, safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not read the selected file")
                if (target.length() <= 0L) {
                    target.delete()
                    throw IllegalStateException("The selected file is empty")
                }
                // If the file is a known catalogue model, register it as
                // downloaded too, so it works exactly like a download.
                val known = ModelCatalog.all.firstOrNull { it.fileName.equals(safeName, ignoreCase = true) }
                val updatedDownloaded = if (known != null) _state.value.downloadedIds + known.id else _state.value.downloadedIds
                val updatedImported = (_state.value.importedFiles + safeName).sorted()
                Store.saveImportedFiles(context, updatedImported.toSet())
                if (known != null) Store.saveDownloaded(context, updatedDownloaded)
                withContext(Dispatchers.Main) {
                    if (known != null) {
                        selectModel(known.id)
                    } else {
                        useImportedFile(safeName)
                    }
                    _state.update {
                        it.copy(
                            downloadedIds = updatedDownloaded,
                            importedFiles = updatedImported,
                            statusMessage = "Imported $safeName (${target.length() / 1_000_000} MB) into app storage — ready to use.",
                            error = null
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(error = "Import failed: ${e.message}") }
                }
            }
        }
    }

    fun downloadSelected() {
        val model = _state.value.selectedModel
        if (_state.value.isDownloading) return
        startDownload(model, resume = false)
    }

    fun resumeDownload() {
        val model = _state.value.selectedModel
        if (_state.value.isDownloading) return
        startDownload(model, resume = true)
    }

    private fun startDownload(model: WhisperModel, resume: Boolean) {
        val target = Store.modelFile(context, model)
        val partialMb = ModelDownloader.partialSize(target) / 1_000_000.0
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isDownloading = true, isDownloadPaused = false,
                            downloadFraction = if (resume && partialMb > 0) (partialMb / model.sizeMb).toFloat() else 0f,
                            downloadDownloadedMb = if (resume) partialMb else 0.0,
                            downloadStatus = if (resume) "Resuming download…" else "Downloading from Hugging Face…",
                            error = null
                        )
                    }
                }
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
                        it.copy(isDownloading = false, isDownloadPaused = false, downloadFraction = 1f, downloadedIds = updated, downloadStatus = "Downloaded & verified")
                    }
                }
                preloadCurrentModel()
            } catch (e: InterruptedException) {
                // Pause keeps the partial file; cancel deletes it (below).
                withContext(Dispatchers.Main) {
                    if (_state.value.isDownloadPaused) {
                        _state.update { it.copy(isDownloading = false, downloadStatus = "Download paused — tap Resume to continue.") }
                    } else {
                        _state.update { it.copy(isDownloading = false, downloadStatus = "Download cancelled") }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isDownloading = false, isDownloadPaused = false, error = e.message ?: "Download failed") }
                }
            }
        }
    }

    fun pauseDownload() {
        _state.update { it.copy(isDownloadPaused = true) }
        downloadJob?.cancel()
    }

    fun cancelDownload() {
        _state.update { it.copy(isDownloadPaused = false) }
        downloadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            ModelDownloader.deletePartial(Store.modelFile(context, _state.value.selectedModel))
            withContext(Dispatchers.Main) {
                _state.update { it.copy(downloadFraction = 0f, downloadDownloadedMb = 0.0, downloadStatus = null) }
            }
        }
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
                _state.update { it.copy(downloadedIds = updated, isModelLoaded = false, downloadFraction = 0f, downloadStatus = null) }
            }
        }
    }

    fun startRecording() {
        val state = _state.value
        // Block double-taps while the model is still loading: a second
        // load() call frees the first context and breaks transcription.
        if (state.isRecording || state.isLoadingModel) return
        if (!state.isCurrentModelReady) {
            _state.update { it.copy(error = "Download or import a model first.") }
            return
        }
        if (!WhisperEngine.isAvailable) {
            _state.update { it.copy(error = "Whisper engine is not available in this build. The native library failed to load.") }
            return
        }
        // Active cache hit: model is already loaded in memory, so skip
        // the load step entirely and start listening immediately.
        if (state.isModelLoaded) {
            startRecorderWithEngine(engine!!)
            return
        }
        _state.update { it.copy(isLoadingModel = true, statusMessage = "Loading ${state.currentModelName} model…", error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val modelFile = currentModelFile()
            val eng = engine ?: WhisperEngine().also { engine = it }
            val loaded = eng.load(modelFile)
            if (!loaded) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isLoadingModel = false, isModelLoaded = false, statusMessage = null, error = "Could not load the downloaded Whisper model. Try deleting and re-downloading it.") }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isLoadingModel = false, isModelLoaded = true) }
            }
            startRecorderWithEngine(eng)
        }
    }

    // VAD-utterance state. Not a timer: an utterance starts on sustained
    // speech (RMS above threshold for 2 frames) and ends after ~2 s of
    // silence, on manual stop, or at Whisper's 30 s window cap. Only a
    // completed utterance is sent to Whisper, once, as final text.
    private val utteranceBuffer = ArrayList<Float>()
    private var utteranceSpeaking = false
    private var speechFrames = 0
    private var silenceFrames = 0
    private val utteranceLock = Any()
    // Full-session audio, separate from the VAD utterance buffer: every
    // frame recorded since the mic started, capped at 30 s. This is the
    // safety net — if VAD never fires (threshold / silence logic), the
    // text still lands on manual stop from this buffer instead of
    // vanishing. sessionTranscribedUpTo marks how much of it a VAD
    // flush already turned into text, so stop never transcribes the
    // same speech twice.
    private val sessionBuffer = ArrayList<Float>()
    private var sessionTranscribedUpTo = 0

    private fun flushUtterance(eng: WhisperEngine, reason: String) {
        val pcm: FloatArray
        synchronized(utteranceLock) {
            if (utteranceBuffer.size < Recorder.SAMPLE_RATE / 4) {
                utteranceBuffer.clear()
                utteranceSpeaking = false
                speechFrames = 0
                silenceFrames = 0
                return
            }
            pcm = FloatArray(utteranceBuffer.size) { utteranceBuffer[it] }
            utteranceBuffer.clear()
            utteranceSpeaking = false
            speechFrames = 0
            silenceFrames = 0
            // Everything recorded up to now has been handed to Whisper
            // (or discarded as too short to be speech), so the stop
            // fallback must not re-transcribe it.
            sessionTranscribedUpTo = sessionBuffer.size
        }
        transcribePcm(eng, pcm, reason)
    }

    /** Transcribe one chunk and append its text (or record the empty
     *  result visibly). Shared by VAD flushes and the stop fallback. */
    // Last audio chunk, kept so an empty result can be retried with
    // lenient settings instead of being lost forever.
    private var lastFailedPcm: FloatArray? = null

    fun retryLastUtterance() {
        val pcm = lastFailedPcm ?: return
        val eng = engine ?: return
        _state.update { it.copy(canRetryLast = false) }
        viewModelScope.launch(Dispatchers.Default) {
            transcribeMutex.withLock {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isTranscribing = true, statusMessage = "Retrying with lenient settings…") }
                }
                val raw = eng.transcribeDiag(pcm, lenient = true)
                val text = raw.trim()
                val diag = "Whisper: ${eng.lastSegments} segments · no-speech ${"%.2f".format(eng.lastNoSpeechProb)} · ${eng.lastTranscribeMs} ms (lenient retry)"
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        lastFailedPcm = null
                        val words = countWords(text).toLong()
                        val newTotal = _state.value.totalWords + words
                        _state.update { current ->
                            val joined = if (current.currentText.isBlank()) text else current.currentText + " " + text
                            current.copy(
                                currentText = joined, totalWords = newTotal,
                                utterancesTranscribed = current.utterancesTranscribed + 1,
                                lastRawResult = text, statusMessage = null, whisperDiag = diag, isTranscribing = false
                            )
                        }
                        viewModelScope.launch { Store.saveTotalWords(context, newTotal) }
                    } else {
                        _state.update {
                            it.copy(
                                utterancesTranscribed = it.utterancesTranscribed + 1,
                                lastRawResult = "(empty — Whisper returned no text, even lenient)",
                                statusMessage = "Whisper still returned no text for that audio. Try the Tiny model, or speak louder and closer to the mic.",
                                whisperDiag = diag, isTranscribing = false
                            )
                        }
                    }
                }
            }
        }
    }

    private fun transcribePcm(eng: WhisperEngine, pcm: FloatArray, reason: String) {
        viewModelScope.launch(Dispatchers.Default) {
            transcribeMutex.withLock {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isTranscribing = true, statusMessage = "Transcribing… Whisper is working on your audio now.") }
                }
                val raw = eng.transcribe(pcm)
                val text = raw.trim()
                val diag = "Whisper: ${eng.lastSegments} segments · no-speech ${"%.2f".format(eng.lastNoSpeechProb)} · ${eng.lastTranscribeMs} ms"
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        lastFailedPcm = null
                        val words = countWords(text).toLong()
                        val newTotal = _state.value.totalWords + words
                        _state.update { current ->
                            val joined = if (current.currentText.isBlank()) text else current.currentText + " " + text
                            current.copy(
                                currentText = joined, totalWords = newTotal,
                                utterancesTranscribed = current.utterancesTranscribed + 1,
                                lastRawResult = text, statusMessage = null, whisperDiag = diag, isTranscribing = false, canRetryLast = false
                            )
                        }
                        viewModelScope.launch { Store.saveTotalWords(context, newTotal) }
                    } else {
                        lastFailedPcm = pcm
                        _state.update {
                            it.copy(
                                utterancesTranscribed = it.utterancesTranscribed + 1,
                                lastRawResult = "(empty — Whisper returned no text for this utterance)",
                                statusMessage = "Heard audio, but Whisper returned no text ($reason). Tap Retry below to try it again with lenient settings.",
                                whisperDiag = diag, isTranscribing = false, canRetryLast = true
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startRecorderWithEngine(eng: WhisperEngine) {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(utteranceLock) {
                utteranceBuffer.clear()
                utteranceSpeaking = false
                speechFrames = 0
                silenceFrames = 0
                sessionBuffer.clear()
                sessionTranscribedUpTo = 0
            }
            val rec = Recorder(
                onAmplitude = { amp ->
                    _state.update { it.copy(amplitude = amp) }
                },
                onFrame = { frame, rms ->
                    _state.update { it.copy(framesReceived = it.framesReceived + 1, lastRms = rms) }
                    var toFlush = false
                    synchronized(utteranceLock) {
                        // Safety-net buffer: every frame, spoken or not,
                        // capped at Whisper's 30 s window.
                        sessionBuffer.addAll(frame.toList())
                        val cap = Recorder.SAMPLE_RATE * 30
                        if (sessionBuffer.size > cap) {
                            val dropped = sessionBuffer.size - cap
                            sessionBuffer.subList(0, dropped).clear()
                            sessionTranscribedUpTo = maxOf(0, sessionTranscribedUpTo - dropped)
                        }
                        if (rms > 0.01f) {
                            speechFrames += 1
                            silenceFrames = 0
                            if (speechFrames >= 2) utteranceSpeaking = true
                        } else if (utteranceSpeaking) {
                            silenceFrames += 1
                            if (silenceFrames >= 20) toFlush = true // ~2 s silence at 100 ms/frame
                        }
                        if (utteranceSpeaking) {
                            utteranceBuffer.addAll(frame.toList())
                            if (utteranceBuffer.size >= Recorder.SAMPLE_RATE * 30) toFlush = true
                        }
                    }
                    if (toFlush) flushUtterance(eng, "silence pause")
                }
            )
            recorder = rec
            try {
                rec.start()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isLoadingModel = false, statusMessage = null, error = "Microphone could not be started: ${e.message}") }
                }
                return@launch
            }
            // Live foreground: keep the app in the foreground while it
            // listens/transcribes, so Android does not throttle or kill
            // the transcription work mid-dictation.
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, OpenFlowService::class.java)
                )
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isRecording = true, isLoadingModel = false, statusMessage = "Listening… speak, then pause — your finished text lands after the pause.", error = null) }
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
        // Manual stop commits whatever utterance was in progress.
        var transcriptionLaunched = false
        engine?.let { eng ->
            val hadUtterance = synchronized(utteranceLock) { utteranceBuffer.size >= Recorder.SAMPLE_RATE / 4 }
            if (hadUtterance) transcriptionLaunched = true
            flushUtterance(eng, "manual stop")
            // Safety net: if VAD never fired, whatever was actually
            // recorded is still in sessionBuffer — transcribe the part
            // no VAD flush has covered yet, instead of losing it.
            val tail: FloatArray? = synchronized(utteranceLock) {
                val remaining = sessionBuffer.size - sessionTranscribedUpTo
                if (remaining < Recorder.SAMPLE_RATE / 4) null
                else FloatArray(remaining) { sessionBuffer[sessionTranscribedUpTo + it] }.also {
                    sessionTranscribedUpTo = sessionBuffer.size
                    utteranceBuffer.clear()
                    utteranceSpeaking = false
                }
            }
            if (tail != null) {
                transcriptionLaunched = true
                transcribePcm(eng, tail, "manual stop — full recording")
            }
        }
        elapsedJob?.cancel()
        elapsedJob = null
        try {
            context.stopService(Intent(context, OpenFlowService::class.java))
        } catch (_: Exception) {
        }
        // Active caching: by default the loaded model stays in memory so
        // the next dictation starts instantly. Only unload if the user
        // turned active caching off in Settings — and only when no
        // utterance is pending, otherwise the pending transcription
        // would run against a freed model and come back empty.
        // Do NOT unload here if a transcription was just launched: it
        // runs async against this same model, and freeing it now would
        // make it come back empty. The model stays loaded until the
        // next model change in that case.
        if (!_state.value.cacheEnabled && !transcriptionLaunched) {
            engine?.close()
            engine = null
            _state.update { it.copy(isModelLoaded = false) }
        }
        // The final partial chunk is transcribed by the recorder's thread
        // before stop() returns, via the serialised onChunk path above, so
        // do not clear the status here — the transcription may still land.
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
            modelName = state.currentModelName,
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
