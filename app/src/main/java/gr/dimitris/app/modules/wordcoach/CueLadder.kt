package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome

/**
 * The five cue levels for one item. Level 2 disappears when no first syllable is known.
 * 0 picture · 1 first sound · 2 first syllable · 3 word spoken · 4 word spoken and written.
 */
class CueLadder(private val item: Item) {
    val levels: List<Int> = listOfNotNull(0, 1, if (item.firstSyllable != null) 2 else null, 3, 4)
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
    }?.let(::spoken)

    /** The cue as it is shown and said: no marks, no stray space around it. */
    private fun spoken(cue: String): String? =
        cue.filterNot { it in PUNCTUATION }.trim().takeIf { it.isNotEmpty() }

    fun outcomeFor(confirmed: Boolean): Outcome = when {
        !confirmed -> Outcome.SKIPPED
        level <= 2 -> Outcome.CORRECT
        else -> Outcome.ASSISTED
    }

    companion object {
        /** Marks a Greek line can carry. None of them is a sound he can start a word from. */
        val PUNCTUATION = charArrayOf(',', '.', ';', '·', '!', '?', '«', '»', '"', '\'')
    }
}
