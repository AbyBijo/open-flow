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

data class RecentEntry(val timestamp: Long, val text: String)

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
            onboardingStep = (p[ONBOARDING_STEP] ?: 0).coerceIn(0, 4),
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
                RecentEntry(o.optLong("t"), o.optString("text"))
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
            arr.put(o)
        }
        return arr.toString()
    }

    suspend fun setOnboardingDone(context: Context) {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    /** Persist the current onboarding step so a killed process resumes there. */
    suspend fun setOnboardingStep(context: Context, step: Int) {
        context.dataStore.edit { it[ONBOARDING_STEP] = step.coerceIn(0, 4) }
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

    suspend fun setHaptic(context: Context, on: Boolean) {
        context.dataStore.edit { it[HAPTIC] = on }
    }

    /** Add a finished dictation: save to Recent and add its audio seconds to today's usage. */
    suspend fun addDictation(context: Context, text: String, audioSeconds: Int) {
        val current = context.dataStore.data.first()
        val today = todayString()
        val storedDate = current[USAGE_DATE] ?: ""
        val baseSeconds = if (storedDate == today) (current[USAGE_SECONDS] ?: 0) else 0
        val recent = parseRecent(current[RECENT_JSON] ?: "[]").toMutableList()
        recent.add(0, RecentEntry(System.currentTimeMillis(), text))
        context.dataStore.edit {
            it[USAGE_DATE] = today
            it[USAGE_SECONDS] = baseSeconds + audioSeconds.coerceAtLeast(0)
            it[RECENT_JSON] = recentJson(recent)
        }
    }
}
