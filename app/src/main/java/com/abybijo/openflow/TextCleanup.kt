package com.abybijo.openflow

/**
 * Deterministic transcript cleanup. No model, no network — a few small,
 * conservative rules applied to raw Whisper text before it is inserted or
 * saved. Nothing is reworded and no real words are dropped:
 *  - standalone filler words (um, uh, er, hmm, ah) are removed
 *  - a self-correction ("I mean", "sorry, I meant") keeps only the corrected part
 *  - a run of number words that parses as one complete number becomes digits
 *  - the first letter of the text and of each sentence is capitalised, and
 *    standalone "i" becomes "I"
 */
fun cleanTranscript(text: String): String {
    var out = text.trim()
    if (out.isEmpty()) return out

    // 1. Fillers at word boundaries, then tidy the gaps they leave.
    out = FILLER_REGEX.replace(out, " ")
    out = out.replace(Regex("\\s+"), " ").trim()
    out = out.replace(Regex("\\s+([,.!?])"), "$1")

    // 2. Self-correction: keep only what follows the last correction marker.
    val marker = CORRECTION_REGEX.findAll(out).lastOrNull()
    if (marker != null) {
        val after = out.substring(marker.range.last + 1).trim()
        if (after.isNotEmpty()) out = after
    }

    // 3. Spoken numbers: convert a run of number words only when the whole
    // run parses as one complete number ("twenty five" -> 25, but "one two"
    // is left alone — it is not a valid number phrase).
    out = convertSpokenNumbers(out)

    // 4. Capitalisation.
    out = capitaliseSentences(out)
    out = STANDALONE_I_REGEX.replace(out, "I")

    return out.trim()
}

private val FILLER_REGEX = Regex("(?i)\\b(um|uh|er|hmm|ah)\\b")
private val CORRECTION_REGEX = Regex("(?i)\\b(i mean|sorry,? i meant)\\b")
private val STANDALONE_I_REGEX = Regex("(?i)\\bi\\b")

private val ONES = mapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
    "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
    "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
    "eighteen" to 18, "nineteen" to 19
)
private val TENS = mapOf(
    "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
)
private val NUMBER_WORDS = ONES.keys + TENS.keys + setOf("hundred", "thousand", "and")

/**
 * Parse a run of number words as one number. Grammar: an optional
 * thousands group, then an optional hundreds group, then tens/ones.
 * "and" may appear between "hundred" and the rest. Anything else fails
 * and the run is left untouched.
 */
private fun parseNumberRun(words: List<String>): Int? {
    var index = 0
    var total = 0
    var seenAny = false

    fun parseBelowThousand(): Int? {
        var value = 0
        var seen = false
        // Hundreds: "three hundred" / "three hundred and twenty".
        if (index + 1 < words.size && ONES.containsKey(words[index]) && words[index + 1] == "hundred") {
            value += ONES.getValue(words[index]) * 100
            index += 2
            seen = true
            if (index < words.size && words[index] == "and") index++
        }
        // Tens and ones: "twenty", "twenty five", "five".
        if (index < words.size && TENS.containsKey(words[index])) {
            value += TENS.getValue(words[index])
            index++
            seen = true
            if (index < words.size && ONES.containsKey(words[index]) && ONES.getValue(words[index]) < 10) {
                value += ONES.getValue(words[index])
                index++
            }
        } else if (index < words.size && ONES.containsKey(words[index])) {
            value += ONES.getValue(words[index])
            index++
            seen = true
        }
        return if (seen) value else null
    }

    // Thousands: "two thousand", "two thousand three hundred".
    if (index + 1 < words.size && ONES.containsKey(words[index]) && words[index + 1] == "thousand") {
        total += ONES.getValue(words[index]) * 1000
        index += 2
        seenAny = true
        if (index < words.size && words[index] == "and") index++
    }
    val rest = parseBelowThousand() ?: return if (seenAny && index == words.size) total else null
    total += rest
    seenAny = true
    if (index != words.size) return null
    return if (seenAny) total else null
}

private fun convertSpokenNumbers(text: String): String {
    val tokens = text.split(Regex("\\s+"))
    val out = StringBuilder()
    var i = 0
    var first = true
    while (i < tokens.size) {
        val word = tokens[i]
        val lower = word.lowercase().trimEnd(',', '.', '!', '?')
        if (lower in NUMBER_WORDS) {
            // Collect the maximal run of number words (keeping trailing
            // punctuation attached to the last word out of the run).
            val run = mutableListOf<String>()
            var j = i
            var trailingPunct = ""
            while (j < tokens.size) {
                val w = tokens[j]
                val bare = w.lowercase().trimEnd(',', '.', '!', '?')
                if (bare !in NUMBER_WORDS) break
                run.add(bare)
                trailingPunct = w.substring(bare.length)
                j++
            }
            val value = parseNumberRun(run)
            if (value != null) {
                if (!first) out.append(' ')
                out.append(value).append(trailingPunct)
                first = false
                i = j
                continue
            }
        }
        if (!first) out.append(' ')
        out.append(word)
        first = false
        i++
    }
    return out.toString()
}

private fun capitaliseSentences(text: String): String {
    val chars = text.toCharArray()
    var sentenceStart = true
    for (i in chars.indices) {
        val c = chars[i]
        when {
            sentenceStart && c.isLetter() -> {
                chars[i] = c.uppercaseChar()
                sentenceStart = false
            }
            c in ".!?" -> sentenceStart = true
            c.isDigit() -> sentenceStart = false
        }
    }
    return String(chars)
}
