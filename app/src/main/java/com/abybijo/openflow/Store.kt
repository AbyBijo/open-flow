package com.abybijo.openflow

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

val Context.openFlowDataStore: DataStore<Preferences> by preferencesDataStore(name = "openflow")

data class HistoryEntry(
    val id: Long,
    val timestamp: Long,
    val modelName: String,
    val text: String,
    val wordCount: Int
)

object Store {
    private val totalWordsKey = longPreferencesKey("total_words")
    private val selectedModelKey = stringPreferencesKey("selected_model")
    private val downloadedKey = stringSetPreferencesKey("downloaded_models")
    private val onboardingDoneKey = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_done")
    private val themeModeKey = stringPreferencesKey("theme_mode") // system | light | dark

    suspend fun loadOnboardingDone(context: Context): Boolean =
        context.openFlowDataStore.data.first()[onboardingDoneKey] ?: false

    suspend fun saveOnboardingDone(context: Context, done: Boolean) {
        context.openFlowDataStore.edit { it[onboardingDoneKey] = done }
    }

    suspend fun loadThemeMode(context: Context): String =
        context.openFlowDataStore.data.first()[themeModeKey] ?: "system"

    suspend fun saveThemeMode(context: Context, mode: String) {
        context.openFlowDataStore.edit { it[themeModeKey] = mode }
    }

    fun clearHistory(context: Context) {
        historyFile(context).delete()
    }

    suspend fun loadTotalWords(context: Context): Long =
        context.openFlowDataStore.data.first()[totalWordsKey] ?: 0L

    suspend fun saveTotalWords(context: Context, value: Long) {
        context.openFlowDataStore.edit { it[totalWordsKey] = value }
    }

    suspend fun loadSelectedModel(context: Context): String =
        context.openFlowDataStore.data.first()[selectedModelKey] ?: "tiny"

    suspend fun saveSelectedModel(context: Context, id: String) {
        context.openFlowDataStore.edit { it[selectedModelKey] = id }
    }

    suspend fun loadDownloaded(context: Context): Set<String> =
        context.openFlowDataStore.data.first()[downloadedKey] ?: emptySet()

    suspend fun saveDownloaded(context: Context, ids: Set<String>) {
        context.openFlowDataStore.edit { it[downloadedKey] = ids }
    }

    private fun historyFile(context: Context): File = File(context.filesDir, "history.json")

    fun loadHistory(context: Context): List<HistoryEntry> {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HistoryEntry(
                    id = o.optLong("id"),
                    timestamp = o.optLong("timestamp"),
                    modelName = o.optString("model"),
                    text = o.optString("text"),
                    wordCount = o.optInt("words")
                )
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHistory(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.sortedByDescending { it.timestamp }.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("timestamp", e.timestamp)
            o.put("model", e.modelName)
            o.put("text", e.text)
            o.put("words", e.wordCount)
            array.put(o)
        }
        historyFile(context).writeText(array.toString())
    }

    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, model: WhisperModel): File =
        File(modelsDir(context), model.fileName)
}

fun countWords(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
