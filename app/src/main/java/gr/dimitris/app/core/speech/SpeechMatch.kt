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
}
