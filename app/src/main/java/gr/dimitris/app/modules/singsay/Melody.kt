package gr.dimitris.app.modules.singsay

import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.Syllabifier
import java.text.Normalizer

enum class Pitch(val hz: Double) { LOW(196.0), HIGH(246.94) }

/**
 * How fast the melody moves, as a caregiver sets it. NORMAL is the pace [Melody] has always sung
 * at ([Melody.NOTE_MS], [Melody.GAP_MS]); SLOW gives every note longer to sound and a wider
 * silence after it, for a tired ear that needs the tune to move more slowly than his speech does.
 */
enum class Tempo(val noteMs: Int, val gapMs: Int) {
    NORMAL(550, 80),
    SLOW(750, 110);

    companion object {
        /** The pace nobody has ever asked to change. */
        val DEFAULT = NORMAL

        /** The stored name read back, with anything else — an old backup, a newer version — as [DEFAULT]. */
        fun named(name: String?): Tempo = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Which two frequencies HIGH and LOW mean, as a caregiver sets it. NORMAL is [Pitch]'s own two
 * frequencies — the register the melody has always sung in; LOW drops both notes for a voice, or a
 * day, a lower key sits easier under. [hz] is how the synth is meant to read a note's frequency —
 * never [Pitch.hz] directly — so the whole tune moves together when the key changes.
 */
enum class Key(val lowHz: Double, val highHz: Double) {
    NORMAL(196.0, 246.94),
    LOW(146.83, 185.0);

    /** The frequency this key gives one [Pitch]. */
    fun hz(pitch: Pitch): Double = if (pitch == Pitch.LOW) lowHz else highHz

    companion object {
        /** The key nobody has ever asked to change. */
        val DEFAULT = NORMAL

        /** The stored name read back, with anything else — an old backup, a newer version — as [DEFAULT]. */
        fun named(name: String?): Key = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

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
    /** [Tempo.NORMAL]'s own timing, kept as this object's default (an enum constant is not a compile-time constant). */
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
