package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.greek.Greek

/**
 * The five cue levels for one item. A level disappears when it is no help of its own: 2 when the
 * first syllable only repeats the first sound, **0 when a card has no picture to show** (see
 * [hasPicture] — a dialogue turn keeps it, because there rung 0 is the conversation so far).
 * 0 picture · 1 first sound · 2 first syllable · 3 word spoken · 4 word spoken and written.
 */
class CueLadder(private val item: Item) {
    val levels: List<Int> = listOfNotNull(if (hasPicture(item)) 0 else null, 1, if (ownRung(item)) 2 else null, 3, 4)
    private var index = 0

    /** Set by [listened] and never cleared while the item lasts: he heard the word, and that is that. */
    private var heard = false

    /** Where the hint sequence stands. This is what is shown and said, and «Βοήθεια» walks it. */
    val level: Int get() = levels[index]

    /**
     * What the attempt row and the Leitner box are scored on: the rung he reached, or [LISTENED]
     * once he has asked to hear the word, whichever is higher.
     *
     * The two are not the same thing since «Άκου» became always available (spec §12, errorless
     * learning). Hearing the model is the same help level 3 gives — the word said to him — so the
     * data have to carry it; but it is not a rung, so nothing on the screen moves.
     */
    val recordedLevel: Int get() = if (heard) maxOf(level, LISTENED) else level

    val canHint: Boolean get() = index < levels.lastIndex
    val showsWord: Boolean get() = level == 4

    fun hint(): Int { if (canHint) index++; return level }

    /**
     * He pressed «Άκου». The recorded level rises to at least [LISTENED]; the hint sequence does
     * not move, so the next «Βοήθεια» carries on from exactly where it was and nothing of the word
     * appears on screen that was not there before.
     */
    fun listened(): Int { heard = true; return recordedLevel }

    fun reset() { index = 0; heard = false }

    /**
     * What to show and say at the current level; null at level 0.
     *
     * Punctuation is stripped at every level. A cue is a sound to start from, not a sentence: with
     * a dialogue turn behind it, level 2 of «Ναι, θα έρθω.» was the syllable *and its comma*, shown
     * at displayLarge and handed to the speech engine to read out. Whatever is left of a cue that
     * was nothing but punctuation is nothing, and says so.
     */
    fun cueText(): String? = when (level) {
        1 -> item.firstSound
        2 -> item.firstSyllable
        3, 4 -> item.text
        else -> null
    }?.let(::trimmed)

    /** Judged on [recordedLevel]: a word he had said to him is assisted work, however he got it said. */
    fun outcomeFor(confirmed: Boolean): Outcome = when {
        !confirmed -> Outcome.SKIPPED
        recordedLevel <= 2 -> Outcome.CORRECT
        else -> Outcome.ASSISTED
    }

    companion object {
        /**
         * The rung hearing the model is worth: level 3 is "the word spoken", which is exactly what
         * «Άκου» does. Every module scores a listen at this, ladder or no ladder.
         */
        const val LISTENED = 3

        /** Marks a Greek line can carry. None of them is a sound he can start a word from. */
        val PUNCTUATION = charArrayOf(',', '.', ';', '·', '!', '?', '«', '»', '"', '\'')

        /**
         * Whether the first syllable is a rung of its own.
         *
         * A syllable that says the same thing as the sound below it is not a smaller step to take,
         * it is the same step twice: «όχι» has «ο» for a first sound and «ό» for a first syllable,
         * and every vowel-initial word whose first syllable is that single vowel is the same. He
         * asked for more help and got the letter he was already looking at. The ladder leaves that
         * rung out exactly as it leaves out a missing syllable — the next «Βοήθεια» then says the
         * whole word, which is help.
         *
         * Compared the way the cue is actually shown: punctuation stripped, lower-cased, without
         * accents, since the accent is the only difference «ό» and «ο» have.
         */
        private fun ownRung(item: Item): Boolean {
            val syllable = key(item.firstSyllable) ?: return false
            return syllable != key(item.firstSound)
        }

        /**
         * Whether rung 0 has anything on it.
         *
         * On a **card** — the word coach, which is what levels 0–4 were written for — level 0 is the
         * picture and nothing else: no sound, no syllable, the word itself withheld. For a word with
         * no picture that is an empty card and the question "what is this?" asked about nothing at
         * all. Some words have none: ARASAAC draws things, and phase 13's «ελπίδα», «σκέψη»,
         * «κατάσταση» are not things; some others ship text-led on purpose, because the only drawing
         * ARASAAC has for them already belongs to another word, and one picture scored against two
         * targets is an error he cannot avoid. The rung is left out exactly as a missing syllable is,
         * so such a word opens on the first sound — the first thing about it there is to show — and
         * «Βοήθεια» walks the rest one honest step at a time. The card is still there, with the
         * placeholder [gr.dimitris.app.ui.components.PictureCard] draws, and «Άκου» is still on it:
         * what is dropped is the rung that would have been *only* that.
         *
         * A **dialogue turn** keeps its rung 0 whether or not it has a picture, because there it
         * never meant the picture. «Διάλογοι» shows no card at all: what is on the screen at level 0
         * is the conversation so far and the line the other person has just said, which is the whole
         * cue — that is the rung where he answers with nothing given away, and it is the one the
         * attempt row records as unaided work. Dropping it would start every turn of every dialogue
         * one rung of help in.
         */
        private fun hasPicture(item: Item): Boolean =
            item.kind == ItemKind.SCRIPT_LINE || !item.imagePath.isNullOrBlank()

        /** The cue as it is shown and said. Null when there was nothing left of it. */
        private fun trimmed(cue: String): String? =
            cue.filterNot { it in PUNCTUATION }.trim().takeIf { it.isNotEmpty() }

        /** What two cues are compared by: the shown cue, lower-cased and stripped of accents. */
        private fun key(cue: String?): String? =
            cue?.let(::trimmed)?.let { Greek.stripAccents(Greek.normalize(it)) }
    }
}
