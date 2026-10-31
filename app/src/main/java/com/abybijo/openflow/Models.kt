package com.abybijo.openflow

/**
 * Model catalogue for OpenFlow v1.
 *
 * Every file is downloaded directly from Hugging Face, public dataset
 * ggerganov/whisper.cpp, with no login and no proxy:
 * https://huggingface.co/ggerganov/whisper.cpp/resolve/main/<file>
 * The ggml.ggerganov.com mirror hosts the same files as a fallback.
 * SHA-256 values are from the whisper.cpp model table where known.
 * Quantised (q5_1) files have no pinned SHA in v1, so they are
 * verified by exact file size only.
 */
data class WhisperModel(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeMb: Int,
    val params: String,
    val ram: String,
    val speed: String,
    val accuracy: String,
    val bestFor: String,
    val group: String,
    val sha256: String? = null
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    val mirrorUrl: String
        get() = "https://ggml.ggerganov.com/$fileName"
}

object ModelCatalog {
    val all: List<WhisperModel> = listOf(
        // Main, multilingual
        WhisperModel("tiny", "Tiny", "ggml-tiny.bin", 75, "39M", "~390 MB", "Fastest", "Good", "Quick notes, short phrases", "Main", "bd577a113a864445d4c299885e0cb97d4ba92b5f"),
        WhisperModel("base", "Base", "ggml-base.bin", 142, "74M", "~500 MB", "Fast", "Better", "Everyday dictation", "Main", "465707469ff3a37a2b9b8d8f89f2f99de7299dac"),
        WhisperModel("small", "Small", "ggml-small.bin", 466, "244M", "~1 GB", "Slower", "Best", "Long text, accents, punctuation", "Main", "55356645c2b361a969dfd0ef2c5a50d530afd8d5"),
        // English-only
        WhisperModel("tiny-en", "Tiny.en", "ggml-tiny.en.bin", 75, "39M", "~390 MB", "Fastest", "Good · English only", "English-only quick notes", "English-only", "c78c86eb1a8faa21b369bcd33207cc90d64ae9df"),
        WhisperModel("base-en", "Base.en", "ggml-base.en.bin", 142, "74M", "~500 MB", "Fast", "Better · English only", "English-only everyday dictation", "English-only", "137c40403d78fd54d454da0f9bd998f78703390c"),
        WhisperModel("small-en", "Small.en", "ggml-small.en.bin", 466, "244M", "~1 GB", "Slower", "Best · English only", "English-only long text", "English-only", "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022"),
        // Quantised q5_1: same models, much smaller files
        WhisperModel("tiny-q5", "Tiny Q5_1", "ggml-tiny-q5_1.bin", 32, "39M", "~390 MB", "Fastest", "Good · quantized", "Smallest download, older phones", "Quantized"),
        WhisperModel("base-q5", "Base Q5_1", "ggml-base-q5_1.bin", 60, "74M", "~500 MB", "Fast", "Good · quantized", "Small download, balanced quality", "Quantized"),
        WhisperModel("small-q5", "Small Q5_1", "ggml-small-q5_1.bin", 190, "244M", "~1 GB", "Balanced", "Very good · quantized", "Best quality per MB", "Quantized")
    )

    fun byId(id: String): WhisperModel = all.firstOrNull { it.id == id } ?: all.first()
    val groups: List<String> = listOf("Main", "English-only", "Quantized")
}
