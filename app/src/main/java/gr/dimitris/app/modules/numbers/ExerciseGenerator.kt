package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.core.greek.GreekTime
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
        7 -> euro(prices)
        // He told us the puzzles up to ten were far too easy. From here on the ladder is arithmetic,
        // then money he has to give back, then the clock and the week, then four-digit words, then a
        // problem with two steps in it. Every one of them is still four buttons and a tap.
        8 -> plain(8)
        9 -> carried(9)
        10 -> times(10)
        11 -> if (random.nextBoolean()) divide(11) else missing(11)
        12 -> change(12, prices)
        13 -> if (random.nextBoolean()) clock(13) else dayAfter(13)
        14 -> bigWord(14)
        else -> problem(15)
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
     * Up to [count] places he may tap, [target] always among them and no two closer than
     * [MIN_TICK_GAP] ticks — that is what a 72dp circle needs on an eleven-tick line at his screen
     * width, and two that overlap are one place with a hidden half.
     *
     * [count] is a ceiling, not a promise: on a line of eleven ticks four places at that gap take
     * nine of the ten steps, so a target in the wrong third of the line (200, 500, 800 at level 6)
     * gets three instead. Every target stays askable, which matters more than a constant option count.
     */
    private fun candidates(ticks: List<Int>, target: Int, count: Int): List<Int> {
        val n = ticks.size
        val t = ticks.indexOf(target)
        val k = (2..count).lastOrNull { slotsFor(it, t, n).isNotEmpty() } ?: return listOf(target)
        val picked = IntArray(k)
        // Which place from the left the target takes; the rest are filled around it.
        val mine = slotsFor(k, t, n).random(random)
        picked[mine] = t
        for (i in mine - 1 downTo 0) picked[i] = random.nextInt(i * MIN_TICK_GAP, picked[i + 1] - MIN_TICK_GAP + 1)
        for (i in mine + 1 until k) picked[i] = random.nextInt(picked[i - 1] + MIN_TICK_GAP, n - (k - 1 - i) * MIN_TICK_GAP)
        return picked.map { ticks[it] }
    }

    /** Which places from the left the target at tick [t] may take, if [count] are to fit on [n] ticks. */
    private fun slotsFor(count: Int, t: Int, n: Int): List<Int> =
        (0 until count).filter { j -> j * MIN_TICK_GAP <= t && (count - 1 - j) * MIN_TICK_GAP <= n - 1 - t }

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

    // ------------------------------------------------------------ levels 8–15

    /**
     * Level 8: a sum or a difference under a hundred with **no carry and no borrow** — every column
     * stands on its own, so the only thing being asked is whether he can add or take away two digits.
     *
     * Built digit by digit rather than drawn and filtered, because filtering throws most of the draws
     * away and a loop that usually succeeds is a loop that sometimes does not.
     */
    private fun plain(level: Int): NumberExercise.Arithmetic {
        val add = random.nextBoolean()
        return if (add) {
            val aOnes = random.nextInt(0, 10)
            val bOnes = random.nextInt(0, 10 - aOnes)
            // Both tens digits at least one, and their sum under ten: two real two-digit numbers.
            val aTens = random.nextInt(1, 9)
            val bTens = random.nextInt(1, 10 - aTens)
            val a = aTens * 10 + aOnes
            val b = bTens * 10 + bOnes
            NumberExercise.Arithmetic(level, a, Op.ADD, b, plainOptions(a, Op.ADD, b))
        } else {
            val aTens = random.nextInt(2, 10)
            val aOnes = random.nextInt(0, 10)
            val a = aTens * 10 + aOnes
            // Every digit of b no bigger than the digit above it: nothing is borrowed.
            val b = random.nextInt(1, aTens) * 10 + random.nextInt(0, aOnes + 1)
            NumberExercise.Arithmetic(level, a, Op.SUB, b, plainOptions(a, Op.SUB, b))
        }
    }

    /**
     * Level 9: the same two operations to a thousand, and now **always with a carry or a borrow** —
     * the one step that makes column arithmetic a skill rather than a lookup, and the reason the
     * screen puts the first number on a line of hundreds beside it.
     */
    private fun carried(level: Int): NumberExercise.Arithmetic {
        val add = random.nextBoolean()
        return if (add) {
            // The units carry by construction; the tens sometimes carry too. a + b never passes 1000.
            val aOnes = random.nextInt(1, 10)
            val bOnes = random.nextInt(10 - aOnes, 10)
            val aTens = random.nextInt(1, 98)
            val bTens = random.nextInt(1, 99 - aTens)
            val a = aTens * 10 + aOnes
            val b = bTens * 10 + bOnes
            NumberExercise.Arithmetic(level, a, Op.ADD, b, carriedOptions(a, Op.ADD, b))
        } else {
            // b's units digit above a's: the subtraction has to borrow from the tens.
            val aTens = random.nextInt(11, 100)
            val aOnes = random.nextInt(0, 9)
            val a = aTens * 10 + aOnes
            val b = random.nextInt(1, aTens) * 10 + random.nextInt(aOnes + 1, 10)
            NumberExercise.Arithmetic(level, a, Op.SUB, b, carriedOptions(a, Op.SUB, b))
        }
    }

    /** Level 10: the tables, two to ten, both ways round. */
    private fun times(level: Int): NumberExercise.Arithmetic {
        val a = random.nextInt(2, 11)
        val b = random.nextInt(2, 11)
        val answer = a * b
        // The mistakes a table actually produces: the row above, the row below, one column across,
        // and adding when you meant to multiply.
        val wrong = listOf(a * (b + 1), a * (b - 1), (a + 1) * b, (a - 1) * b, answer + a, answer - b, a + b)
        return NumberExercise.Arithmetic(level, a, Op.MUL, b, options(answer, wrong, 1..120))
    }

    /** Level 11, first half: a division that comes out exactly, straight off the same tables. */
    private fun divide(level: Int): NumberExercise.Arithmetic {
        val b = random.nextInt(2, 11)
        val answer = random.nextInt(2, 11)
        val a = b * answer
        val wrong = listOf(answer + 1, answer - 1, answer + 2, answer - 2, b, a - b, answer * 2)
        return NumberExercise.Arithmetic(level, a, Op.DIV, b, options(answer, wrong, 1..99))
    }

    /**
     * Level 11, second half: the blank is an operand — `? × 4 = 32`.
     *
     * Multiplication both ways round, because a man who can say "eight fours are thirty-two" has not
     * necessarily noticed that the same fact answers "four times what is thirty-two".
     */
    private fun missing(level: Int): NumberExercise.Missing {
        val known = random.nextInt(2, 11)
        val answer = random.nextInt(2, 11)
        val result = known * answer
        val wrong = listOf(answer + 1, answer - 1, answer + 2, answer - 2, known, result - known)
        return NumberExercise.Missing(
            level, known = known, op = Op.MUL, result = result, missing = answer,
            missingFirst = random.nextBoolean(), options = options(answer, wrong, 1..99),
        )
    }

    /**
     * Level 12: change from a note. «Πληρώνεις 20 €, κοστίζει 13,40 €. Πόσα ρέστα;»
     *
     * The price is one of his own where a caregiver priced something in the right range to a round ten
     * λεπτά, and a made-up one otherwise: change of 4,01 € would have the phone saying «ένα λεπτά»,
     * and an exercise about money should not teach him a plural that does not exist. The note is the
     * smallest one that covers the price, or the next one up — which is what a man at a counter
     * actually hands over.
     */
    private fun change(level: Int, prices: List<Price>): NumberExercise.Change {
        val his = prices.map { it.cents }.filter { it in CHANGE_MIN_PRICE..CHANGE_MAX_PRICE && it % 10 == 0 }
        val price = if (his.isNotEmpty() && random.nextBoolean()) his[random.nextInt(his.size)]
                    else random.nextInt(CHANGE_MIN_PRICE / 10, CHANGE_MAX_PRICE / 10 + 1) * 10
        val covering = NOTES.filter { it > price }
        val paid = covering[random.nextInt(minOf(2, covering.size))]
        val answer = paid - price
        // A euro out either way is the carry gone wrong; ten λεπτά out is the column gone wrong; and
        // the price itself is the question misheard, which is the commonest miss of all.
        val wrong = listOf(answer + 100, answer - 100, answer + 10, answer - 10, price, answer + 1000)
        return NumberExercise.Change(level, paid, price, options(answer, wrong, 10..paid, step = 10))
    }

    /**
     * Level 13, first half: a drawn face. Quarters more often than not, because «και τέταρτο» and
     * «και μισή» are the two a conversation actually uses.
     */
    private fun clock(level: Int): NumberExercise.Clock {
        val hour = random.nextInt(0, 12)
        val minute = if (random.nextInt(5) < 3) QUARTERS.random(random) else random.nextInt(0, 12) * 5
        val answer = hour * 60 + minute
        val wrong = listOf(
            // The two hands read the wrong way round: the commonest mistake on a real face.
            (minute / 5) * 60 + hour * 5,
            answer + 60, answer - 60, answer + 15, answer - 15, answer + 5, answer - 5,
            answer + 6 * 60,
        ).map { GreekTime.normalise(it) }
        return NumberExercise.Clock(level, answer, optionsFrom(answer, wrong, FACE_TIMES))
    }

    /** Level 13, second half: «Σήμερα είναι Δευτέρα. Τι μέρα είναι μετά από 3 μέρες;» */
    private fun dayAfter(level: Int): NumberExercise.DayAfter {
        val from = random.nextInt(0, GreekTime.days.size)
        val plus = random.nextInt(2, 7)
        val answer = GreekTime.dayAfter(from, plus)
        // A day either side is a miscount; today is the question unheard; one short is the fencepost.
        val wrong = listOf(answer + 1, answer - 1, from, GreekTime.dayAfter(from, plus - 1))
            .map { GreekTime.dayAfter(it, 0) }
        return NumberExercise.DayAfter(level, from, plus, optionsFrom(answer, wrong, DAY_INDICES))
    }

    /**
     * Level 14: the word for a number up to 9999, and the number for a word. Mostly four digits, with
     * a three-digit one now and then so that a man who has just learned «χιλιάδες» is not left
     * assuming every answer has one in it.
     */
    private fun bigWord(level: Int): NumberExercise.WordMatch {
        val n = if (random.nextInt(4) == 0) random.nextInt(101, 1000) else random.nextInt(1000, GreekNumbers.MAX + 1)
        // Transposed digits are what a damaged reading of a number actually looks like; a hundred or a
        // thousand out is what a damaged hearing of one does.
        val wrong = listOf(
            swap(n, 1, 10), swap(n, 100, 1000), n + 100, n - 100, n + 1000, n - 1000, n + 10, n - 10,
        )
        return NumberExercise.WordMatch(level, n, options(answer = n, wrong = wrong, range = 100..GreekNumbers.MAX), showWord = random.nextBoolean())
    }

    /** [n] with the digits at two place values exchanged: 2345 → 2354, or 2345 → 2435. */
    private fun swap(n: Int, lower: Int, upper: Int): Int {
        val lo = (n / lower) % 10
        val hi = (n / upper) % 10
        return n - lo * lower - hi * upper + hi * lower + lo * upper
    }

    /**
     * Level 15: two steps in one situation, spoken and shown.
     *
     * Five shapes, not five wordings of one shape: times-then-take-away, up-then-down, twice-away,
     * share-then-eat, and buy-then-change. Every noun in them is neuter, because a Greek number agrees
     * with what it counts and «τρεις καφέδες» would need a different word for three than the rest of
     * the module says.
     *
     * The distractors are the one-step answers. That is the whole of what this level tests: a man who
     * stops after the first step gets a button that is there waiting for him, and the row records
     * which one he tapped.
     */
    private fun problem(level: Int): NumberExercise.WordProblem {
        val (shown, said, answer, wrong) = when (random.nextInt(5)) {
            0 -> {
                val k = random.nextInt(2, 6)
                val n = random.nextInt(3, 10)
                val m = random.nextInt(1, k * n)
                told(k * n - m, listOf(k * n, k * n + m, n - m, k * n - m + 10)) { num ->
                    "Έχεις ${num(k)} κουτιά με ${num(n)} αυγά. Σπάνε ${num(m)}. Πόσα μένουν;"
                }
            }
            1 -> {
                val a = random.nextInt(5, 31)
                val b = random.nextInt(2, 10)
                val c = random.nextInt(1, 10)
                told(a + b - c, listOf(a + b, a + b + c, a - b + c, a - c)) { num ->
                    "Στο λεωφορείο είναι ${num(a)} άτομα. Ανεβαίνουν ${num(b)} και κατεβαίνουν ${num(c)}. Πόσα άτομα είναι τώρα;"
                }
            }
            2 -> {
                val a = random.nextInt(20, 51)
                val b = random.nextInt(2, 10)
                val c = random.nextInt(2, 10)
                told(a - b - c, listOf(a - b, a - b + c, a - c, b + c)) { num ->
                    "Έχεις ${num(a)} ευρώ. Ξοδεύεις ${num(b)} ευρώ και μετά ${num(c)} ευρώ. Πόσα σου μένουν;"
                }
            }
            3 -> {
                val plates = random.nextInt(2, 6)
                val each = random.nextInt(3, 10)
                val eaten = random.nextInt(1, each)
                told(each - eaten, listOf(each, plates * each - eaten, plates * each, each + eaten)) { num ->
                    "Μοιράζεις ${num(plates * each)} μπισκότα σε ${num(plates)} πιάτα. Από το ένα πιάτο τρως ${num(eaten)}. Πόσα μένουν σ' αυτό;"
                }
            }
            else -> {
                val k = random.nextInt(2, 6)
                val n = random.nextInt(2, 7)
                val cost = k * n
                val paid = NOTE_EUROS.first { it > cost }
                told(paid - cost, listOf(cost, paid - n, paid - cost + n, paid + cost)) { num ->
                    "Αγοράζεις ${num(k)} μπουκάλια προς ${num(n)} ευρώ. Δίνεις ${num(paid)} ευρώ. Πόσα ρέστα παίρνεις;"
                }
            }
        }
        return NumberExercise.WordProblem(level, shown, said, options(answer, wrong, 0..150), answer)
    }

    /**
     * One story told twice — digits for the eye, words for the ear — plus its answer and the wrong
     * answers that belong to it.
     *
     * [tell] is handed the way to write a number and returns the sentence, so the two versions cannot
     * drift apart: there is one sentence in the source and the numbers in it are spelled by whoever is
     * reading it.
     */
    private fun told(answer: Int, wrong: List<Int>, tell: ((Int) -> String) -> String): Story =
        Story(tell { it.toString() }, tell { GreekNumbers.words(it) }, answer, wrong)

    private data class Story(val shown: String, val said: String, val answer: Int, val wrong: List<Int>)

    /** Level 8's mistakes: a column out, a ten out, or the other operation altogether. */
    private fun plainOptions(a: Int, op: Op, b: Int): List<Int> {
        val answer = op.apply(a, b)
        val other = if (op == Op.ADD) a - b else a + b
        return options(answer, listOf(answer + 10, answer - 10, answer + 1, answer - 1, other), 0..99)
    }

    /** Level 9's mistakes: the carry dropped, a hundred out, or the other operation altogether. */
    private fun carriedOptions(a: Int, op: Op, b: Int): List<Int> {
        val answer = op.apply(a, b)
        val other = if (op == Op.ADD) a - b else a + b
        return options(answer, listOf(answer - 10, answer + 10, answer - 100, answer + 100, answer + 1, other), 0..1000)
    }

    /**
     * [OPTIONS] buttons: the answer, and the most plausible wrong ones that fit [range].
     *
     * A distractor is only worth a button if it is a mistake he could actually make, so [wrong] is the
     * level's own list of those and nothing else gets in ahead of them. Where a level's mistakes run
     * out — a small range, an answer at the edge of it — the rest are filled in from the answer
     * outwards in steps of [step], because four buttons is what every other level gives him and a
     * question with three is a question with a better guess in it.
     */
    private fun options(answer: Int, wrong: List<Int>, range: IntRange, step: Int = 1, count: Int = OPTIONS): List<Int> {
        val picks = mutableListOf(answer)
        wrong.filter { it != answer && it in range }.distinct().shuffled(random)
            .forEach { if (picks.size < count) picks += it }
        var away = step
        while (picks.size < count && away <= range.last - range.first) {
            listOf(answer + away, answer - away).forEach { if (picks.size < count && it in range && it !in picks) picks += it }
            away += step
        }
        return picks.shuffled(random)
    }

    /**
     * The same, where the values are not a range but a set — the times on a five-minute face, the
     * seven days. Anything outside [pool] is not a wrong answer, it is a nonsense one.
     */
    private fun optionsFrom(answer: Int, wrong: List<Int>, pool: List<Int>, count: Int = OPTIONS): List<Int> {
        val picks = mutableListOf(answer)
        wrong.filter { it != answer && it in pool }.distinct().shuffled(random)
            .forEach { if (picks.size < count) picks += it }
        pool.filter { it != answer }.shuffled(random).forEach { if (picks.size < count && it !in picks) picks += it }
        return picks.shuffled(random)
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
        is NumberExercise.Arithmetic -> "arith:${e.a}:${e.op}:${e.b}"
        is NumberExercise.Missing -> "missing:${e.known}:${e.op}:${e.result}:${e.missingFirst}"
        is NumberExercise.Change -> "change:${e.paidCents}:${e.priceCents}"
        is NumberExercise.Clock -> "clock:${e.answer}"
        is NumberExercise.DayAfter -> "day:${e.from}:${e.plus}"
        // The story is the question: two runs of the same shape with different numbers in it are two
        // different questions, and the text is the only thing that holds all of them.
        is NumberExercise.WordProblem -> "problem:${e.prompt}"
    }

    companion object {
        /** The biggest note is 50 €. A price above it can never be paid with one, so [euro] skips it. */
        val MAX_PAYABLE_CENTS: Int = Euro.denominations.max()

        /**
         * The smallest price worth asking: two notes have to be too small for it, or a question with
         * exactly one sufficient option cannot be built. One cent over the 2 € note.
         */
        val MIN_PAYABLE_CENTS: Int = Euro.denominations.filter { it >= 100 }.sorted()[1] + 1

        /**
         * Ticks between two tap targets. His screen is 411dp wide, so an eleven-tick line puts its
         * ticks 30dp apart: at two ticks a 72dp circle overlaps its neighbour by twelve, at three it
         * clears it. Measured on the device, not guessed.
         */
        const val MIN_TICK_GAP = 3

        /** Tap targets on a line: as many as the neighbouring levels' option counts, not eleven. */
        const val SMALL_LINE_CANDIDATES = 3
        const val BIG_LINE_CANDIDATES = 4

        /** A repeat is redrawn this many times; after that the run takes what it got. */
        const val MAX_REDRAWS = 8

        /** Buttons on a level-8-and-up question. Four is what the levels below it settled on. */
        const val OPTIONS = 4

        /** The notes he pays with, in cents: 5, 10, 20 and 50 €. */
        val NOTES: List<Int> = Euro.denominations.filter { it >= 500 }

        /** The same as whole euros, for a word problem that talks about them rather than prints them. */
        val NOTE_EUROS: List<Int> = NOTES.map { it / 100 }

        /**
         * The prices a change question is asked about: over a euro, so the answer is not the whole
         * note back, and never above 45 € so that a 50 € note always covers it.
         */
        const val CHANGE_MIN_PRICE = 110
        const val CHANGE_MAX_PRICE = 4500

        /** The minutes a conversation names: «ακριβώς», «και τέταρτο», «και μισή», «παρά τέταρτο». */
        val QUARTERS: List<Int> = listOf(0, 15, 30, 45)

        /** Every time on a five-minute face, which is every time the clock exercise may show or offer. */
        val FACE_TIMES: List<Int> = (0 until GreekTime.MINUTES step 5).toList()

        val DAY_INDICES: List<Int> = GreekTime.days.indices.toList()
    }
}
