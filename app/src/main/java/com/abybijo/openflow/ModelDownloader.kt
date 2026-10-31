package com.abybijo.openflow

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
 * Downloads a model directly from Hugging Face into app-internal storage.
 * The file is written to <file>.tmp and only renamed on success, so a
 * failed download never leaves a half model in place as a finished model.
 *
 * Pause/resume: on pause (or interruption) the .tmp file is KEPT, not
 * deleted. A resume re-sends the download with a Range header starting
 * at the .tmp length; if the server honours it (HTTP 206) the file is
 * appended to, otherwise the download restarts from scratch. A full
 * Cancel deletes the .tmp file explicitly from the ViewModel.
 * SHA-256 is verified when the catalogue pins one for the model.
 */
object ModelDownloader {
    fun download(
        model: WhisperModel,
        target: File,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        var lastError: Exception? = null
        // Primary: Hugging Face. Fallback: ggml mirror, same file.
        val urls = listOf(model.url, model.mirrorUrl)
        for (urlString in urls) {
            try {
                downloadFromUrl(urlString, tmp, onProgress, isCancelled)
                if (isCancelled()) {
                    // Paused/interrupted: keep .tmp so resume can continue.
                    throw InterruptedException("Download paused")
                }
                if (model.sha256 != null) {
                    val actual = sha256(tmp)
                    if (!actual.equals(model.sha256, ignoreCase = true)) {
                        tmp.delete()
                        throw IllegalStateException("Downloaded model failed verification (SHA-256 mismatch)")
                    }
                }
                if (tmp.length() <= 0L) {
                    tmp.delete()
                    throw IllegalStateException("Downloaded model is empty")
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
                // Do not delete .tmp here: a transient network failure can
                // also be resumed, and a corrupt partial is caught by the
                // SHA check (or by size) on the next attempt.
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
    }

    fun deletePartial(target: File) {
        File(target.parentFile, target.name + ".tmp").delete()
    }

    fun partialSize(target: File): Long =
        File(target.parentFile, target.name + ".tmp").let { if (it.exists()) it.length() else 0L }

    private fun downloadFromUrl(
        urlString: String,
        tmp: File,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val existing = if (tmp.exists()) tmp.length() else 0L
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "OpenFlow/1.0 (Android)")
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
            // Server ignored Range: restart cleanly, do not append to a
            // partial file or the model would be corrupt.
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
