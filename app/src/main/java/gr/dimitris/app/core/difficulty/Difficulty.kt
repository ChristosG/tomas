package gr.dimitris.app.core.difficulty

import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.greek.Syllabifier
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates

/**
 * The one number he sets himself: how hard each module is, 1 to 5 (spec §13).
 *
 * Dimitris told us the app is too easy. Everything in it that decides how hard an exercise is was
 * until now decided *for* him — by the Leitner boxes, by [NumberProgression], by
 * [gr.dimitris.app.core.scheduler.LevelProgression] — and all three of those only ever look at the
 * sitting he has just done. None of them can hear "this is too easy for me"; he has no sentence to
 * say it with, and his caregivers are not in the room when he practises. Five dots on the first
 * screen of each module are that sentence.
 *
 * What the dots do *not* do is replace the automatic progressions. They pick a **band** of the
 * module's own levels, and the progression keeps moving inside it exactly as it did before: the dots
 * say how hard the work should be, the sitting still says whether he is ready for the next step of
 * it. When the dots move, the module's level jumps to the bottom of the new band — a man who has
 * just asked for harder work should meet the easiest of the harder work first, not the hardest.
 *
 * Pure: no Android, no DataStore, no clock, so every mapping below can be argued with in
 * `DifficultyTest` rather than on a phone. Where it is stored, and how the caregiver's bounds hold
 * it, is [gr.dimitris.app.core.settings.Settings.difficulty].
 */
object Difficulty {
    const val MIN = 1
    const val MAX = 5

    /**
     * Where everyone starts. Two and not three, because the bands below are built so that 2 is the
     * app as it was before this phase — the word coach's full vocabulary, the arcade's 96 dp target
     * ([Adaptive.START]) — so an existing phone behaves on upgrade exactly as it did the day before,
     * and the only thing that has changed is that he can now move it.
     */
    const val DEFAULT = 2

    /** A value from anywhere — a backup, a sync, a stepper — held to 1..5. */
    fun clamp(n: Int): Int = if (n < MIN) MIN else if (n > MAX) MAX else n

    /**
     * The same, inside the caregiver's bounds. [floor] and [ceiling] are themselves held to 1..5 and
     * to each other first, so a pair that arrived the wrong way round — an old backup, a half-written
     * edit — can never produce an empty range he is locked out of entirely.
     */
    fun clamp(n: Int, floor: Int, ceiling: Int): Int {
        val lo = clamp(floor)
        val hi = clamp(ceiling).coerceAtLeast(lo)
        return clamp(n).coerceIn(lo, hi)
    }

    // ---------------------------------------------------------------- «Αριθμοί»

    /**
     * The number-sense levels each dot stands for, as the ladder will be when Task 5 has built it:
     * 1 → 1–2, 2 → 3–4, 3 → 5–7, 4 → 8–11, 5 → 12–15. The bands widen on the way up because the
     * steps get smaller: levels 12 to 15 are four variations on paying for something, where 1 and 2
     * are two whole different ideas.
     *
     * **Levels above 7 do not exist yet.** Task 5 adds 8–15; until it lands, bands 4 and 5 both
     * clamp to level 7, which is the hardest thing the module can currently generate. So the two
     * hardest dots do the same as the third-hardest today, and the KDoc rather than the UI says so:
     * a row of dots that told him "this one is not built yet" would be a row of dots he has to read.
     */
    fun numbers(d: Int): IntRange = band(NUMBERS_BANDS, d, NumberProgression.MAX_LEVEL)

    /**
     * The whole ladder as it will be, kept here rather than inlined so Task 5 has one place to widen
     * and `DifficultyTest` has one place to read.
     */
    internal val NUMBERS_BANDS = listOf(1..2, 3..4, 5..7, 8..11, 12..15)

    // -------------------------------------------------------------- «Προτάσεις»

    /**
     * The sentence-builder levels each dot stands for, as the ladder will be once Task 7 has added
     * 5–8: 1 → 1–2, 2 → 3–4, 3 → 5–6, 4 → 7, 5 → 8. The shape follows «Αριθμοί» — two levels a band
     * at the easy end, where the steps are whole new sentence shapes — and then one each at the top,
     * where a level is a clause more to hold in mind and is a band of its own.
     *
     * **Levels above 4 do not exist yet** ([SentenceTemplates.MAX_LEVEL]); until Task 7 lands, bands
     * 3, 4 and 5 all clamp to level 4, the longest sentence the templates can build.
     */
    fun sentences(d: Int): IntRange = band(SENTENCES_BANDS, d, SentenceTemplates.MAX_LEVEL)

    internal val SENTENCES_BANDS = listOf(1..2, 3..4, 5..6, 7..7, 8..8)

    // ------------------------------------------------------------------ «Γράψε»

    /**
     * What he writes, one dot per level, because «Γράψε» already had exactly five and they are
     * already a difficulty: 1 capitals, 2 small letters, 3 his own name, 4 words, 5 words from
     * memory.
     *
     * A band of one level means the automatic progression has nowhere to move inside it: from here
     * on, the writing level is his to set and the sitting no longer steps it up or down behind him.
     * That is the intended trade — the five levels are five *different exercises*, not five sizes of
     * the same one, and being moved off "my own name" by a good morning was never something he
     * asked for.
     */
    fun trace(d: Int): IntRange = clamp(d).let { it..it }

    // ------------------------------------------------------------------ «Λέξεις»

