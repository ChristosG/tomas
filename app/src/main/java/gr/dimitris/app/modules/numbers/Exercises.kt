package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.core.greek.GreekTime

data class Price(val name: String, val cents: Int)

/**
 * The four operations, as a sign on the screen and a word in his ear.
 *
 * Both, always: the signs are a written language, and written language is what his stroke took. A man
 * who cannot read «×» can still hear «επί», and a man who has forgotten «επί» can still see the sign
 * — so the exercise never depends on one of them alone.
 */
enum class Op(val sign: String, val spoken: String, val key: String) {
    ADD("+", "συν", "add"),
    SUB("−", "πλην", "sub"),
    MUL("×", "επί", "mul"),
    DIV("÷", "διά", "div");

    /** [a] against [b]. Division by zero is not a question the generator can build, and answers 0. */
    fun apply(a: Int, b: Int): Int = when (this) {
        ADD -> a + b
        SUB -> a - b
        MUL -> a * b
        DIV -> if (b == 0) 0 else a / b
    }
}

/**
 * How many exercises a plan of [plannedItems] items buys.
 *
 * The module owes the session one Attempt per item it was handed, and the session shares one sitting
 * out between the modules ([gr.dimitris.app.today.SessionBudget]), so a short plan means a short run.
 * Never zero — a module worth entering is worth one question — and never more than the full ten free
 * practice asks for.
 */
fun exercisesFor(plannedItems: Int): Int = plannedItems.coerceIn(1, NumbersModule.EXERCISES_PER_SESSION)

/** One question. [options] are what he can tap; [answer] is the correct option value. */
sealed class NumberExercise {
    abstract val level: Int
    abstract val prompt: String
    abstract val options: List<Int>
    abstract val answer: Int
    abstract val type: String

    /**
     * What the phone says. Usually the written [prompt] word for word; a level whose stimulus is only
     * on the screen adds it, because text is a hint layer and never the only channel.
     */
    open val spokenPrompt: String get() = prompt

    /** Which is more? Two quantities, with dots at low levels. */
    data class Compare(override val level: Int, val a: Int, val b: Int, val showDots: Boolean) : NumberExercise() {
        override val type = "compare"
        override val prompt = "Ποιο είναι περισσότερο;"
        override val options = listOf(a, b)
        override val answer = maxOf(a, b)
    }

    /**
     * Where is the number on the line? [ticks] are every mark drawn, ascending; [candidates] are the
     * places he may tap — a subset of [ticks] that always contains [target], never two of them closer
     * than [ExerciseGenerator.MIN_TICK_GAP] ticks so two 72dp buttons cannot overlap.
     */
    data class NumberLine(
        override val level: Int,
        val target: Int,
        val ticks: List<Int>,
        val candidates: List<Int>,
    ) : NumberExercise() {
        override val type = "line"
        override val prompt = "Πού είναι το ${GreekNumbers.words(target)};"
        override val options = candidates
        override val answer = target
    }

    /** Tap every object, hear the count, then say how many. */
    data class Count(override val level: Int, val n: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "count"
        override val prompt = "Μέτρα τα. Πόσα είναι;"
        override val answer = n
    }

    /** Digit ↔ word. showWord: the word is shown and he taps the digit; else the digit is shown and he taps the word. */
    data class WordMatch(override val level: Int, val number: Int, override val options: List<Int>, val showWord: Boolean) : NumberExercise() {
        override val type = "word"
        override val prompt = if (showWord) "Ποιος αριθμός είναι;" else "Πώς λέγεται;"
        override val answer = number

        // Written Greek is the channel his aphasia damaged, so the word on the screen is said out
        // loud as well. It gives nothing away — the buttons are digits. The other way round it would:
        // there the buttons are the words.
        override val spokenPrompt: String get() = if (showWord) "$prompt ${GreekNumbers.words(number)}" else prompt
    }

    /** Which coin or note is this amount? */
    data class CoinPick(override val level: Int, val targetCents: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "coin"
        // In words, never "10,00 €": that exact numeral is printed on one of the buttons, and matching
        // two identical strings is not knowing money. The buttons keep the numeral; the question does
        // not. Greek TTS reads "10,00 €" as punctuation anyway, so the words are also what it says.
        // No article: "το πενήντα λεπτά" is not Greek, and the coins run from λεπτά to ευρώ.
        override val prompt = "Ποιο είναι ${Euro.spoken(targetCents)};"
        override val answer = targetCents
    }

    /** Which of his real things is more expensive? */
    data class PriceCompare(override val level: Int, val a: Price, val b: Price) : NumberExercise() {
        override val type = "price"
        override val prompt = "Ποιο είναι πιο ακριβό;"
        override val options = listOf(a.cents, b.cents)
        override val answer = maxOf(a.cents, b.cents)
    }

    /**
     * It costs X. Which note do you give? Exactly one of the [options] is enough to pay it — the
     * generator guarantees that — so handing over a note that covers the price is never marked wrong.
     */
    data class Pay(override val level: Int, val priceCents: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "pay"
        override val prompt = "Κοστίζει ${Euro.format(priceCents)}. Με τι πληρώνεις;"
        override val spokenPrompt: String get() = "Κοστίζει ${Euro.spoken(priceCents)}. Με τι πληρώνεις;"
        override val answer = options.filter { it >= priceCents }.min()
    }

    // ------------------------------------------------ levels 8–15: phase 12

