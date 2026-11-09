package com.abybijo.openflow

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
 */
data class WhisperModel(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeMb: Int,
    val blurb: String,
    val sha256: String? = null
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    val mirrorUrl: String
        get() = "https://ggml.ggerganov.com/$fileName"
}

object ModelCatalog {
    /** Smallest download; the first model offered in Settings. */
    const val DEFAULT_MODEL_ID = "tiny-q5"

    val all: List<WhisperModel> = listOf(
        WhisperModel("tiny", "Tiny", "ggml-tiny.bin", 75, "Fastest, good for quick notes", "bd577a113a864445d4c299885e0cb97d4ba92b5f"),
        WhisperModel("base", "Base", "ggml-base.bin", 142, "Fast, better for everyday dictation", "465707469ff3a37a2b9b8d8f89f2f99de7299dac"),
        WhisperModel("small", "Small", "ggml-small.bin", 466, "Slower, best for long text and accents", "55356645c2b361a969dfd0ef2c5a50d530afd8d5"),
        WhisperModel("tiny-en", "Tiny.en", "ggml-tiny.en.bin", 75, "English only, fastest", "c78c86eb1a8faa21b369bcd33207cc90d64ae9df"),
        WhisperModel("base-en", "Base.en", "ggml-base.en.bin", 142, "English only, everyday dictation", "137c40403d78fd54d454da0f9bd998f78703390c"),
        WhisperModel("small-en", "Small.en", "ggml-small.en.bin", 466, "English only, best quality", "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022"),
        WhisperModel("tiny-q5", "Tiny Q5_1", "ggml-tiny-q5_1.bin", 32, "Smallest download, good on older phones"),
        WhisperModel("base-q5", "Base Q5_1", "ggml-base-q5_1.bin", 60, "Small download, balanced quality"),
        WhisperModel("small-q5", "Small Q5_1", "ggml-small-q5_1.bin", 190, "Best quality per megabyte")
    )

    fun byId(id: String): WhisperModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_MODEL_ID }
}
