package gr.dimitris.app.modules.trace

/**
 * One letter of a dictated word, as it came out of his hand.
 *
 * The three numbers are the ones [LetterScore] already carries, kept per letter because that is how
 * the word was marked and how it will be read: a word he finished with one letter shown to him is a
 * letter to practise, not a word. [missed] is that showing — true when the letter had to be put on
 * the paper before he could write it.
 */
data class DictatedLetter(
    val c: String,
    val coverage: Float,
    val precision: Float,
    val ink: Float,
    val missed: Boolean,
    /** Whether he wrote the letter's accent, on a letter that has one. See [LetterScore.accent]. */
    val accent: Boolean? = null,
)

/**
 * One word written from hearing, letter by letter, on one piece of paper.
 *
 * Level 4 of «Γράψε» — «Υπαγόρευση» — says the word out loud and never writes it down. He hears it,
 * and then writes it one letter at a time: the letters he has got joined in a row above the paper,
 * and the paper itself empty for the letter he is on. Each letter is marked on its own by the
 * phase-11 scorer against the letter that belongs at that slot, at the same strictness a caregiver
 * set, so «ψομί» cannot pass as «ψωμί» and a letter that was nearly right is still nearly right.
 *
 * A miss is never a wall and never a wrong answer. The letter is put on the paper as a template, he
 * traces over it, and the word goes on — the only thing it costs is the word's mark, which is what
 * [helped] decides: a word written with no letter shown is his own (CORRECT), and a word with one is
 * assisted work (ASSISTED). Exactly the trade every other exercise in this app makes.
 *
 * Pure: no Android, no scorer, no clock, so the whole of the letter-by-letter rule can be argued
 * with in `DictationTest` rather than on a phone. What marks a letter is [TraceScorer]; what this
 * knows is which letter comes next and what the word cost him.
 */
data class Dictation(
    /** The word he heard. Never shown to him: that is the whole exercise. */
    val word: String,
    /** The letters he has written and had accepted, in the order they are written. */
    val written: List<DictatedLetter> = emptyList(),
) {
    /** Which slot he is on, counted from zero: one per letter he has already got. */
    val at: Int get() = written.size

    /** The letter the slot he is on wants, or null once the word is finished. */
    val expected: String? get() = word.getOrNull(at)?.toString()

    /** True once every letter of the word has been written. */
    val done: Boolean get() = at >= word.length

    /** The letters already accepted, as the row above the paper shows them. */
    val accepted: String get() = written.joinToString("") { it.c }

    /**
     * True when any letter of this word had to be shown to him. It is what turns the finished word
     * from his own work into assisted work, and nothing else about the word does.
     */
    val helped: Boolean get() = written.any { it.missed }

    /** How much of the word he went over, letter by letter: the mean of the letters he wrote. */
    val coverage: Float? get() = mean { it.coverage }

    /** How much of what he drew was on the letters. See [TraceScore.precision]. */
    val precision: Float? get() = mean { it.precision }

    /** How much line he drew against how long the letters are, averaged the same way. */
    val ink: Float? get() = mean { it.ink }

    /**
     * One marked try at the slot he is on — the whole rule of this level, in one place.
     *
     * The letter, accepted, with the next slot opened empty behind it; or **null** when what he drew
     * was not the letter, which is not a wrong answer and not the end of the word: the caller puts the
     * letter on the paper, he traces over it, and the same slot is marked again. [shown] is whether the
     * letter was already on the paper for this try, and it is what the accepted letter will remember:
     * a letter he was shown is what makes the finished word assisted work.
     *
     * Here rather than in the ViewModel so that "a miss shows the letter and the word goes on" is a
     * sentence a unit test can drive with a scorer of its own. What the ViewModel adds to it is the
     * buzz, the paper and the row.
     */
    fun judged(score: TraceScore, shown: Boolean): Dictation? {
        if (!score.passed) return null
        // One letter on the paper, so one mark. A glyph always produces one, and a pass with none is
        // impossible ([TraceScorer.score] returns nothing-at-all instead); the word's own numbers are
        // the honest answer if one ever arrives.
        val mark = score.letters.firstOrNull()
            ?: LetterScore(expected.orEmpty(), score.coverage, score.precision, passed = true, ink = score.inkRatio)
        return accept(mark, missed = shown)
    }

    /**
     * The letter at this slot, accepted, and the next slot opened empty behind it. [missed] is
     * whether the letter had been put on the paper for him first.
     */
    fun accept(mark: LetterScore, missed: Boolean): Dictation = copy(
        written = written + DictatedLetter(
            // The letter the word wanted, not whatever the mark happens to be named after: the two
            // are the same letter today, and a row that says «ψ» for a slot the word spells «ω»
            // would be unreadable a year from now.
            c = expected ?: mark.text,
            coverage = mark.coverage,
            precision = mark.precision,
            ink = mark.ink,
            missed = missed,
            accent = mark.accent,
        ),
    )

    /**
     * The per-letter rows the attempt's detail carries: `{c, coverage, precision, ink, missed}` each,
     * and `accent` where the letter has one.
     *
     * Written as plain maps because that is what [gr.dimitris.app.core.data.Adapt.Detail.kept] puts
     * on the row, and shaped exactly like the per-letter marks phase 6 already wrote for a traced
     * word — with `missed` added, which is the one thing a dictated letter knows and a traced one
     * cannot.
     */
    val detail: List<Map<String, Any?>>
        get() = written.map {
            // A null `accent` is a letter with none, and Gson leaves a null out of the object: the
            // key is there exactly where there was a mark to hit.
            mapOf(
                "c" to it.c, "coverage" to it.coverage, "precision" to it.precision,
                "ink" to it.ink, "missed" to it.missed, "accent" to it.accent,
            )
        }

    private inline fun mean(of: (DictatedLetter) -> Float): Float? =
        if (written.isEmpty()) null else written.map(of).average().toFloat()
}
