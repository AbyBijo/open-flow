package com.abybijo.openflow

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
}

/**
 * Downloads a Whisper model directly from Hugging Face into internal
 * storage only (filesDir/models/). The bytes go to a .tmp file that is
 * only renamed to the finished model once the download verifies, so a
 * failed download never leaves a half model behind as a finished one.
 *
 * Pause/resume: stopping (or a dropped connection) keeps the .tmp
 * file. Resuming re-sends the download with a Range header starting
 * at the .tmp length; if the server honours it (HTTP 206) the file is
 * appended to, otherwise the download restarts from scratch. Cancel
 * deletes the .tmp file. Models with a pinned SHA-256 are verified
 * against it; the rest are verified by exact file size.
 *
 * This is a blocking call — run it off the main thread (the download
 * service does). [isCancelled] is polled between buffer reads.
 */
object ModelDownloader {
    /** Download rounds (primary + mirror) before a failure is final. */
    const val MAX_ATTEMPTS = 3

    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, model: WhisperModel): File =
        File(modelsDir(context), model.fileName)

    /**
     * Downloaded means the file exists and is at least 90% of its
     * catalogue size — a truncated download never counts as ready,
     * and the exact byte count is not required (published sizes are
     * rounded). Models with a pinned SHA are verified exactly at
     * download time, not on every launch.
     */
    fun isDownloaded(context: Context, model: WhisperModel): Boolean {
        val file = modelFile(context, model)
        return file.exists() && file.length() >= model.sizeMb * 1024L * 1024L * 9 / 10
    }

    fun partialSize(context: Context, model: WhisperModel): Long {
        val tmp = File(modelsDir(context), model.fileName + ".tmp")
        return if (tmp.exists()) tmp.length() else 0L
    }

    fun deleteModel(context: Context, model: WhisperModel) {
        modelFile(context, model).delete()
        File(modelsDir(context), model.fileName + ".tmp").delete()
    }

    fun deletePartial(context: Context, model: WhisperModel) {
        File(modelsDir(context), model.fileName + ".tmp").delete()
    }

    /** Free bytes must cover the model plus a 100 MB margin for the OS. */
    fun hasEnoughSpace(context: Context, model: WhisperModel): Boolean {
        return try {
            val free = StatFs(modelsDir(context).absolutePath).availableBytes
            free >= model.sizeMb * 1024L * 1024L + 100L * 1024L * 1024L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Download with automatic retry: an HTTP error or a dropped
     * connection must not fail the download outright. Transient
     * failures are retried (up to [MAX_ATTEMPTS] rounds over both
     * mirrors, with backoff), resuming from the partial .tmp file
     * every time. The partial is kept for resume on transient
     * failure; only a genuinely corrupt file (SHA or size mismatch
     * after a full download) is deleted so the next attempt starts
     * clean. [onRetry] reports each retry so the UI can say
     * "retrying" honestly instead of flashing a failure.
     */
    fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
        onRetry: ((attempt: Int, maxAttempts: Int, message: String) -> Unit)? = null
    ) {
        val target = modelFile(context, model)
        val tmp = File(modelsDir(context), target.name + ".tmp")
        var lastError: Exception? = null
        // Primary: Hugging Face. Fallback: ggml mirror, same file.
        val urls = listOf(model.url, model.mirrorUrl)
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            for (urlString in urls) {
                try {
                    downloadFromUrl(urlString, tmp, onProgress, isCancelled)
                    if (isCancelled()) throw InterruptedException("Download paused")
                    if (model.sha256 != null) {
                        val actual = sha256(tmp)
                        if (!actual.equals(model.sha256, ignoreCase = true)) {
                            // Corrupt, not transient: delete the partial
                            // so the retry starts clean, never resumes it.
                            tmp.delete()
                            throw IllegalStateException("Downloaded model failed verification (SHA-256 mismatch)")
                        }
                    } else {
                        // No pinned SHA: the size must still land — a
                        // short file is a failed download, not a model.
                        val minBytes = model.sizeMb * 1024L * 1024L * 9 / 10
                        if (tmp.length() < minBytes) {
                            tmp.delete()
                            throw IllegalStateException("Downloaded model is incomplete")
                        }
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    return
                } catch (e: InterruptedException) {
                    // Keep .tmp for resume; the caller decides pause vs cancel.
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    // Transient failure: keep .tmp for resume. A corrupt
                    // partial was already deleted above by the check
                    // that caught it, so the next attempt starts clean.
                    if (!isTransient(e)) throw e
                }
            }
            if (attempt < MAX_ATTEMPTS) {
                onRetry?.invoke(attempt, MAX_ATTEMPTS, lastError?.message ?: "Download failed")
                backoff(attempt, isCancelled)
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
    }

    /** True for failures worth retrying: network trouble, server-side HTTP errors, and a short file (truncated stream — the partial is deleted and the retry starts clean). A SHA mismatch is not transient: the source itself is wrong, and retrying would just re-download the same bad bytes. */
    private fun isTransient(e: Exception): Boolean {
        if (e is java.io.IOException) return true
        val message = e.message ?: return false
        return message.contains("HTTP 5") || message.contains("HTTP 429") ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("Unable to resolve", ignoreCase = true) ||
            message.contains("incomplete", ignoreCase = true)
    }

    /** Backoff between attempts (1 s, then 2 s), interruptible so Pause still works mid-wait. */
    private fun backoff(attempt: Int, isCancelled: () -> Boolean) {
        val waitMs = (1000L shl (attempt - 1)).coerceAtMost(4000L)
        var waited = 0L
        while (waited < waitMs) {
            if (isCancelled()) throw InterruptedException("Download paused")
            Thread.sleep(100)
            waited += 100
        }
    }

    private fun downloadFromUrl(
        urlString: String,
        tmp: File,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val existing = if (tmp.exists()) tmp.length() else 0L
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "OpenFlow/2.3 (Android)")
        if (existing > 0L) {
            connection.setRequestProperty("Range", "bytes=$existing-")
        }
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("Download failed (HTTP $code)")
        }
        val resumed = code == 206
        if (!resumed && existing > 0L) {
            // Server ignored Range: restart cleanly, or the model would
            // be corrupt from appending a fresh copy onto a partial.
            tmp.delete()
        }
        val remaining = connection.contentLengthLong
        val total = if (resumed) existing + remaining else remaining
        var downloaded = if (resumed) existing else 0L
        connection.inputStream.use { input ->
            FileOutputStream(tmp, resumed).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled()) throw InterruptedException("Download paused")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(DownloadProgress(downloaded, total))
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
