package com.abybijo.openflow

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF0A84FF)
private val LightScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    background = Color.White,
    surface = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black
)
private val DarkScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color(0xFF111111),
    onBackground = Color.White,
    onSurface = Color.White
)

enum class Tab { DICTATE, HISTORY, MODELS, SETTINGS }

@Composable
fun OpenFlowApp(
    state: UiState,
    isDark: Boolean,
    onSelectModel: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onToggleRecording: () -> Unit,
    onDeleteCurrent: () -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearError: () -> Unit,
    onSetThemeMode: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onShowTutorial: () -> Unit = {},
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {},
    onImportLocal: () -> Unit = {},
    onUseImported: (String) -> Unit = {},
    onSetCacheEnabled: (Boolean) -> Unit = {}
) {
    var tab by remember { mutableStateOf(Tab.DICTATE) }
    MaterialTheme(colorScheme = if (isDark) DarkScheme else LightScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text("OpenFlow", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Text(
                    "Free, open-source voice dictation. No account. On-device. Private.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))
                // Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFFEEEEEE))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tab.values().forEach { t ->
                        val selected = tab == t
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) Color.White else Color.Transparent)
                                .clickable { tab = t }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when (t) {
                                    Tab.DICTATE -> "Dictate"
                                    Tab.HISTORY -> "History"
                                    Tab.MODELS -> "Models"
                                    Tab.SETTINGS -> "Settings"
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Total words
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TOTAL WORDS SPOKEN", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${state.totalWords}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("Never resets — even when you delete a dictation.", color = Color.Gray, fontSize = 13.sp)
                    }
                }
                state.error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onClearError() },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9E9)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(message, modifier = Modifier.padding(14.dp), color = Color(0xFF8B0000))
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    Tab.DICTATE -> DictateScreen(
                        state = state,
                        onToggleRecording = onToggleRecording,
                        onDeleteCurrent = onDeleteCurrent,
                        onOpenModels = { tab = Tab.MODELS }
                    )
                    Tab.HISTORY -> HistoryScreen(state, onDeleteHistory)
                    Tab.MODELS -> ModelsScreen(state, onSelectModel, onDownload, onCancelDownload, onDeleteModel, onPauseDownload, onResumeDownload, onImportLocal, onUseImported)
                    Tab.SETTINGS -> SettingsScreen(state, onSetThemeMode, onClearHistory, onShowTutorial, onSetCacheEnabled)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Models download directly from Hugging Face (ggerganov/whisper.cpp) into the app's internal storage. Audio never leaves your phone.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ModelCard(model: WhisperModel, selected: Boolean, downloaded: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFEAF3FF) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (downloaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color(0xFF0A7A2E), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Downloaded", color = Color(0xFF0A7A2E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(model.bestFor, color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("${model.sizeMb} MB · ${model.params} params · ${model.speed} · ${model.accuracy}", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun DictateScreen(
    state: UiState,
    onToggleRecording: () -> Unit,
    onDeleteCurrent: () -> Unit,
    onOpenModels: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val model = state.selectedModel
    // Current model only — no download UI here by design.
    // Downloading, progress and deleting a model live only in Models.
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Current model", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(state.currentModelName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        when {
                            state.isModelLoaded -> "Cached in memory · ready instantly"
                            state.isCurrentModelReady -> "On device · loading into memory…"
                            else -> "Not downloaded yet · ${model.sizeMb} MB"
                        },
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenModels) {
                Text(if (state.isCurrentModelReady) "Change model in Models" else "Download in Models")
                Spacer(Modifier.width(6.dp))
                Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (!state.isCurrentModelReady) {
                Spacer(Modifier.height(6.dp))
                Text("Dictation stays locked until you download or import a model in Models.", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // Dictation card
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val infinite = rememberInfiniteTransition(label = "pulse")
            val pulse by infinite.animateFloat(
                initialValue = 1f,
                targetValue = if (state.isRecording) 1.08f else 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "pulse"
            )
            Box(
                modifier = Modifier
                    .size((92 * pulse).dp)
                    .clip(CircleShape)
                    .background(if (state.isRecording) Color(0xFFFF3B30) else Blue)
                    .clickable(enabled = state.isCurrentModelReady && !state.isDownloading && !state.isLoadingModel) { onToggleRecording() },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Microphone", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(8.dp))
            Waveform(amplitude = state.amplitude, active = state.isRecording)
            Text(
                when {
                    !state.isCurrentModelReady -> "Download or import a model in Models to unlock dictation"
                    state.isLoadingModel -> "Loading model… please wait, this can take several seconds for larger models"
                    state.isRecording -> "Listening… live dictation"
                    else -> "Tap the mic to start dictating"
                },
                color = Color.Gray,
                fontSize = 13.sp
            )
            state.statusMessage?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(message, color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (state.isRecording && state.elapsedSeconds > 0) {
                Text(
                    "Elapsed: %d:%02d".format(state.elapsedSeconds / 60, state.elapsedSeconds % 60),
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                Text(
                    state.currentText.ifBlank { "Your dictation will appear here, live, as you speak…" },
                    fontSize = 18.sp,
                    color = if (state.currentText.isBlank()) Color.Gray else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(state.currentText)) },
                    enabled = state.currentText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                OutlinedButton(onClick = onDeleteCurrent, enabled = state.currentText.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_trash), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete & save to history")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Current dictation: ${state.currentWordCount} words · Live, chunked Whisper transcription — audio never leaves your phone.",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun Waveform(amplitude: Float, active: Boolean) {
    Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
        val bars = 7
        val spacing = size.width / (bars + 1)
        for (i in 0 until bars) {
            val h = if (active) (8 + amplitude * 28 + (i % 3) * 4).coerceAtMost(32f) else 8f
            drawLine(
                color = Color(0xFF0A84FF),
                start = Offset(spacing * (i + 1), size.height / 2 - h / 2),
                end = Offset(spacing * (i + 1), size.height / 2 + h / 2),
                strokeWidth = 8f
            )
        }
    }
}

@Composable
private fun HistoryScreen(state: UiState, onDeleteHistory: (Long) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val format = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }
    if (state.history.isEmpty()) {
        Text("Nothing here yet. Delete a dictation and it will appear here.", color = Color.Gray, fontSize = 14.sp)
    }
    state.history.forEach { entry ->
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row {
                    Text("${entry.modelName} · ${entry.wordCount} words", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(format.format(Date(entry.timestamp)), color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(entry.text, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(entry.text)) }) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                    OutlinedButton(onClick = { onDeleteHistory(entry.id) }) { Text("Delete entry") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ModelsScreen(
    state: UiState,
    onSelectModel: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {},
    onImportLocal: () -> Unit = {},
    onUseImported: (String) -> Unit = {}
) {
    // The ONLY place model download is shown, by design.
    val model = state.selectedModel
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (state.downloadedIds.contains(model.id)) "${model.name} — downloaded & verified"
                else "${model.name} — ready to download",
                fontWeight = FontWeight.Bold
            )
            Text(
                "Direct download from huggingface.co/ggerganov/whisper.cpp — straight to the app's internal storage, never the Downloads folder. No login needed.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            if (state.isDownloading) {
                LinearProgressIndicator(progress = { state.downloadFraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    "Downloading… %.1f / %.0f MB".format(state.downloadDownloadedMb, state.downloadTotalMb),
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            } else if (state.downloadedIds.contains(model.id)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color(0xFF0A7A2E), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stored in app internal storage (models/${model.fileName})", fontSize = 13.sp, color = Color(0xFF0A7A2E))
                }
            } else {
                Text("Not downloaded yet · ${model.sizeMb} MB", color = Color.Gray, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isDownloading) {
                    OutlinedButton(onClick = onPauseDownload) { Text("Pause") }
                    OutlinedButton(onClick = onCancelDownload) { Text("Cancel") }
                } else if (state.isDownloadPaused) {
                    Button(onClick = onResumeDownload, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Resume download") }
                    OutlinedButton(onClick = onCancelDownload) { Text("Cancel") }
                } else if (!state.downloadedIds.contains(model.id)) {
                    Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download model")
                    }
                } else {
                    OutlinedButton(onClick = { onDeleteModel(model.id) }) { Text("Delete model") }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onImportLocal) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import local model (.bin)")
            }
            Text(
                "Pick a Whisper .bin you already downloaded from elsewhere (Downloads, Files, etc.). It is copied into the app's internal storage. If its name matches a known model, it is registered as that model; otherwise you can use it directly as a custom model.",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
    if (state.importedFiles.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Imported local models", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        state.importedFiles.forEach { fileName ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fileName, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (state.customModelFile == fileName) {
                        Text("In use", color = Color(0xFF0A7A2E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        OutlinedButton(onClick = { onUseImported(fileName) }) { Text("Use") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Source for every model: Hugging Face, ggerganov/whisper.cpp. Fallback mirror: ggml.ggerganov.com.", color = Color.Gray, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    ModelCatalog.groups.forEach { group ->
        Text(group, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        ModelCatalog.all.filter { it.group == group }.forEach { m ->
            ModelCard(m, m.id == state.selectedModelId, state.downloadedIds.contains(m.id)) {
                onSelectModel(m.id)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SettingsScreen(
    state: UiState,
    onSetThemeMode: (String) -> Unit,
    onClearHistory: () -> Unit,
    onShowTutorial: () -> Unit,
    onSetCacheEnabled: (Boolean) -> Unit = {}
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Developer", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text("Aby Bijo", fontWeight = FontWeight.Bold)
            Text(
                "Android & Web Developer · Computer Applications student, Mar Augustinos College, Ramapuram, Kottayam, Kerala",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("GitHub · AbyBijo", color = Blue, modifier = Modifier.clickable { uriHandler.openUri("https://github.com/AbyBijo") })
            Text("abybijo.web.app", color = Blue, modifier = Modifier.clickable { uriHandler.openUri("https://abybijo.web.app") })
            Text("LinkedIn · Aby Bijo", color = Blue, modifier = Modifier.clickable { uriHandler.openUri("https://www.linkedin.com/in/aby-bijo-102359282") })
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Support", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Found a bug or want a feature? Open an issue — that is the support channel for this open-source app.", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("OpenFlow Issues on GitHub", color = Blue, modifier = Modifier.clickable { uriHandler.openUri("https://github.com/AbyBijo/open-flow/issues") })
            Text("abybijo1978@gmail.com", color = Blue, modifier = Modifier.clickable { uriHandler.openUri("mailto:abybijo1978@gmail.com") })
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text("Theme", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                    if (state.themeMode == mode) {
                        Button(onClick = { onSetThemeMode(mode) }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onSetThemeMode(mode) }) { Text(label) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Active caching", fontWeight = FontWeight.SemiBold)
            Text(
                if (state.cacheEnabled) "On — downloaded models stay on device, and the current model stays loaded in memory so dictation starts instantly."
                else "Off — the model is unloaded when you stop dictating, so the next start pays the load time again.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.cacheEnabled) {
                    Button(onClick = { onSetCacheEnabled(true) }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Cache on") }
                    OutlinedButton(onClick = { onSetCacheEnabled(false) }) { Text("Cache off") }
                } else {
                    OutlinedButton(onClick = { onSetCacheEnabled(true) }) { Text("Cache on") }
                    Button(onClick = { onSetCacheEnabled(false) }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Cache off") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Default model: ${state.currentModelName} — change it in Models.", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text("History & storage", fontWeight = FontWeight.SemiBold)
            Text(
                "Downloaded models: " + if (state.downloadedIds.isEmpty()) "none" else state.downloadedIds.joinToString { ModelCatalog.byId(it).name } +
                    " · History entries: ${state.history.size} · Total words: ${state.totalWords}",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClearHistory) { Text("Clear history") }
                OutlinedButton(onClick = onShowTutorial) { Text("Show tutorial again") }
            }
            Spacer(Modifier.height(10.dp))
            Text("Total words spoken is a lifetime counter and cannot be reset from Settings — that is intentional.", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("OpenFlow v1.0.0 · MIT licence · Transcription language: Auto-detect · Audio never leaves your phone · No account, no analytics.", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

private data class OnboardingPage(val iconRes: Int, val title: String, val text: String)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val pages = listOf(
        OnboardingPage(R.drawable.ic_mic, "Welcome to OpenFlow", "Free, open-source voice dictation. No account, no subscription, no cloud."),
        OnboardingPage(R.drawable.ic_lock, "Private by design", "Everything runs on your phone with Whisper. Your voice is never uploaded, and there is no account to sign into."),
        OnboardingPage(R.drawable.ic_download, "Download a model first", "Go to Models, pick Tiny, Base or Small — plus English-only and Quantized versions — and download it directly from Hugging Face into the app's internal storage. Dictation unlocks after that."),
        OnboardingPage(R.drawable.ic_write, "Dictate, copy, keep count", "Tap the mic and speak — text appears live. Copy instantly, or Delete to clear the current dictation and save it to History. Your total words spoken never resets.")
    )
    val current = pages[page]
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Custom onboarding illustration, made for onboarding only:
                // a centred blue disc with a pulsing ring and the step icon,
                // plus a small animated detail under it that changes per step.
                OnboardingIllustration(iconRes = current.iconRes, pageIndex = page)
                Spacer(Modifier.height(24.dp))
                Text(
                    current.title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    current.text,
                    fontSize = 16.sp,
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (index == page) Blue else Color.Gray)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = onFinish) { Text("Skip") }
                    Button(
                        onClick = { if (page < pages.lastIndex) page += 1 else onFinish() },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Text(if (page == pages.lastIndex) "Get started" else "Next") }
                }
            }
        }
    }
}

/**
 * Onboarding-only illustration, built from scratch in code — not a reused
 * app icon placed on a page. A centred disc, a pulsing outer ring, the
 * step icon in the middle, and a per-step animated detail below:
 * sound waves (welcome), a shield arc (private), a download progress
 * arc (download), and text lines (dictate).
 */
@Composable
private fun OnboardingIllustration(iconRes: Int, pageIndex: Int) {
    val infinite = rememberInfiniteTransition(label = "onboarding")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "onboardingPulse"
    )
    val wave by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "onboardingWave"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        // Pulsing outer ring
        Box(
            modifier = Modifier
                .size((168 * pulse).dp)
                .clip(CircleShape)
                .background(Color(0xFFEAF3FF))
        )
        // Centred disc
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(Blue),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp)
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    // Per-step animated detail, drawn with simple shapes
    when (pageIndex) {
        0, 3 -> {
            // Sound waves / live text lines
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0.5f, 1f, 0.7f, 1f, 0.5f).forEachIndexed { i, _ ->
                    val h = (10 + (if (i % 2 == 0) wave * 18 else (1 - wave) * 18)).dp
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(h)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Blue)
                    )
                }
            }
        }
        1 -> {
            // Shield-style arc: a centred ring segment look, using a bordered disc
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(48.dp)) {
                    drawArc(
                        color = Color(0xFF0A84FF),
                        startAngle = -90f,
                        sweepAngle = 360f * wave,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                    )
                }
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Blue, modifier = Modifier.size(24.dp))
            }
        }
        2 -> {
            // Download progress track
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress = { wave },
                    modifier = Modifier.width(160.dp),
                    color = Blue
                )
                Spacer(Modifier.height(6.dp))
                Text("Downloading from Hugging Face", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}
