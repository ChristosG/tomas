package gr.dimitris.app.core.greek

import java.text.Normalizer

/**
 * Rule-based Greek syllable splitter.
 *
 * Rules implemented (modern Greek school grammar):
 *  1. Vowel units: single vowels α ε η ι ο υ ω, the digraphs ου αι ει οι υι, and αυ ευ ηυ each count as ONE vowel.
 *     Two vowel units next to each other that do not form a digraph split: α-έ-ρας, σί-α.
 *     A vowel carrying dialytika (ϊ ϋ ΐ ΰ) never joins the preceding vowel into a digraph: μα-ϊ-μού, προ-ϊ-όν.
 *  2. A single consonant between vowels goes with the following vowel: κα-φές, νε-ρό.
 *  3. Two consonants between vowels stay together if a Greek word can begin with them
 *     (βλ βρ γλ γν γρ δρ θλ θν θρ κλ κν κρ κτ μν μπ ντ γκ πλ πν πρ πτ σβ σγ σθ σκ σλ σμ σν σπ στ σφ σχ τζ τμ τρ τσ φθ φλ φρ φτ χθ χλ χν χρ χτ);
 *     otherwise split between them: άν-θρω-πος (νθ cannot start a word), ελ-λά-δα, εκ-κλη-σί-α.
 *  4. Three or more consonants: keep them together if a word can begin with the first two; otherwise split after the first.
 *  5. Keep accents and letters exactly as given; only insert boundaries. Return null for blank input.
 */
object Syllabifier {

    private val vowels = setOf('α', 'ε', 'η', 'ι', 'ο', 'υ', 'ω')
    private val vowelDigraphs = setOf("ου", "αι", "ει", "οι", "υι", "αυ", "ευ", "ηυ")
    private val wordInitialClusters = setOf(
        "βλ", "βρ", "γλ", "γν", "γρ", "δρ", "θλ", "θν", "θρ", "κλ", "κν", "κρ", "κτ", "μν", "μπ", "ντ", "γκ",
        "πλ", "πν", "πρ", "πτ", "σβ", "σγ", "σθ", "σκ", "σλ", "σμ", "σν", "σπ", "στ", "σφ", "σχ", "τζ", "τμ",
        "τρ", "τσ", "φθ", "φλ", "φρ", "φτ", "χθ", "χλ", "χν", "χρ", "χτ"
    )

    /** Accent-stripped, lower-cased single-character key used for rule lookups only; the original char is kept in the output. */
    private fun bareChar(c: Char): Char = Greek.stripAccents(c.toString()).lowercase().firstOrNull() ?: c

    /** True if [c]'s NFD decomposition contains a combining diaeresis (ϊ ϋ ΐ ΰ): dialytika mark vowels that must stay separate from what precedes them. */
    private fun hasDialytika(c: Char): Boolean =
        Normalizer.normalize(c.toString(), Normalizer.Form.NFD).contains('\u0308')

    private fun isVowel(c: Char): Boolean = bareChar(c) in vowels

    /** [a] followed by [b] forms a digraph only when [b] carries no dialytika (rule 1). */
    private fun isDigraph(a: Char, b: Char): Boolean =
        !hasDialytika(b) && "${bareChar(a)}${bareChar(b)}" in vowelDigraphs

    private fun isWordInitialCluster(a: Char, b: Char): Boolean = "${bareChar(a)}${bareChar(b)}" in wordInitialClusters

    /** The vowel units of [word], as index ranges into it, greedily pairing up digraphs (rule 1). */
    private fun vowelUnits(word: String): List<IntRange> {
        val units = mutableListOf<IntRange>()
        var i = 0
        while (i < word.length) {
            if (!isVowel(word[i])) {
                i++
                continue
            }
            if (i + 1 < word.length && isVowel(word[i + 1]) && isDigraph(word[i], word[i + 1])) {
                units.add(i..i + 1)
                i += 2
            } else {
                units.add(i..i)
                i += 1
            }
        }
        return units
    }

    /**
     * Where to cut the consonant run between two vowel units, counted from the run's start (rules 2-4):
     * empty run or a single consonant moves wholly to the next syllable; two or more consonants move together
     * only if the first two of them could start a Greek word, otherwise just the first one stays behind.
     */
    private fun splitOffset(word: String, runStart: Int, runEnd: Int): Int {
        val length = runEnd - runStart
        return when {
            length <= 1 -> 0
            isWordInitialCluster(word[runStart], word[runStart + 1]) -> 0
            else -> 1
        }
    }

    // CHRIS: rewrite me.
    // Claude wrote this first version so the app could ship tonight; SyllabifierTest spells out the intended
    // contract in full, so treat those ten cases as the spec if you want to rebuild this as a learning exercise.
    fun syllables(word: String): List<String>? {
        if (word.isBlank()) return null
        val units = vowelUnits(word)
        if (units.isEmpty()) return listOf(word)

        val result = mutableListOf<String>()
        var syllableStart = 0
        for (index in units.indices) {
            val unit = units[index]
            if (index == units.lastIndex) {
                result.add(word.substring(syllableStart))
            } else {
                val runStart = unit.last + 1
                val runEnd = units[index + 1].first
                val boundary = runStart + splitOffset(word, runStart, runEnd)
                result.add(word.substring(syllableStart, boundary))
                syllableStart = boundary
            }
        }
        return result
    }

    fun firstSyllable(word: String): String? = syllables(word)?.firstOrNull()
}
