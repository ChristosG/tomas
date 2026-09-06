package gr.dimitris.app.core.speech

import gr.dimitris.app.core.greek.Greek

/** Lenient comparison of what the recognizer heard with the target word. Encouragement only, never a gate. */
object SpeechMatch {
    fun key(s: String): String = Greek.stripAccents(Greek.normalize(s)).replace(Regex("[^\\p{L}\\p{N} ]"), "")

    fun matches(said: String, target: String): Boolean {
        val s = key(said); val t = key(target)
        if (t.isEmpty()) return false
        if (s == t || s.split(' ').any { it == t } || (t.length >= 4 && s.contains(t))) return true
        if (t.length < 4) return false
        return s.split(' ').any { levenshtein(it, t) <= 1 }
    }

    /**
     * The same question for a whole line: a dialogue turn or a sung phrase, where [matches] is far
     * too strict. «Θέλω έναν καφέ» said as «θέλω καφέ» is the man doing the exercise, not failing it,
     * and being told otherwise is exactly the wall spec §12 forbids.
     *
     * So: the whole string first, and then how much of the target he actually got out —
     * [OVERLAP] of its words is enough. Word for word, each compared as leniently as [matches]
     * compares a single one, so a missing final «ς» or an accent the recognizer put elsewhere costs
     * nothing.
     *
     * A one-word target keeps [matches] and nothing else: two thirds of one word is not a word, and
     * loosening the single-word case would let «νερό» pass for «καφές».
     */
    fun phraseMatches(heard: String, target: String): Boolean {
        if (matches(heard, target)) return true
        val wanted = words(target)
        if (wanted.size < 2) return false
        val said = words(heard)
        if (said.isEmpty()) return false
        val got = wanted.count { w -> said.any { it == w || (w.length >= 4 && levenshtein(it, w) <= 1) } }
        return got.toFloat() / wanted.size >= OVERLAP
    }

    private fun words(s: String): List<String> = key(s).split(' ').filter { it.isNotEmpty() }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    /**
     * How much of a phrase counts as having said it. Three words out of five, or two out of three:
     * enough that the small words he drops — an article, a «έναν» — never cost him the exercise,
     * and not so little that a different sentence altogether would pass.
     */
    const val OVERLAP = 0.6f
}
