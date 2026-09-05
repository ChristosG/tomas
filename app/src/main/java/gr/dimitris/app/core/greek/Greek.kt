package gr.dimitris.app.core.greek

import java.text.Normalizer
import java.util.Locale

object Greek {
    private val locale = Locale("el")
    private val vowelDigraphs = listOf("ου", "αι", "ει", "οι", "υι", "αυ", "ευ", "ηυ")
    private val consonantDigraphs = listOf("μπ", "ντ", "γκ", "τσ", "τζ")

    /** Trimmed and lower-cased with Greek rules (final sigma handled by the JDK). */
    fun normalize(word: String): String = word.trim().lowercase(locale)

    /** Removes tonos/dialytika: καλημέρα → καλημερα. */
    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** The first sound to give as a cue: a single letter, or a digraph that is really one sound. */
    fun firstSound(word: String): String {
        val w = stripAccents(normalize(word))
        if (w.isEmpty()) return ""
        val two = w.take(2)
        return if (two in vowelDigraphs || two in consonantDigraphs) two else w.take(1)
    }
}
