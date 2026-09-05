package gr.dimitris.app.modules.singsay

import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.Syllabifier
import java.text.Normalizer

enum class Pitch(val hz: Double) { LOW(196.0), HIGH(246.94) }

data class Note(val syllable: String, val pitch: Pitch, val wordIndex: Int)

/**
 * Two-note melody for Melodic Intonation Therapy: the stressed syllable of each word is HIGH,
 * everything else LOW. Monosyllables are LOW, even if they happen to carry a stray tonos. A
 * multi-syllable word with no written accent (e.g. an all-capitals word) gets HIGH on its last
 * syllable so it still has a peak. If none of that gives the phrase any HIGH note at all — a run
 * of unaccented monosyllables, say — the very last note is raised to HIGH so the melody he hears
 * always has a shape, never a flat line.
 */
object Melody {
    const val NOTE_MS = 550
    const val GAP_MS = 80

    fun forPhrase(text: String): List<Note> {
        val words = text.trim().split(Regex("\\s+"))
            .map { Greek.normalize(it.trim { c -> !c.isLetter() }) }
            .filter { it.isNotEmpty() }
        val notes = words.flatMapIndexed { wi, word ->
            val syl = Syllabifier.syllables(word) ?: return@flatMapIndexed emptyList()
            if (syl.size == 1) return@flatMapIndexed listOf(Note(syl[0], Pitch.LOW, wi))
            val stressed = syl.indexOfFirst { hasTonos(it) }.let { if (it == -1) syl.lastIndex else it }
            syl.mapIndexed { i, s -> Note(s, if (i == stressed) Pitch.HIGH else Pitch.LOW, wi) }
        }
        if (notes.isEmpty() || notes.any { it.pitch == Pitch.HIGH }) return notes
        return notes.toMutableList().also { it[it.lastIndex] = it.last().copy(pitch = Pitch.HIGH) }
    }

    /** True when the syllable carries a written accent (tonos, U+0301 in NFD). */
    private fun hasTonos(s: String): Boolean = Normalizer.normalize(s, Normalizer.Form.NFD).contains('́')
}
