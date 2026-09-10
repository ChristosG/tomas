package gr.dimitris.app.core.difficulty

import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.greek.Syllabifier
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.sql.SqlPuzzles

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
     * All fifteen exist since Task 5 ([NumberProgression.MAX_LEVEL]), so every dot now means something
     * different and nothing below clamps.
     */
    fun numbers(d: Int): IntRange = band(NUMBERS_BANDS, d, NumberProgression.MAX_LEVEL)

    /**
     * The whole ladder as it will be, kept here rather than inlined so Task 5 has one place to widen
     * and `DifficultyTest` has one place to read.
     */
    internal val NUMBERS_BANDS = listOf(1..2, 3..4, 5..7, 8..11, 12..15)

    // -------------------------------------------------------------- «Προτάσεις»

    /**
     * The sentence-builder levels each dot stands for: 1 → 1–2, 2 → 3–4, 3 → 5–6, 4 → 7, 5 → 8. The
     * shape follows «Αριθμοί» — two levels a band at the easy end, where the steps are whole new
     * sentence shapes — and then one each at the top, where a level is a clause more to hold in mind
     * and is a band of its own.
     *
     * All eight exist since Task 7 ([SentenceTemplates.MAX_LEVEL]): 5 the articles, 6 the
     * prepositions, 7 a clause, 8 a question. Every dot now means something different and nothing
     * below clamps — which is what Dimitris asked for when he told us the two-card sentences were
     * trivial.
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
     * The hardest tier of the vocabulary this dot asks for, as a
     * [gr.dimitris.app.core.data.Item.tier]: one dot, one tier, and dot n admits every word of tier
     * n **and below**.
     *
     * Five tiers exist since phase 13 — 1 the everyday words, 2 the phrases, 3 the long everyday
     * words a chemist and a bank need, 4 the verbs and adjectives of an opinion, 5 the abstract
     * nouns — so every dot now means something different. Before it there were two, and dots 3, 4
     * and 5 all landed on the same two hundred words, which is the half of "the app is too easy"
     * that «Λέξεις» was guilty of.
     *
     * Cumulative, like [syllableCeiling] and [scriptTier] and for the same reason: «νερό» is still
     * worth saying on the day he asks for «ελευθερία», and the sandwich in
     * [gr.dimitris.app.core.scheduler.SessionBuilder] wants an easy word at each end of the sitting.
     * A word nobody has graded is tier 1 — every word already on a phone, and every word a caregiver
     * types without touching the chips — so it is in reach from every dot, always.
     */
    fun wordCoachTier(d: Int): Int = clamp(d)

    /** Whether a word of [tier] is one this dot asks for. Anything unreadable counts as the easiest. */
    fun admitsTier(tier: Int, d: Int): Boolean = clamp(tier) <= wordCoachTier(d)

    /**
     * The kinds tier [wordCoachTier] admits. A phrase is the harder thing to retrieve and to say, so
     * dot 1 is single words and everything above it is words and phrases — which is what the module
     * did before tiers, and still does: it is the same cut, drawn in SQL where the tier ceiling is
     * drawn in Kotlin.
     */
    fun wordCoachKinds(d: Int): List<ItemKind> =
        if (wordCoachTier(d) <= 1) listOf(ItemKind.WORD) else listOf(ItemKind.WORD, ItemKind.PHRASE)

    // -------------------------------------------------------------------- «SQL»

    /**
     * The puzzle levels «SQL» may reach at this dot: **1 up to n**, and dot n admits every level at
     * or below it — cumulative, like [wordCoachTier] and [scriptTier] rather than banded like
     * [numbers] and [sentences].
     *
     * The five levels are five different *kinds* of question and not five sizes of one: 1 put the
     * words of a query in order, 2 choose the query that gives a result, 3 fill in the missing
     * keyword, 4 write the query, 5 two tables at once. A ceiling is the honest shape for that.
     * Putting the words in order is still worth doing on the day he writes a `JOIN` — it is how
     * every sitting starts, and the progression inside the band is what decides when he meets the
     * harder kind — whereas a *band* of 4..4 would have retired the first three kinds the moment he
     * asked for hard work, which is exactly the mistake [syllableCeiling] documents.
     *
     * So the dot is a real cap: at dot 1 the module is ordering tiles and nothing else, and at dot 5
     * the whole ladder is in play. [SqlPuzzles.MAX_LEVEL] is 5, so every dot means something
     * different and nothing below clamps.
     */
    fun sql(d: Int): IntRange = SqlPuzzles.MIN_LEVEL..clamp(d).coerceAtMost(SqlPuzzles.MAX_LEVEL)

    /**
     * The dot a «SQL» level belongs to: the level *is* the dot, because the dot is that level's
     * ceiling. It is what [gr.dimitris.app.core.settings.Settings.setSqlLevel] moves the dot to when a
     * sitting promotes him — a man who has just earned level 4 is a man whose dots say 4.
     */
    fun sqlDot(level: Int): Int = clamp(level)

    // ------------------------------------------------- «Τραγούδα και πες το»

    /**
     * How long a phrase he sings, in syllables: 1 → up to 2, 2 → up to 4, 3 → up to 6, 4 → up to 8,
     * 5 → any length. A **ceiling**, not a window — see [syllableCeiling].
     *
     * Syllables and not words, because a syllable is what melodic intonation therapy is made of —
     * one tapped beat each — so two syllables more is two more beats to hold, whether they arrive as
     * one word or two.
     */
    fun syllables(d: Int): IntRange = when (clamp(d)) {
        1 -> 1..2
        2 -> 3..4
        3 -> 5..6
        4 -> 7..8
        else -> 9..Int.MAX_VALUE
    }

    /**
     * The longest phrase this dot admits. Everything **shorter stays in the pool**, which is what
     * makes the dots a difficulty rather than a filter.
     *
     * The first cut of this was a window — only phrases *inside* the band — and it quietly deleted
     * half his vocabulary on upgrade: at the default dot 2 «Ναι», «Όχι», «Ξανά» and «Τέλος» were
     * suddenly not offered, their Leitner rows went overdue and stayed overdue for ever, and no dot
     * setting brought them back. A difficulty is a ceiling on what he is *asked* for. «Λέξεις»
     * already grades this way ([wordCoachKinds] is cumulative) and so does «Δεξί χέρι»
     * ([arcadeClamp]); the short phrases keep the sitting's easy ends, which is what the sandwich in
     * [gr.dimitris.app.core.scheduler.SessionBuilder] is for.
     */
    fun syllableCeiling(d: Int): Int = syllables(d).last

    /** The dot a phrase of [syllables] syllables belongs to: the easiest one that still admits it. */
    fun syllableDot(syllables: Int): Int =
        (MIN..MAX).firstOrNull { syllables <= syllableCeiling(it) } ?: MAX

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
     * How many turns of his own a dialogue may ask for: 1 → up to 2, 2 → up to 4, 3 → up to 6,
     * 4 → up to 9, 5 → any. A **ceiling**, not a window — see [turnCeiling].
     *
     * This was the stand-in the dialogue module planned on until a dialogue could say how hard it
     * was. It no longer plans on it: [scriptTier] does, against the tier every line now carries.
     * What is left here is the one thing this mapping is still good for — reading a *number of
     * turns* back as a dot, for dialogues written before tiers existed, which is
     * [DifficultyInit.scriptsDot]'s first-run guess and its only caller.
     */
    fun turns(d: Int): IntRange = when (clamp(d)) {
        1 -> 1..2
        2 -> 3..4
        3 -> 5..6
        4 -> 7..9
        else -> 10..Int.MAX_VALUE
    }

    /**
     * The longest dialogue a dot would admit, in turns of his own — the shape [turns] is read
     * through, and cumulative like [syllableCeiling]. Nothing plans on it any more; it exists so
     * that [turnDot] can name the dot a length belongs to.
     */
    fun turnCeiling(d: Int): Int = turns(d).last

    /** The dot a dialogue of [turns] turns belongs to: the easiest one that still admits it. */
    fun turnDot(turns: Int): Int = (MIN..MAX).firstOrNull { turns <= turnCeiling(it) } ?: MAX

    /**
     * The hardest dialogue this dot admits, as a tier of
     * [gr.dimitris.app.core.data.ScriptLine.tier]: one dot, one tier, and dot n takes every dialogue
     * of tier n **and below**. Cumulative like [wordCoachKinds] and [syllableCeiling], for the same
     * reason — the errand at the bakery is still worth having on the day he asked for hard work.
     *
     * This is what [gr.dimitris.app.modules.scripts.ScriptsModule.practisable] now plans on, in place
     * of [turnCeiling]: a tier is what the dialogue itself says about its difficulty, where the number
     * of turns was only ever a stand-in for it. [turns] stays for
     * [DifficultyInit.scriptsDot], which has to guess where a phone already was from dialogues written
     * before any of them carried a tier.
     */
    fun scriptTier(d: Int): Int = clamp(d)

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

    /**
     * A stored size held to what this dot asks for: **never bigger** than the band's easiest target,
     * and never outside what the games can draw.
     *
     * A ceiling and not a window, for the reason [syllableCeiling] gives and for one of the arcade's
     * own: the stored size is the module's clinical measure. A hand that has worked its way down to
     * 50 dp over months of physiotherapy must never be handed a 94 dp circle because the dots
     * happened to say 2 — that is weeks of work undone by an upgrade, with no tap from him. Smaller
     * than the band is him doing better than he asked for, and it stands.
     */
    fun arcadeClamp(size: Float, d: Int): Float = Adaptive.clamp(size).coerceAtMost(arcadeStart(d))

    /**
     * The dot a stored target size belongs to: the **hardest** one that still admits it, which is the
     * band the size falls in. The dots run easiest-first while the sizes run biggest-first, so this
     * is the last dot whose ceiling is still at or above the size, not the first.
     *
     * Used once, to work out where a hand that has been playing since before the dots existed
     * already is. [DifficultyInit] hands it the **biggest** of the four stored sizes, because with
     * [arcadeClamp] a ceiling that is the one derivation under which no game's target moves at all on
     * the upgrade.
     */
    fun arcadeDot(sizeDp: Float): Int {
        val size = Adaptive.clamp(sizeDp)
        return (MIN..MAX).lastOrNull { size <= arcadeStart(it) } ?: MIN
    }

    // ------------------------------------------- the level, at both ends of a sitting

    /**
     * The level a sitting really runs at: the stored one, brought **into** the band the dots ask for.
     *
     * This happens once, when the module opens, and it is an honest jump — the level it returns is
     * the level the exercises are built from and the level the attempt rows record, so a physio
     * reading the database sees what he actually did rather than what the store happened to say.
     *
     * It exists because the alternative was worse in both directions. Leaving a stored level below
     * the band alone meant the *progression* pulled it up at the end of the sitting instead, and
     * that made a **failed** sitting a promotion: he taps dot 5 in «Αριθμοί», the store says level 7,
     * he gets four of ten right, `next` returns 6, and a clamp into 12..15 opened tomorrow on
     * two-step word problems. Now the jump happens before the work, where it is his own tap that
     * caused it, and [levelAfterSitting] can never raise him for a bad morning.
     */
    fun levelAtLoad(stored: Int, band: IntRange): Int = stored.coerceIn(band)

    /**
     * What a finished sitting is allowed to leave behind: the progression's answer, held inside the
     * band — and, when the sitting did **not** earn a step up, never above where it started.
     *
     * [from] is the level he actually practised ([levelAtLoad]), [next] what
     * [gr.dimitris.app.core.scheduler.LevelProgression] or
     * [gr.dimitris.app.modules.numbers.NumberProgression] made of it. A sitting he did not earn a
     * step in can never push him up into a band he has not entered — that jump belongs to
     * [levelAtLoad], where his own tap caused it.
     *
     * The exactly-honest statement of the guard, since the summary above is one word too broad: a
     * [from] below the band that *earns* a step up is admitted to the band's floor. No caller can
     * reach it — all three ViewModels pass [levelAtLoad]'s output, and «Προτάσεις» passes a `played`
     * that `LevelProgression` has already held to the band — and admitting a level he has just earned
     * is the right answer if one ever does.
     *
     * A band of one level is the common case rather than the corner: «Γράψε» is `d..d` at every dot,
     * and «Προτάσεις» is `7..7` and `8..8` at its top two. Both directions then return [from], so
     * the sitting writes nothing.
     */
    fun levelAfterSitting(from: Int, next: Int, band: IntRange): Int {
        val held = next.coerceIn(band)
        return if (next > from) held else held.coerceAtMost(from)
    }

    /** The dot whose band a number-sense level belongs to. The bands tile 1..15, so there is one. */
    fun numbersDot(level: Int): Int = dotOf(NUMBERS_BANDS, level)

    /** The dot whose band a sentence level belongs to. Read off the full ladder, not the clamped one. */
    fun sentencesDot(level: Int): Int = dotOf(SENTENCES_BANDS, level)

    /** «Γράψε» has one level per dot, so the level *is* the dot. */
    fun traceDot(level: Int): Int = clamp(level)

    // ----------------------------------------------------------------- internals

    /**
     * Which dot owns [level], read off the **unclamped** ladder so that the answer did not change as
     * Tasks 5 and 7 built the levels the top bands were waiting for. A level past the end of the
     * ladder belongs to the hardest dot.
     */
    private fun dotOf(bands: List<IntRange>, level: Int): Int =
        bands.indexOfFirst { level <= it.last }.let { if (it < 0) MAX else it + 1 }

    /**
     * One band out of [bands], with everything above [max] folded onto [max] — which is how a band
     * over levels that have not been built yet still names a level that exists. A band entirely
     * above [max] collapses to `max..max`, which is what the top three dots of «Προτάσεις» did until
     * Task 7 widened that ladder the way Task 5 widened «Αριθμοί». Both ladders are whole now, so
     * nothing folds today; the guard stays because the next ladder to be widened will need it.
     */
    private fun band(bands: List<IntRange>, d: Int, max: Int): IntRange {
        val b = bands[clamp(d) - 1]
        return b.first.coerceAtMost(max)..b.last.coerceAtMost(max)
    }
}