    /**
     * Two numbers and an operation: `34 + 25`, `7 × 8`, `32 ÷ 4`.
     *
     * The sum is written on the screen *and* said out loud, whole, because he cannot write digits and
     * the only thing he is ever asked to do with one is recognise it among four. What makes level 8
     * different from level 9 and level 10 from level 11 is not this class — it is what the generator
     * allows into it, and the distractors it puts beside the answer.
     */
    data class Arithmetic(
        override val level: Int,
        val a: Int,
        val op: Op,
        val b: Int,
        override val options: List<Int>,
    ) : NumberExercise() {
        override val type = op.key
        override val answer = op.apply(a, b)

        /** What stands on the screen, with the answer's place left open. */
        val equation = "$a ${op.sign} $b"
        override val prompt = "Πόσο κάνει $equation;"
        override val spokenPrompt: String get() = "Πόσο κάνει ${GreekNumbers.words(a)} ${op.spoken} ${GreekNumbers.words(b)};"
    }

    /**
     * The blank is an operand, not the result: `? × 4 = 32`.
     *
     * [missing] is carried rather than derived, because "what would make this true" is the generator's
     * arithmetic to do once and not this class's to re-derive in four branches — and because a
     * subtraction or a division with the blank in front is a different question from one with the
     * blank behind, which is exactly the kind of thing a derived answer gets quietly wrong.
     */
    data class Missing(
        override val level: Int,
        val known: Int,
        val op: Op,
        val result: Int,
        val missing: Int,
        val missingFirst: Boolean,
        override val options: List<Int>,
    ) : NumberExercise() {
        override val type = "missing"
        override val answer = missing

        /** The line as he sees it, with a question mark where the number he owes it goes. */
        val equation = if (missingFirst) "$BLANK ${op.sign} $known = $result" else "$known ${op.sign} $BLANK = $result"
        override val prompt = "Ποιος αριθμός λείπει;"
        override val spokenPrompt: String get() {
            val k = GreekNumbers.words(known)
            val r = GreekNumbers.words(result)
            val said = if (missingFirst) "πόσα ${op.spoken} $k κάνουν $r" else "$k ${op.spoken} πόσα κάνουν $r"
            return "$prompt $said;"
        }
    }

    /**
     * You pay with a note, it costs less, how much comes back. The level the whole euro ladder was
     * climbing towards, because it is the one thing at a counter he cannot check afterwards.
     *
     * [answer] is never negative: a payment that does not cover the price is not a question with
     * change in it, and the generator never builds one.
     */
    data class Change(
        override val level: Int,
        val paidCents: Int,
        val priceCents: Int,
        override val options: List<Int>,
    ) : NumberExercise() {
        override val type = "change"
        override val answer = Euro.changeCents(paidCents, priceCents)
        override val prompt = "Πληρώνεις ${Euro.format(paidCents)}. Κοστίζει ${Euro.format(priceCents)}. Πόσα ρέστα;"
        override val spokenPrompt: String get() =
            "Πληρώνεις ${Euro.spoken(paidCents)}. Κοστίζει ${Euro.spoken(priceCents)}. Πόσα ρέστα;"

        /** The coins and notes that make the answer, for the moment after he has found it. */
        val pieces: List<Int> get() = Euro.change(paidCents, priceCents)
    }

    /**
     * A drawn face, and four times to choose from. [minutes] is the time on a twelve-hour face
     * ([GreekTime.MINUTES]), which is also the option value.
     *
     * The prompt says nothing but «Τι ώρα είναι;» — the face is the question, and a spoken prompt that
     * named the time would be reading out the answer. It is the one exercise in the module where the
     * sound cannot carry the stimulus, so the options carry both channels instead: the digits and the
     * words are on every button.
     */
    data class Clock(override val level: Int, val minutes: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "clock"
        override val answer = GreekTime.normalise(minutes)
        override val prompt = "Τι ώρα είναι;"
    }

    /** «Σήμερα είναι Δευτέρα. Τι μέρα είναι μετά από 3 μέρες;» Options are days, by their index. */
    data class DayAfter(
        override val level: Int,
        val from: Int,
        val plus: Int,
        override val options: List<Int>,
    ) : NumberExercise() {
        override val type = "day"
        override val answer = GreekTime.dayAfter(from, plus)
        override val prompt = "Σήμερα είναι ${GreekTime.day(from)}. Τι μέρα είναι μετά από $plus μέρες;"

        // «μέρες» is feminine, so it is «μετά από τρεις μέρες» and never «μετά από τρία μέρες».
        override val spokenPrompt: String get() =
            "Σήμερα είναι ${GreekTime.day(from)}. Τι μέρα είναι μετά από ${GreekNumbers.feminine(plus)} μέρες;"
    }

    /**
     * Two steps in one situation: «Έχεις 3 κουτιά με 6 αυγά. Σπάνε 4. Πόσα μένουν;»
     *
     * Both texts are carried, because they are not the same sentence: [prompt] has digits in it, which
     * are what a number looks like, and [spoken] has the words, which are what a number sounds like —
     * and Greek TTS given "3" in the middle of a sentence is a coin toss. The arithmetic is the
     * generator's; what this holds is the story it built, so a row in the database a year from now
     * still says which question he was actually asked.
     */
    data class WordProblem(
        override val level: Int,
        override val prompt: String,
        val spoken: String,
        override val options: List<Int>,
        override val answer: Int,
    ) : NumberExercise() {
        override val type = "problem"
        override val spokenPrompt: String get() = spoken
    }

    companion object {
        /** Where the number he owes an equation goes. Not an underscore: he is not filling in a form. */
        const val BLANK = "?"
    }
}
