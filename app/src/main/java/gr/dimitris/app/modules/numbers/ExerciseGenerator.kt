package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.Euro
import kotlin.random.Random

class ExerciseGenerator(private val random: Random = Random.Default) {

    /**
     * [count] questions at [level]. No question is ever asked twice in a row: level 2 draws from
     * eleven targets, so an unguarded run repeats itself almost every time, and the same question
     * twice reads as a bug and inflates the second answer.
     */
    fun session(level: Int, prices: List<Price>, count: Int = 10): List<NumberExercise> {
        val out = mutableListOf<NumberExercise>()
        repeat(count) {
            var e = generate(level, prices)
            var redraws = 0
            while (out.isNotEmpty() && sameQuestion(e, out.last()) && redraws++ < MAX_REDRAWS) e = generate(level, prices)
            out += e
        }
        return out
    }

    fun generate(level: Int, prices: List<Price>): NumberExercise = when (level.coerceIn(NumberProgression.MIN_LEVEL, NumberProgression.MAX_LEVEL)) {
        // A minimum gap of 2 at the entry level: 7 against 8 is a counting question, and this level
        // exists to teach magnitude.
        1 -> compare(1, 0..10, showDots = true, minGap = 2)
        2 -> line(2, (0..10).toList(), SMALL_LINE_CANDIDATES)
        3 -> random.nextInt(1, 11).let { n -> NumberExercise.Count(3, n, optionsAround(n, 1..10)) }
        4 -> compare(4, 0..100, showDots = false)
        5 -> random.nextInt(0, 101).let { n -> NumberExercise.WordMatch(5, n, optionsAround(n, 0..100), showWord = random.nextBoolean()) }
        6 -> if (random.nextBoolean()) compare(6, 0..1000, showDots = false)
             else line(6, (0..1000 step 100).toList(), BIG_LINE_CANDIDATES)
        else -> euro(prices)
    }

    private fun compare(level: Int, range: IntRange, showDots: Boolean, minGap: Int = 1): NumberExercise.Compare {
        val a = random.nextInt(range.first, range.last + 1)
        var b = random.nextInt(range.first, range.last + 1)
        while (kotlin.math.abs(b - a) < minGap) b = random.nextInt(range.first, range.last + 1)
        return NumberExercise.Compare(level, a, b, showDots)
    }

    private fun line(level: Int, ticks: List<Int>, count: Int): NumberExercise.NumberLine {
        val target = ticks[random.nextInt(ticks.size)]
        return NumberExercise.NumberLine(level, target, ticks, candidates(ticks, target, count))
    }

    /**
     * [count] places he may tap, [target] always among them and no two closer than [MIN_TICK_GAP]
     * ticks: two 72dp buttons on neighbouring ticks would overlap, and the pair would read as one
     * place. Greedy over a shuffled order — with eleven ticks a maximal set is at least four, so the
     * requested count is always reached.
     */
    private fun candidates(ticks: List<Int>, target: Int, count: Int): List<Int> {
        val picked = mutableListOf(ticks.indexOf(target))
        for (i in ticks.indices.shuffled(random)) {
            if (picked.size >= count) break
            if (picked.none { kotlin.math.abs(it - i) < MIN_TICK_GAP }) picked += i
        }
        return picked.sorted().map { ticks[it] }
    }

    /** Three distinct options: the answer plus two neighbours within [range]. */
    private fun optionsAround(answer: Int, range: IntRange): List<Int> {
        val pool = (range).filter { it != answer }
        val spread = if (range.last <= 10) 3 else 10
        val near = pool.filter { kotlin.math.abs(it - answer) <= spread }.ifEmpty { pool }
        val picks = near.shuffled(random).take(2).toMutableList()
        while (picks.size < 2) picks += pool.shuffled(random).first { it !in picks }
        return (picks + answer).shuffled(random)
    }

    private fun euro(prices: List<Price>): NumberExercise {
        val kinds = if (prices.map { it.cents }.distinct().size >= 2) listOf("coin", "price", "pay") else listOf("coin", "pay")
        return when (kinds[random.nextInt(kinds.size)]) {
            "coin" -> {
                val target = Euro.denominations[random.nextInt(Euro.denominations.size)]
                val others = Euro.denominations.filter { it != target }.shuffled(random).take(2)
                NumberExercise.CoinPick(7, target, (others + target).shuffled(random))
            }
            "price" -> {
                val two = prices.shuffled(random).let { list -> val a = list[0]; a to list.first { it.cents != a.cents } }
                NumberExercise.PriceCompare(7, two.first, two.second)
            }
            else -> {
                // A price one note can cover, with at least two notes under it: exactly one option is
                // ever enough to pay. Handing over a bigger note than the smallest that fits is what
                // he would do at the counter, so no question may have two right-looking answers.
                val payable = prices.filter { it.cents in MIN_PAYABLE_CENTS..MAX_PAYABLE_CENTS }
                val price = if (payable.isNotEmpty() && random.nextBoolean()) payable[random.nextInt(payable.size)].cents
                            else random.nextInt(MIN_PAYABLE_CENTS / 50 + 1, 40) * 50
                val notes = Euro.denominations.filter { it >= 100 }
                val enough = notes.filter { it >= price }.take(1)
                val tooSmall = notes.filter { it < price }.shuffled(random).take(3 - enough.size)
                NumberExercise.Pay(7, price, (enough + tooSmall).shuffled(random))
            }
        }
    }

    /** Same question, whatever the distractors around it happen to be. */
    private fun sameQuestion(a: NumberExercise, b: NumberExercise): Boolean = key(a) == key(b)

    private fun key(e: NumberExercise): String = when (e) {
        is NumberExercise.Compare -> "compare:${minOf(e.a, e.b)}:${maxOf(e.a, e.b)}"
        is NumberExercise.NumberLine -> "line:${e.target}"
        is NumberExercise.Count -> "count:${e.n}"
        is NumberExercise.WordMatch -> "word:${e.number}:${e.showWord}"
        is NumberExercise.CoinPick -> "coin:${e.targetCents}"
        is NumberExercise.PriceCompare -> "price:${minOf(e.a.name, e.b.name)}:${maxOf(e.a.name, e.b.name)}"
        is NumberExercise.Pay -> "pay:${e.priceCents}"
    }

    companion object {
        /** The biggest note is 50 €. A price above it can never be paid with one, so [euro] skips it. */
        val MAX_PAYABLE_CENTS: Int = Euro.denominations.max()

        /**
         * The smallest price worth asking: two notes have to be too small for it, or a question with
         * exactly one sufficient option cannot be built. One cent over the 2 € note.
         */
        val MIN_PAYABLE_CENTS: Int = Euro.denominations.filter { it >= 100 }.sorted()[1] + 1

        /** Two 72dp buttons a tick apart would overlap on a phone-wide 0..10 line. */
        const val MIN_TICK_GAP = 2

        /** Tap targets on a line: as many as the neighbouring levels' option counts, not eleven. */
        const val SMALL_LINE_CANDIDATES = 3
        const val BIG_LINE_CANDIDATES = 4

        /** A repeat is redrawn this many times; after that the run takes what it got. */
        const val MAX_REDRAWS = 8
    }
}
