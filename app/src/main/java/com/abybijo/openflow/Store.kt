package com.abybijo.openflow

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "openflow_prefs")

/** One finished dictation. [seconds] is the recorded audio length (0 for entries saved before 2.4.0). */
data class RecentEntry(val timestamp: Long, val text: String, val seconds: Int = 0)

data class Prefs(
    val onboardingDone: Boolean = false,
    val onboardingStep: Int = 0,
    val theme: String = "system", // system | light | dark
    val bubbleEnabled: Boolean = true,
    val bubbleSize: Int = 58,
    val bubbleOpacity: Int = 100,
    val bubbleColor: Int = 0,
    val bubbleShape: String = "circle", // circle | pill
    val snapToEdge: Boolean = true,
    val showWaveform: Boolean = true,
    val autoPunctuation: Boolean = true,
    val cleanTranscript: Boolean = true,
    val reviewBeforeInsert: Boolean = false,
    val engine: String = "groq", // groq | local
    val localModelId: String = ModelCatalog.DEFAULT_MODEL_ID,
    /** Onboarding engine choice ("groq" | "local", "" = not chosen yet). */
    val onboardingEngine: String = "",
    val haptic: Boolean = true,
    val usageDate: String = "",
    val usageSeconds: Int = 0,
    val recent: List<RecentEntry> = emptyList()
)

object Store {
    private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    private val ONBOARDING_STEP = intPreferencesKey("onboarding_step")
    private val THEME = stringPreferencesKey("theme")
    private val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
    private val BUBBLE_SIZE = intPreferencesKey("bubble_size")
    private val BUBBLE_OPACITY = intPreferencesKey("bubble_opacity")
    private val BUBBLE_COLOR = intPreferencesKey("bubble_color")
    private val BUBBLE_SHAPE = stringPreferencesKey("bubble_shape")
    private val SNAP = booleanPreferencesKey("snap_to_edge")
    private val WAVEFORM = booleanPreferencesKey("show_waveform")
    private val AUTO_PUNCT = booleanPreferencesKey("auto_punctuation")
    private val CLEAN_TRANSCRIPT = booleanPreferencesKey("clean_transcript")
    private val REVIEW_BEFORE_INSERT = booleanPreferencesKey("review_before_insert")
    private val ENGINE = stringPreferencesKey("engine")
    private val LOCAL_MODEL_ID = stringPreferencesKey("local_model_id")
    private val ONBOARDING_ENGINE = stringPreferencesKey("onboarding_engine")
    private val HAPTIC = booleanPreferencesKey("haptic")
    private val USAGE_DATE = stringPreferencesKey("usage_date")
    private val USAGE_SECONDS = intPreferencesKey("usage_seconds")
    private val RECENT_JSON = stringPreferencesKey("recent_json")

    fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun prefsFlow(context: Context): Flow<Prefs> = context.dataStore.data.map { p ->
        val today = todayString()
        val storedDate = p[USAGE_DATE] ?: ""
        val usageSeconds = if (storedDate == today) (p[USAGE_SECONDS] ?: 0) else 0
        Prefs(
            onboardingDone = p[ONBOARDING_DONE] ?: false,
            onboardingStep = (p[ONBOARDING_STEP] ?: 0).coerceIn(0, 5),
            theme = p[THEME] ?: "system",
            bubbleEnabled = p[BUBBLE_ENABLED] ?: true,
            bubbleSize = (p[BUBBLE_SIZE] ?: 58).coerceIn(44, 84),
            bubbleOpacity = (p[BUBBLE_OPACITY] ?: 100).coerceIn(40, 100),
            bubbleColor = (p[BUBBLE_COLOR] ?: 0).coerceIn(0, 4),
            bubbleShape = p[BUBBLE_SHAPE] ?: "circle",
            snapToEdge = p[SNAP] ?: true,
            showWaveform = p[WAVEFORM] ?: true,
            autoPunctuation = p[AUTO_PUNCT] ?: true,
            cleanTranscript = p[CLEAN_TRANSCRIPT] ?: true,
            reviewBeforeInsert = p[REVIEW_BEFORE_INSERT] ?: false,
            engine = (p[ENGINE] ?: "groq").takeIf { it == "local" } ?: "groq",
            localModelId = p[LOCAL_MODEL_ID] ?: ModelCatalog.DEFAULT_MODEL_ID,
            onboardingEngine = (p[ONBOARDING_ENGINE] ?: "").takeIf { it == "local" || it == "groq" } ?: "",
            haptic = p[HAPTIC] ?: true,
            usageDate = storedDate,
            usageSeconds = usageSeconds,
            recent = parseRecent(p[RECENT_JSON] ?: "[]")
        )
    }

