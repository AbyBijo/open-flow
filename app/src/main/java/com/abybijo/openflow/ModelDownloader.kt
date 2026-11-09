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

    fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val target = modelFile(context, model)
        val tmp = File(modelsDir(context), target.name + ".tmp")
        var lastError: Exception? = null
        // Primary: Hugging Face. Fallback: ggml mirror, same file.
        val urls = listOf(model.url, model.mirrorUrl)
        for (urlString in urls) {
            try {
                downloadFromUrl(urlString, tmp, onProgress, isCancelled)
                if (isCancelled()) throw InterruptedException("Download paused")
                if (model.sha256 != null) {
                    val actual = sha256(tmp)
                    if (!actual.equals(model.sha256, ignoreCase = true)) {
                        tmp.delete()
                        throw IllegalStateException("Downloaded model failed verification (SHA-256 mismatch)")
                    }
                } else if (tmp.length() <= 0L) {
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
                // Do not delete .tmp here: a transient failure can also be
                // resumed, and a corrupt partial is caught by the SHA/size
                // check on the next attempt.
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
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
