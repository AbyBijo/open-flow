package com.abybijo.openflow

import android.app.ActivityManager
import android.content.Context

/**
 * On-device Whisper model catalogue.
 *
 * Every file downloads directly from Hugging Face, public dataset
 * ggerganov/whisper.cpp, no login and no proxy:
 * https://huggingface.co/ggerganov/whisper.cpp/resolve/main/<file>
 * The ggml.ggerganov.com mirror hosts the same files as a fallback.
 * SHA-256 values come from the whisper.cpp model table where known;
 * quantised (Q5_1) files carry no pinned SHA, so they are verified
 * by exact file size instead. Nothing is bundled in the APK.
 *
 * Sizes are the published whisper.cpp table values (MiB, rounded).
 * Medium and Large are downloads, never bundled: Large is 3 GB of
 * weights and needs an 8 GB-class phone to load safely — see
 * [WhisperModel.minRamGiB].
 */
data class WhisperModel(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeMb: Int,
    val blurb: String,
    val sha256: String? = null,
    /**
     * Total RAM this model needs to load and run safely, in GiB as
     * Android reports it (ActivityManager totalMem, which already
     * excludes kernel carveouts — a phone sold as "4 GB" reports
     * roughly 3.6 GiB, "6 GB" roughly 5.5 GiB, so the floors sit just
     * below each nominal tier).
     *
     * whisper.cpp reads the whole model file into RAM (weights), then
     * needs room for the KV cache and encoder buffers on top — about
     * half the weights again for a 30-second window — plus the app
     * itself and Android's own reservations. So a 1.5 GB Medium needs
     * a 6 GB-class phone, and a 3 GB Large needs an 8 GB-class phone:
     * on a smaller phone the model downloads fine and then the first
     * dictation dies in the native runtime with an out-of-memory kill.
     * The floors below are that arithmetic, checked against the
     * same tiering openWispr's DeviceFit uses (their ~1 GB Parakeet
     * + LLM pair needs a 5.4 GiB floor; their ~380 MB compact pair
     * needs 3.4 GiB).
     */
    val minRamGiB: Double = 1.8
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    val mirrorUrl: String
        get() = "https://ggml.ggerganov.com/$fileName"

    /** True when this phone can load and run this model. */
    fun fits(totalRamBytes: Long, lowRamDevice: Boolean): Boolean {
        if (totalRamBytes <= 0L) return true // Unreadable RAM: do not punish the phone for it.
        if (lowRamDevice && minRamGiB > 1.8) return false
        return totalRamBytes >= (minRamGiB * 1024L * 1024L * 1024L).toLong()
    }

    /** Plain-language reason shown when the model does not fit. */
    fun ramShortfall(totalRamBytes: Long): String {
        val needGb = minRamGiB.toInt().coerceAtLeast(2)
        val haveGb = if (totalRamBytes > 0L) "%.1f".format(totalRamBytes / (1024.0 * 1024.0 * 1024.0)) else "unknown"
        return "Needs a phone with at least $needGb GB RAM (this phone has $haveGb GB)."
    }
}

object ModelCatalog {
    /** Smallest download; the first model offered in Settings. */
    const val DEFAULT_MODEL_ID = "tiny-q5"

    val all: List<WhisperModel> = listOf(
        WhisperModel("tiny", "Tiny", "ggml-tiny.bin", 75, "Fastest, good for quick notes", "bd577a113a864445d4c299885e0cb97d4ba92b5f", 1.8),
        WhisperModel("base", "Base", "ggml-base.bin", 142, "Fast, better for everyday dictation", "465707469ff3a37a2b9b8d8f89f2f99de7299dac", 2.8),
        WhisperModel("small", "Small", "ggml-small.bin", 466, "Slower, best for long text and accents", "55356645c2b361a969dfd0ef2c5a50d530afd8d5", 3.4),
        WhisperModel("medium", "Medium", "ggml-medium.bin", 1537, "Much better accuracy, needs a 6 GB phone", null, 5.4),
        WhisperModel("large-turbo", "Large Turbo", "ggml-large-v3-turbo.bin", 1623, "Near-large accuracy, much faster, needs a 6 GB phone", null, 5.4),
        WhisperModel("large", "Large", "ggml-large-v3.bin", 3094, "Best accuracy, 3 GB download, needs an 8 GB phone", null, 7.4),
        WhisperModel("tiny-en", "Tiny.en", "ggml-tiny.en.bin", 75, "English only, fastest", "c78c86eb1a8faa21b369bcd33207cc90d64ae9df", 1.8),
        WhisperModel("base-en", "Base.en", "ggml-base.en.bin", 142, "English only, everyday dictation", "137c40403d78fd54d454da0f9bd998f78703390c", 2.8),
        WhisperModel("small-en", "Small.en", "ggml-small.en.bin", 466, "English only, best quality", "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022", 3.4),
        WhisperModel("medium-en", "Medium.en", "ggml-medium.en.bin", 1537, "English only, needs a 6 GB phone", null, 5.4),
        WhisperModel("tiny-q5", "Tiny Q5_1", "ggml-tiny-q5_1.bin", 32, "Smallest download, good on older phones", null, 1.8),
        WhisperModel("base-q5", "Base Q5_1", "ggml-base-q5_1.bin", 60, "Small download, balanced quality", null, 1.8),
        WhisperModel("small-q5", "Small Q5_1", "ggml-small-q5_1.bin", 190, "Best quality per megabyte", null, 2.8),
        WhisperModel("medium-q5", "Medium Q5_1", "ggml-medium-q5_1.bin", 514, "Medium quality, small download", null, 3.4)
    )

    fun byId(id: String): WhisperModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_MODEL_ID }

    /**
     * Best model for this phone: the largest full (non-quantised,
     * multilingual) model that fits its RAM, in quality order. .en and
     * Q5_1 variants stay available to pick by hand; the recommendation
     * itself is always a full model. Null only when even Tiny does not
     * fit (unreadable RAM is treated as fitting, so this is near
     * impossible in practice).
     */
    fun recommend(totalRamBytes: Long, lowRamDevice: Boolean): WhisperModel? {
        val order = listOf("large", "large-turbo", "medium", "small", "base", "tiny")
        return order.firstNotNullOfOrNull { id ->
            all.first { it.id == id }.takeIf { it.fits(totalRamBytes, lowRamDevice) }
        }
    }
}

/** Total physical RAM as Android reports it, or 0 if it cannot be read. */
fun totalRamBytes(context: Context): Long {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
    return runCatching {
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)
}

/** Android's own "this device is memory-constrained" flag (Go edition / low-RAM builds). */
fun isLowRamDevice(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return runCatching { am.isLowRamDevice }.getOrDefault(false)
}

/** "4.0 GB" for the RAM line in onboarding and Settings. */
fun formatRamGb(totalRamBytes: Long): String =
    if (totalRamBytes > 0L) "%.1f GB".format(totalRamBytes / (1024.0 * 1024.0 * 1024.0)) else "unknown RAM"
