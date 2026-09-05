package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.greek.Greek

/**
 * The five cue levels for one item. Level 2 disappears when the first syllable is no help of its own.
 * 0 picture · 1 first sound · 2 first syllable · 3 word spoken · 4 word spoken and written.
 */
class CueLadder(private val item: Item) {
    val levels: List<Int> = listOfNotNull(0, 1, if (ownRung(item)) 2 else null, 3, 4)
    private var index = 0

    val level: Int get() = levels[index]
    val canHint: Boolean get() = index < levels.lastIndex
    val showsWord: Boolean get() = level == 4

    fun hint(): Int { if (canHint) index++; return level }
    fun reset() { index = 0 }

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

    fun outcomeFor(confirmed: Boolean): Outcome = when {
        !confirmed -> Outcome.SKIPPED
        level <= 2 -> Outcome.CORRECT
        else -> Outcome.ASSISTED
    }

    companion object {
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

        /** The cue as it is shown and said. Null when there was nothing left of it. */
        private fun trimmed(cue: String): String? =
            cue.filterNot { it in PUNCTUATION }.trim().takeIf { it.isNotEmpty() }

        /** What two cues are compared by: the shown cue, lower-cased and stripped of accents. */
        private fun key(cue: String?): String? =
            cue?.let(::trimmed)?.let { Greek.stripAccents(Greek.normalize(it)) }
    }
}