    private fun parseRecent(json: String): List<RecentEntry> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                RecentEntry(o.optLong("t"), o.optString("text"), o.optInt("s", 0))
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun recentJson(entries: List<RecentEntry>): String {
        val arr = JSONArray()
        entries.sortedByDescending { it.timestamp }.take(100).forEach { e ->
            val o = JSONObject()
            o.put("t", e.timestamp)
            o.put("text", e.text)
            o.put("s", e.seconds)
            arr.put(o)
        }
        return arr.toString()
    }

    suspend fun setOnboardingDone(context: Context) {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    /** Persist the current onboarding step so a killed process resumes there. */
    suspend fun setOnboardingStep(context: Context, step: Int) {
        context.dataStore.edit { it[ONBOARDING_STEP] = step.coerceIn(0, 5) }
    }

    /** Persist the onboarding engine fork choice ("groq" | "local"). */
    suspend fun setOnboardingEngine(context: Context, engine: String) {
        context.dataStore.edit { it[ONBOARDING_ENGINE] = if (engine == "local") "local" else "groq" }
    }

    suspend fun setTheme(context: Context, theme: String) {
        context.dataStore.edit { it[THEME] = theme }
    }

    suspend fun setBubbleEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[BUBBLE_ENABLED] = enabled }
    }

    suspend fun setBubbleSize(context: Context, size: Int) {
        context.dataStore.edit { it[BUBBLE_SIZE] = size }
    }

    suspend fun setBubbleOpacity(context: Context, opacity: Int) {
        context.dataStore.edit { it[BUBBLE_OPACITY] = opacity }
    }

    suspend fun setBubbleColor(context: Context, index: Int) {
        context.dataStore.edit { it[BUBBLE_COLOR] = index }
    }

    suspend fun setBubbleShape(context: Context, shape: String) {
        context.dataStore.edit { it[BUBBLE_SHAPE] = shape }
    }

    suspend fun setSnapToEdge(context: Context, snap: Boolean) {
        context.dataStore.edit { it[SNAP] = snap }
    }

    suspend fun setShowWaveform(context: Context, show: Boolean) {
        context.dataStore.edit { it[WAVEFORM] = show }
    }

    suspend fun setAutoPunctuation(context: Context, on: Boolean) {
        context.dataStore.edit { it[AUTO_PUNCT] = on }
    }

    suspend fun setCleanTranscript(context: Context, on: Boolean) {
        context.dataStore.edit { it[CLEAN_TRANSCRIPT] = on }
    }

    suspend fun setReviewBeforeInsert(context: Context, on: Boolean) {
        context.dataStore.edit { it[REVIEW_BEFORE_INSERT] = on }
    }

    suspend fun setEngine(context: Context, engine: String) {
        context.dataStore.edit { it[ENGINE] = if (engine == "local") "local" else "groq" }
    }

    suspend fun setLocalModelId(context: Context, modelId: String) {
        context.dataStore.edit { it[LOCAL_MODEL_ID] = modelId }
    }

    suspend fun setHaptic(context: Context, on: Boolean) {
        context.dataStore.edit { it[HAPTIC] = on }
    }

    /**
     * Add a finished dictation: save to Recent and, when [countUsage] is
     * true, add its audio seconds to today's Groq usage. On-device
     * dictations pass false — they never consume Groq quota, so the
     * usage ring shows Groq usage only.
     */
    suspend fun addDictation(context: Context, text: String, audioSeconds: Int, countUsage: Boolean = true) {
        val current = context.dataStore.data.first()
        val today = todayString()
        val storedDate = current[USAGE_DATE] ?: ""
        val baseSeconds = if (storedDate == today) (current[USAGE_SECONDS] ?: 0) else 0
        val recent = parseRecent(current[RECENT_JSON] ?: "[]").toMutableList()
        recent.add(0, RecentEntry(System.currentTimeMillis(), text, audioSeconds.coerceAtLeast(0)))
        context.dataStore.edit {
            if (countUsage) {
                it[USAGE_DATE] = today
                it[USAGE_SECONDS] = baseSeconds + audioSeconds.coerceAtLeast(0)
            }
            it[RECENT_JSON] = recentJson(recent)
        }
    }
}
