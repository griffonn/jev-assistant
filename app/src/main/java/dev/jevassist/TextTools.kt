package dev.jevassist

import java.text.Normalizer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything here is plain code on purpose. Jev can't write text, so free-text pieces
 * (a message body, a song name, a reminder title) are found by generating every candidate
 * span of the sentence in code and letting Jev *choose* the right one.
 * Numbers and durations are parsed here too, since TypeSafe recommends keeping math in code.
 */
object TextTools {

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private const val EDGE_PUNCT = ".,!?;:\"'“”‘’()"

    fun words(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    fun cleanSpan(s: String): String = s.trim().trim { it in EDGE_PUNCT }.trim()

    /**
     * Candidate spans of the utterance for a Jev Choice (max 255 options per Choice;
     * we keep headroom for "none"). Suffixes come first so long dictated messages are always
     * covered, then every short span so titles and song names in the middle are covered.
     */
    fun spanCandidates(words: List<String>, max: Int = 240): List<String> {
        val n = words.size
        val out = LinkedHashSet<String>()
        fun add(i: Int, j: Int) {
            if (i < j && out.size < max) {
                val s = cleanSpan(words.subList(i, j).joinToString(" "))
                if (s.isNotEmpty()) out.add(s)
            }
        }
        for (i in 1 until n) {
            add(i, n)
            for (k in 1..3) if (n - k > i) add(i, n - k)
        }
        var len = 1
        while (len <= n && out.size < max) {
            for (i in 0..n - len) add(i, i + len)
            len++
        }
        return out.toList()
    }

    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** "twenty five minutes" -> "25 minutes" (on-device recognizers often spell numbers out). */
    fun wordsToNumbers(s: String): String {
        val tokens = s.replace('-', ' ').split(Regex("\\s+"))
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val tk = tokens[i]
            val tens = TENS[tk]
            val unit = UNITS[tk]
            if (tens != null) {
                val next = tokens.getOrNull(i + 1)?.let { UNITS[it] }
                if (next != null && next in 1..9) {
                    out.add((tens + next).toString()); i += 2; continue
                }
                out.add(tens.toString()); i++; continue
            }
            if (unit != null) {
                out.add(unit.toString()); i++; continue
            }
            out.add(tk); i++
        }
        return out.joinToString(" ")
    }

    /** Sums every "<number> <unit>" in the text. Returns seconds, or null if none found. */
    fun parseDurationSeconds(text: String): Int? {
        var t = " " + wordsToNumbers(text.lowercase()) + " "
        t = t.replace(Regex("\\b(a\\s+)?quarter\\s+(of\\s+an\\s+)?hour\\b"), "15 minutes")
        t = t.replace(Regex("\\bhalf\\s+an?\\s+hour\\b"), "30 minutes")
        t = t.replace(Regex("\\bhalf\\s+an?\\s+minute\\b"), "30 seconds")
        t = t.replace(Regex("\\ban?\\s+(?=(hour|minute|second|min|sec))"), "1 ")
        t = t.replace(Regex("(\\d+)\\s+and\\s+a\\s+half"), "$1.5")
        val re = Regex("(\\d+(?:[.,]\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")
        var total = 0.0
        var found = false
        for (m in re.findAll(t)) {
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            total += v * when {
                unit.startsWith("h") -> 3600
                unit.startsWith("m") -> 60
                else -> 1
            }
            found = true
        }
        return if (found && total >= 1) total.roundToInt() else null
    }

    fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add(plural(h, "hour"))
        if (m > 0) parts.add(plural(m, "minute"))
        if (s > 0) parts.add(plural(s, "second"))
        return parts.joinToString(" ")
    }

    private fun plural(n: Int, w: String) = if (n == 1) "1 $w" else "$n ${w}s"

    /** A phone number said directly ("call 555 123 4567"), or null. */
    fun findPhoneNumber(text: String): String? {
        val m = Regex("\\+?\\d[\\d\\s().-]{3,}\\d").find(text) ?: return null
        val cleaned = m.value.filter { it.isDigit() || it == '+' }
        return if (cleaned.count { it.isDigit() } >= 5) cleaned else null
    }
}
