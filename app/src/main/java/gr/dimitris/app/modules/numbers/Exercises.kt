package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers

data class Price(val name: String, val cents: Int)

/** One question. [options] are what he can tap; [answer] is the correct option value. */
sealed class NumberExercise {
    abstract val level: Int
    abstract val prompt: String
    abstract val options: List<Int>
    abstract val answer: Int
    abstract val type: String

    /** Which is more? Two quantities, with dots at low levels. */
    data class Compare(override val level: Int, val a: Int, val b: Int, val showDots: Boolean) : NumberExercise() {
        override val type = "compare"
        override val prompt = "Ποιο είναι περισσότερο;"
        override val options = listOf(a, b)
        override val answer = maxOf(a, b)
    }

    /** Where does the number go on the line? */
    data class NumberLine(override val level: Int, val target: Int, val ticks: List<Int>) : NumberExercise() {
        override val type = "line"
        override val prompt = "Πού πάει το ${GreekNumbers.words(target)};"
        override val options = ticks
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
    }

    /** Which coin or note is this amount? */
    data class CoinPick(override val level: Int, val targetCents: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "coin"
        override val prompt = "Ποιο είναι το ${Euro.format(targetCents)};"
        override val answer = targetCents
    }

    /** Which of his real things is more expensive? */
    data class PriceCompare(override val level: Int, val a: Price, val b: Price) : NumberExercise() {
        override val type = "price"
        override val prompt = "Ποιο είναι πιο ακριβό;"
        override val options = listOf(a.cents, b.cents)
        override val answer = maxOf(a.cents, b.cents)
    }

    /** It costs X. Which note do you give? (smallest one that is enough) */
    data class Pay(override val level: Int, val priceCents: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "pay"
        override val prompt = "Κοστίζει ${Euro.format(priceCents)}. Με τι πληρώνεις;"
        override val answer = options.filter { it >= priceCents }.min()
    }
}
