package com.abybijo.openflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed class VerifyResult {
    object Ok : VerifyResult()
    object InvalidKey : VerifyResult()
    data class Error(val message: String) : VerifyResult()
}

sealed class TranscribeResult {
    data class Ok(val text: String) : TranscribeResult()
    object InvalidKey : TranscribeResult()
    object RateLimited : TranscribeResult()
    data class Error(val message: String) : TranscribeResult()
}

/**
 * Groq Cloud speech-to-text. The model is Groq's whisper-large-v3-turbo;
 * the user supplies their own free API key (BYOK). No key is ever logged.
 */
object GroqClient {
    private const val BASE = "https://api.groq.com/openai/v1"
    private const val MODEL = "whisper-large-v3-turbo"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Live verification: GET /models with the key. HTTP 200 means the key works. */
    suspend fun verifyKey(key: String): VerifyResult = withContext(Dispatchers.IO) {
        if (!key.startsWith("gsk_") || key.length <= 12) {
            return@withContext VerifyResult.InvalidKey
        }
        try {
            val request = Request.Builder()
                .url("$BASE/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> VerifyResult.Ok
                    401 -> VerifyResult.InvalidKey
                    429 -> VerifyResult.Error("Groq daily limit reached — try tomorrow.")
                    else -> VerifyResult.Error("Groq returned HTTP ${response.code}. Please try again.")
                }
            }
        } catch (e: java.net.UnknownHostException) {
            VerifyResult.Error("No internet connection. Check your connection and try again.")
        } catch (e: java.net.SocketTimeoutException) {
            VerifyResult.Error("Groq took too long to respond. Please try again.")
        } catch (e: Exception) {
            VerifyResult.Error(e.message ?: "Could not reach Groq. Please try again.")
        }
    }

    /** Transcribe one finished WAV file (16 kHz mono). Returns the plain text. */
    suspend fun transcribe(key: String, wavFile: File): TranscribeResult =
        withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "recording.wav",
                        wavFile.asRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", MODEL)
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json")
                    .build()
                val request = Request.Builder()
                    .url("$BASE/audio/transcriptions")
                    .header("Authorization", "Bearer $key")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    when (response.code) {
                        200 -> {
                            val text = try {
                                JSONObject(raw).optString("text", "").trim()
                            } catch (_: Exception) {
                                ""
                            }
                            if (text.isEmpty()) {
                                TranscribeResult.Error("Groq returned no text. Please try speaking again.")
                            } else {
                                TranscribeResult.Ok(text)
                            }
                        }
                        401 -> TranscribeResult.InvalidKey
                        429 -> TranscribeResult.RateLimited
                        else -> {
                            val detail = try {
                                JSONObject(raw).optJSONObject("error")?.optString("message")
                            } catch (_: Exception) {
                                null
                            }
                            TranscribeResult.Error(
                                detail?.takeIf { it.isNotBlank() }
                                    ?: "Groq returned HTTP ${response.code}. Please try again."
                            )
                        }
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                TranscribeResult.Error("No internet connection.")
            } catch (e: java.net.SocketTimeoutException) {
                TranscribeResult.Error("Groq took too long to respond. Please try again.")
            } catch (e: Exception) {
                TranscribeResult.Error(e.message ?: "Could not reach Groq. Please try again.")
            }
        }
}