    /**
     * Which tier of the vocabulary the word coach draws from: 1 single words only, 2 words and
     * phrases.
     *
     * **There are only two tiers today.** The seed ships words and phrases and nothing graded beyond
     * them, so 3, 4 and 5 all map to tier 2 — the hardest that exists — until phase 13 brings a
     * graded vocabulary. Tier 2 is also [DEFAULT], which is why an upgraded phone sees the same
     * words it saw yesterday.
     */
    fun wordCoachTier(d: Int): Int = if (clamp(d) <= 1) 1 else 2

    /** The kinds tier [wordCoachTier] admits. A phrase is the harder thing to retrieve and to say. */
    fun wordCoachKinds(d: Int): List<ItemKind> =
        if (wordCoachTier(d) == 1) listOf(ItemKind.WORD) else listOf(ItemKind.WORD, ItemKind.PHRASE)

    // ------------------------------------------------- «Τραγούδα και πες το»

    /**
     * How long a phrase he sings, in syllables: 1 → 1–2, 2 → 3–4, 3 → 5–6, 4 → 7–8, 5 → nine and up.
     *
     * Syllables and not words, because a syllable is what melodic intonation therapy is made of —
     * one tapped beat each — so two syllables more is two more beats to hold, whether they arrive as
     * one word or two. The seed's thirty-two phrases run from one syllable to seven, so the top dot
     * has nothing of its own until a caregiver writes something longer; [gr.dimitris.app.modules.singsay.SingSayModule]
     * falls back to the unfiltered plan rather than leaving him a module with nothing in it.
     */
    fun syllables(d: Int): IntRange = when (clamp(d)) {
        1 -> 1..2
        2 -> 3..4
        3 -> 5..6
        4 -> 7..8
        else -> 9..Int.MAX_VALUE
    }

    /**
     * How many syllables a phrase is worth: every word of it, added up.
     *
     * [Syllabifier] returns null for a blank word and for anything it cannot split — a number, a
     * Latin word — and such a word counts as one syllable rather than none, so a phrase is never
     * measured as shorter than the words it has.
     */
    fun syllablesOf(text: String): Int = text.trim().split(WHITESPACE)
        .filter { it.isNotBlank() }
        .sumOf { Syllabifier.syllables(it)?.size ?: 1 }

    private val WHITESPACE = Regex("\\s+")

    // --------------------------------------------------------------- «Διάλογοι»

    /**
     * How many turns of his own a dialogue gives him: 1 → 1–2, 2 → 3–4, 3 → 5–6, 4 → 7–9, 5 → ten
     * and up.
     *
     * A stand-in, and deliberately a crude one. Task 6 gives `scripts` a `tier` column and the
     * dialogues a real grading — how much of the turn is handed to him, how far from the script an
     * answer may be — and this mapping is then replaced by it. Until then the only thing a dialogue
     * carries that is honestly about effort is how many times he has to speak: the six dialogues the
     * app ships all give him four turns, so they all sit in band 2 and the dots change nothing until
     * a caregiver writes a longer or a shorter conversation. [gr.dimitris.app.modules.scripts.ScriptsModule]
     * falls back to every practisable dialogue when the band is empty, because a conversation is the
     * module: there is nothing else for it to offer.
     */
    fun turns(d: Int): IntRange = when (clamp(d)) {
        1 -> 1..2
        2 -> 3..4
        3 -> 5..6
        4 -> 7..9
        else -> 10..Int.MAX_VALUE
    }

    // -------------------------------------------------------------- «Δεξί χέρι»

    /**
     * How big the arcade's targets may be, in dp: an even fifth of [Adaptive.MIN]..[Adaptive.MAX]
     * each, hardest last, because a smaller target is a harder one. 1 → 112–130, 2 → 94–112,
     * 3 → 76–94, 4 → 58–76, 5 → 40–58.
     *
     * The arcade is the one module whose difficulty was already a number that moves by itself — eight
     * per cent off a hit, fifteen back on a miss — so here the band is what that number is held
     * inside between sittings, which is exactly what a band is everywhere else. [Adaptive.START] is
     * 96 dp and sits in band 2, so nothing changes for a hand that has been playing until he asks it
     * to.
     *
     * Held on the way *in* and on the way *out* of one round, not inside it: the shrink and the grow
     * live in the four games, and a target that dips below the band for one tap and comes back is
     * not worth four call sites.
     */
    fun arcade(d: Int): ClosedFloatingPointRange<Float> {
        val step = (Adaptive.MAX - Adaptive.MIN) / MAX
        val hardest = Adaptive.MAX - step * clamp(d)
        return hardest..(hardest + step)
    }

    /** The easiest size in the band, which is the biggest: where a hand starts when the dots move. */
    fun arcadeStart(d: Int): Float = arcade(d).endInclusive

    /** A stored size held inside the band the dots ask for, and inside what the games can draw. */
    fun arcadeClamp(size: Float, d: Int): Float = Adaptive.clamp(size).coerceIn(arcade(d))

    // ----------------------------------------------------------------- internals

    /**
     * One band out of [bands], with everything above [max] folded onto [max] — which is how a band
     * over levels that have not been built yet still names a level that exists. A band entirely
     * above [max] collapses to `max..max`, so the two hardest dots of «Αριθμοί» both mean "level 7"
     * until Task 5 widens the ladder.
     */
    private fun band(bands: List<IntRange>, d: Int, max: Int): IntRange {
        val b = bands[clamp(d) - 1]
        return b.first.coerceAtMost(max)..b.last.coerceAtMost(max)
    }
}
