package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.Euro
import kotlin.random.Random

class ExerciseGenerator(private val random: Random = Random.Default) {

    fun session(level: Int, prices: List<Price>, count: Int = 10): List<NumberExercise> = List(count) { generate(level, prices) }

    fun generate(level: Int, prices: List<Price>): NumberExercise = when (level.coerceIn(NumberProgression.MIN_LEVEL, NumberProgression.MAX_LEVEL)) {
        1 -> compare(1, 0..10, showDots = true)
        2 -> NumberExercise.NumberLine(2, random.nextInt(0, 11), (0..10).toList())
        3 -> random.nextInt(1, 11).let { n -> NumberExercise.Count(3, n, optionsAround(n, 1..10)) }
        4 -> compare(4, 0..100, showDots = false)
        5 -> random.nextInt(0, 101).let { n -> NumberExercise.WordMatch(5, n, optionsAround(n, 0..100), showWord = random.nextBoolean()) }
        6 -> if (random.nextBoolean()) compare(6, 0..1000, showDots = false)
             else NumberExercise.NumberLine(6, random.nextInt(0, 11) * 100, (0..1000 step 100).toList())
        else -> euro(prices)
    }

    private fun compare(level: Int, range: IntRange, showDots: Boolean): NumberExercise.Compare {
        val a = random.nextInt(range.first, range.last + 1)
        var b = random.nextInt(range.first, range.last + 1)
        while (b == a) b = random.nextInt(range.first, range.last + 1)
        return NumberExercise.Compare(level, a, b, showDots)
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
                val price = if (prices.isNotEmpty() && random.nextBoolean()) prices[random.nextInt(prices.size)].cents else random.nextInt(1, 40) * 50
                val notes = Euro.denominations.filter { it >= 100 }
                val enough = notes.filter { it >= price }.take(2)
                val tooSmall = notes.filter { it < price }.shuffled(random).take(3 - enough.size)
                val options = (enough + tooSmall).distinct().let { o -> if (o.size < 3) (o + notes.filter { it !in o }.shuffled(random).take(3 - o.size)) else o }
                NumberExercise.Pay(7, price, options.shuffled(random))
            }
        }
    }
}
