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

    /** What to show and say at the current level; null at level 0. */
    fun cueText(): String? = when (level) {
        1 -> item.firstSound
        2 -> item.firstSyllable
        3, 4 -> item.text
        else -> null
    }

    fun outcomeFor(confirmed: Boolean): Outcome = when {
        !confirmed -> Outcome.SKIPPED
        level <= 2 -> Outcome.CORRECT
        else -> Outcome.ASSISTED
    }
}
