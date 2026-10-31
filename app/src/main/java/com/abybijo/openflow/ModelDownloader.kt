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
 * cancelled or failed download never leaves a half model in place.
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
                    tmp.delete()
                    throw InterruptedException("Download cancelled")
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
                tmp.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                tmp.delete()
                // Try the next source; if this was the last one, rethrow below.
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
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "OpenFlow/1.0 (Android)")
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("Download failed (HTTP $code)")
        }
        val total = connection.contentLengthLong
        var downloaded = 0L
        connection.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled()) throw InterruptedException("Download cancelled")
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
