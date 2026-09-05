package gr.dimitris.app.modules.numbers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExerciseGeneratorTest {
    private val gen = ExerciseGenerator(Random(42))
    private val prices = listOf(Price("καφές", 250), Price("σουβλάκι", 380), Price("νερό", 50))

    private fun many(level: Int, n: Int = 200) = List(n) { gen.generate(level, prices) }

    @Test fun `level 1 compares numbers 0 to 10 with dots, never two neighbours`() {
        many(1).forEach { e ->
            e as NumberExercise.Compare
            assertTrue(e.a in 0..10 && e.b in 0..10 && e.showDots)
            // The entry level teaches magnitude: 7 against 8 is a counting question, not that.
            assertTrue("gap ${e.a} vs ${e.b}", kotlin.math.abs(e.a - e.b) >= 2)
            assertEquals(maxOf(e.a, e.b), e.answer)
            assertEquals(listOf(e.a, e.b), e.options)
        }
    }

    @Test fun `level 2 number line draws eleven ticks and offers three places`() {
        many(2).forEach { e ->
            e as NumberExercise.NumberLine
            assertEquals((0..10).toList(), e.ticks)
            assertEquals(ExerciseGenerator.SMALL_LINE_CANDIDATES, e.candidates.size)
            assertEquals(e.candidates, e.options)
            assertTrue("the answer is always one of the places", e.target in e.candidates)
            assertEquals(e.target, e.answer)
            assertEquals("ascending, like the line", e.candidates.sorted(), e.candidates)
            assertGapsAreWideEnough(e)
        }
    }

    /** Every number on the line must be askable, at both line sizes. */
    @Test fun `every tick is reachable as a target`() {
        val small = List(400) { ExerciseGenerator(Random(it)).generate(2, prices) }.map { (it as NumberExercise.NumberLine).target }
        assertEquals((0..10).toSet(), small.toSet())
        val big = List(800) { ExerciseGenerator(Random(it)).generate(6, prices) }
            .filterIsInstance<NumberExercise.NumberLine>().map { it.target }
        assertEquals((0..1000 step 100).toSet(), big.toSet())
    }

    @Test fun `level 3 counting offers three options including the count`() {
        many(3).forEach { e -> e as NumberExercise.Count; assertTrue(e.n in 1..10); assertEquals(3, e.options.size); assertTrue(e.n in e.options); assertEquals(e.options.toSet().size, 3) }
    }

    @Test fun `level 4 compares 0 to 100 without dots`() {
        many(4).forEach { e -> e as NumberExercise.Compare; assertTrue(e.a in 0..100 && e.b in 0..100 && !e.showDots) }
        assertTrue(many(4).any { (it as NumberExercise.Compare).a > 10 })
    }

    @Test fun `level 5 word match has three distinct options containing the number`() {
        many(5).forEach { e -> e as NumberExercise.WordMatch; assertTrue(e.number in 0..100); assertEquals(3, e.options.toSet().size); assertTrue(e.number in e.options); assertEquals(e.number, e.answer) }
        assertTrue(many(5).any { (it as NumberExercise.WordMatch).showWord } && many(5).any { !(it as NumberExercise.WordMatch).showWord })
    }

    @Test fun `level 5 says the Greek word it puts on the screen`() {
        val shown = NumberExercise.WordMatch(5, 73, listOf(73, 37, 70), showWord = true)
        assertEquals("Ποιος αριθμός είναι; εβδομήντα τρία", shown.spokenPrompt)
        // The other way round the words are the buttons: saying it would be reading out the answer.
        val hidden = NumberExercise.WordMatch(5, 73, listOf(73, 37, 70), showWord = false)
        assertEquals(hidden.prompt, hidden.spokenPrompt)
    }

    @Test fun `level 6 mixes compare and number line up to 1000`() {
        val all = many(6)
        assertTrue(all.any { it is NumberExercise.Compare } && all.any { it is NumberExercise.NumberLine })
        all.forEach {
            when (it) {
                is NumberExercise.Compare -> assertTrue(it.a in 0..1000 && it.b in 0..1000 && it.a != it.b)
                is NumberExercise.NumberLine -> {
                    assertEquals((0..1000 step 100).toList(), it.ticks)
                    // Four places wherever the target leaves room for four; three otherwise, because
                    // a target that cannot be asked at all is worse than one option fewer.
                    assertTrue("${it.candidates}", it.candidates.size in 3..ExerciseGenerator.BIG_LINE_CANDIDATES)
                    assertTrue(it.target in it.candidates)
                    assertGapsAreWideEnough(it)
                }
                else -> throw AssertionError("unexpected $it")
            }
        }
    }

    @Test fun `level 7 uses euro exercises and real prices`() {
        val all = many(7)
        assertTrue(all.any { it is NumberExercise.CoinPick } && all.any { it is NumberExercise.PriceCompare } && all.any { it is NumberExercise.Pay })
        all.filterIsInstance<NumberExercise.PriceCompare>().forEach { assertTrue(it.a in prices && it.b in prices && it.a != it.b); assertEquals(maxOf(it.a.cents, it.b.cents), it.answer) }
        all.filterIsInstance<NumberExercise.CoinPick>().forEach { assertTrue(it.answer in it.options && it.options.toSet().size == 3) }
    }

    /**
     * The one thing a Pay question may never do: offer two notes that would both work at the counter
     * and mark one of them wrong. He is not being asked for the smallest note — nothing says so.
     */
    @Test fun `exactly one Pay option is enough to pay the price`() {
        val all = many(7) + List(200) { ExerciseGenerator(Random(it)).generate(7, prices) }
        val pays = all.filterIsInstance<NumberExercise.Pay>()
        assertTrue(pays.isNotEmpty())
        pays.forEach { e ->
            assertEquals("options: ${e.options} for ${e.priceCents}", 1, e.options.count { it >= e.priceCents })
            assertEquals("three distinct notes", 3, e.options.toSet().size)
            assertTrue(e.answer in e.options && e.answer >= e.priceCents)
        }
    }

    @Test fun `level 7 without prices still works`() {
        val list = List(50) { ExerciseGenerator(Random(it)).generate(7, emptyList()) }
        assertTrue(list.none { it is NumberExercise.PriceCompare })
    }

    @Test fun `a price no note covers never becomes a Pay exercise`() {
        // Pay.answer is the smallest note that is enough: a 75 euro item would leave it with none.
        val tooDear = listOf(Price("τηλέφωνο", 7500))
        val all = List(100) { ExerciseGenerator(Random(it)).generate(7, tooDear) }
        all.filterIsInstance<NumberExercise.Pay>().forEach { e ->
            assertTrue("7500 must not become a Pay price", e.priceCents != 7500)
            assertTrue(e.answer in e.options && e.answer >= e.priceCents)
        }
        assertTrue(all.any { it is NumberExercise.Pay })
    }

    /** Under two euro there are not two notes too small for it, so such a price is never asked. */
    @Test fun `a price too small to have two notes under it never becomes a Pay exercise`() {
        val cheap = listOf(Price("τσίχλα", 50), Price("νερό", 100))
        val all = List(200) { ExerciseGenerator(Random(it)).generate(7, cheap) }
        all.filterIsInstance<NumberExercise.Pay>().forEach { e ->
            assertTrue("cheap price ${e.priceCents}", e.priceCents >= ExerciseGenerator.MIN_PAYABLE_CENTS)
            assertEquals(1, e.options.count { it >= e.priceCents })
        }
    }

    @Test fun `a four-item plan yields four exercises`() {
        assertEquals(4, exercisesFor(4))
        assertEquals(4, gen.session(1, prices, exercisesFor(4)).size)
        // Free practice hands over the full ten, and a session may never ask for none.
        assertEquals(10, exercisesFor(10))
        assertEquals(1, exercisesFor(0))
        assertEquals(10, exercisesFor(25))
    }

    @Test fun `session has the requested size and only that level`() {
        val s = gen.session(3, prices, 10)
        assertEquals(10, s.size); assertTrue(s.all { it.level == 3 })
    }

    /** The same question twice in a row reads as a bug, and the second answer is not his own. */
    @Test fun `a run never asks the same question twice in a row`() {
        (1..7).forEach { level ->
            repeat(20) { seed ->
                val run = ExerciseGenerator(Random(seed)).session(level, prices, 10)
                run.zipWithNext().forEach { (a, b) -> assertTrue("level $level repeated $a", sameQuestion(a, b).not()) }
            }
        }
    }

    /** Whatever the caller passes, the exercise is one of the seven levels. */
    @Test fun `a level outside one to seven is clamped`() {
        assertEquals(1, gen.generate(0, prices).level)
        assertEquals(1, gen.generate(-5, prices).level)
        assertEquals(7, gen.generate(8, prices).level)
        assertEquals(7, gen.generate(Int.MAX_VALUE, prices).level)
    }

    /** A caregiver who priced everything the same still gets euro questions, just not comparisons. */
    @Test fun `prices that are all equal produce no price comparison`() {
        val same = listOf(Price("καφές", 250), Price("τσάι", 250))
        val all = List(100) { ExerciseGenerator(Random(it)).generate(7, same) }
        assertTrue(all.none { it is NumberExercise.PriceCompare })
        assertTrue(all.any { it is NumberExercise.CoinPick } && all.any { it is NumberExercise.Pay })
    }

    @Test fun `euro questions are asked in words, not punctuation`() {
        val coin = NumberExercise.CoinPick(7, 1000, listOf(1000, 200, 500))
        // The written question too: "10,00 €" is printed on one of the buttons, and matching two
        // identical strings is not knowing money.
        assertEquals("Ποιο είναι δέκα ευρώ;", coin.prompt)
        assertEquals(coin.prompt, coin.spokenPrompt)
        assertEquals("Ποιο είναι πενήντα λεπτά;", NumberExercise.CoinPick(7, 50, listOf(50, 200, 500)).prompt)
        val pay = NumberExercise.Pay(7, 250, listOf(100, 500, 200))
        assertTrue(pay.spokenPrompt.contains("δύο ευρώ και πενήντα λεπτά"))
        // Everything else says exactly what it shows.
        val compare = NumberExercise.Compare(1, 2, 5, showDots = true)
        assertEquals(compare.prompt, compare.spokenPrompt)
    }

    @Test fun `the coin question never prints the amount it is asking for`() {
        List(200) { ExerciseGenerator(Random(it)).generate(7, prices) }.filterIsInstance<NumberExercise.CoinPick>().forEach { e ->
            assertTrue("prompt gives it away: ${e.prompt}", !e.prompt.contains(gr.dimitris.app.core.greek.Euro.format(e.targetCents)))
            assertTrue("prompt has a digit in it: ${e.prompt}", e.prompt.none { c -> c.isDigit() })
        }
    }

    @Test fun `every exercise has a Greek prompt`() {
        (1..7).forEach { level -> assertTrue(gen.generate(level, prices).prompt.isNotBlank()) }
    }

    private fun assertGapsAreWideEnough(e: NumberExercise.NumberLine) {
        val indices = e.candidates.map { e.ticks.indexOf(it) }
        assertTrue("all candidates are ticks", indices.none { it < 0 })
        indices.sorted().zipWithNext().forEach { (a, b) ->
            assertTrue("two 72dp buttons ${e.candidates} would overlap", b - a >= ExerciseGenerator.MIN_TICK_GAP)
        }
    }

    /** The generator's own idea of "the same question", mirrored here so the test can assert on it. */
    private fun sameQuestion(a: NumberExercise, b: NumberExercise): Boolean = when {
        a is NumberExercise.Compare && b is NumberExercise.Compare -> setOf(a.a, a.b) == setOf(b.a, b.b)
        a is NumberExercise.NumberLine && b is NumberExercise.NumberLine -> a.target == b.target
        a is NumberExercise.Count && b is NumberExercise.Count -> a.n == b.n
        a is NumberExercise.WordMatch && b is NumberExercise.WordMatch -> a.number == b.number && a.showWord == b.showWord
        a is NumberExercise.CoinPick && b is NumberExercise.CoinPick -> a.targetCents == b.targetCents
        a is NumberExercise.PriceCompare && b is NumberExercise.PriceCompare -> setOf(a.a.name, a.b.name) == setOf(b.a.name, b.b.name)
        a is NumberExercise.Pay && b is NumberExercise.Pay -> a.priceCents == b.priceCents
        else -> false
    }
}
