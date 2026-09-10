package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.core.greek.GreekTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExerciseGeneratorTest {
    private val prices = listOf(Price("καφές", 250), Price("σουβλάκι", 380), Price("νερό", 50))

    /**
     * A generator of this test's own, from a seed of its own.
     *
     * Never a field shared between tests: with one stream for the whole class, what each test sees
     * depends on JUnit's method ordering *and* on how many draws every test before it happened to
     * take, so adding or renaming any test silently reshuffles all the others — and an assertion
     * that would have caught a real defect can go green for a whole release on the draws it was
     * handed. One did.
     */
    private fun gen(seed: Int = 42) = ExerciseGenerator(Random(seed))

    /** [n] draws at [level], from a stream that depends on the level and on nothing else. */
    private fun many(level: Int, n: Int = 200) = gen(level * 31).let { g -> List(n) { g.generate(level, prices) } }

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
        assertEquals(4, gen().session(1, prices, exercisesFor(4)).size)
        // Free practice hands over the full ten, and a session may never ask for none.
        assertEquals(10, exercisesFor(10))
        assertEquals(1, exercisesFor(0))
        assertEquals(10, exercisesFor(25))
    }

    @Test fun `session has the requested size and only that level`() {
        val s = gen().session(3, prices, 10)
        assertEquals(10, s.size); assertTrue(s.all { it.level == 3 })
    }

    /** The same question twice in a row reads as a bug, and the second answer is not his own. */
    @Test fun `a run never asks the same question twice in a row`() {
        (NumberProgression.MIN_LEVEL..NumberProgression.MAX_LEVEL).forEach { level ->
            repeat(20) { seed ->
                val run = ExerciseGenerator(Random(seed)).session(level, prices, 10)
                run.zipWithNext().forEach { (a, b) -> assertTrue("level $level repeated $a", sameQuestion(a, b).not()) }
            }
        }
    }

    /** Whatever the caller passes, the exercise is one of the fifteen levels. */
    @Test fun `a level outside one to fifteen is clamped`() {
        assertEquals(1, gen().generate(0, prices).level)
        assertEquals(1, gen().generate(-5, prices).level)
        assertEquals(15, gen().generate(16, prices).level)
        assertEquals(15, gen().generate(Int.MAX_VALUE, prices).level)
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
        (NumberProgression.MIN_LEVEL..NumberProgression.MAX_LEVEL).forEach { level ->
            assertTrue(gen().generate(level, prices).prompt.isNotBlank())
        }
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
        a is NumberExercise.Arithmetic && b is NumberExercise.Arithmetic -> a.a == b.a && a.op == b.op && a.b == b.b
        a is NumberExercise.Missing && b is NumberExercise.Missing ->
            a.known == b.known && a.op == b.op && a.result == b.result && a.missingFirst == b.missingFirst
        a is NumberExercise.Change && b is NumberExercise.Change -> a.paidCents == b.paidCents && a.priceCents == b.priceCents
        a is NumberExercise.Clock && b is NumberExercise.Clock -> a.answer == b.answer
        a is NumberExercise.DayAfter && b is NumberExercise.DayAfter -> a.from == b.from && a.plus == b.plus
        a is NumberExercise.WordProblem && b is NumberExercise.WordProblem -> a.prompt == b.prompt
        else -> false
    }

    // ------------------------------------------------------- levels 8–15: phase 12

    /**
     * The one promise every level owes him, at every level, five hundred draws deep: four buttons,
     * none of them the same, exactly one of them right, and nothing thrown on the way there.
     *
     * Five hundred and not fifty because the levels below are built digit by digit out of several
     * random draws each, and an impossible combination that happens one time in three hundred is a
     * crash on a phone in a kitchen in Thessaloniki rather than a red line here.
     */
    @Test fun `every level draws five hundred questions with exactly one right answer`() {
        (NumberProgression.MIN_LEVEL..NumberProgression.MAX_LEVEL).forEach { level ->
            val generator = ExerciseGenerator(Random(level * 1_000))
            repeat(DRAWS) { draw ->
                val e = generator.generate(level, prices)
                val where = "level $level draw $draw: $e"
                assertEquals("$where repeats an option", e.options.size, e.options.toSet().size)
                assertEquals("$where has not exactly one right answer", 1, e.options.count { it == e.answer })
                assertEquals("$where is not at the level it was asked for", level, e.level)
                assertTrue("$where has no prompt", e.prompt.isNotBlank())
                assertTrue("$where says nothing", e.spokenPrompt.isNotBlank())
            }
        }
    }

    /** From level 8 up it is always four buttons: the levels below settled on two and three. */
    @Test fun `the harder levels all offer four options`() {
        (8..NumberProgression.MAX_LEVEL).forEach { level ->
            many(level, 100).forEach { e -> assertEquals("level $level: ${e.options}", ExerciseGenerator.OPTIONS, e.options.size) }
        }
    }

    @Test fun `level 8 adds and subtracts under a hundred, and never carries`() {
        val all = many(8, 400).map { it as NumberExercise.Arithmetic }
        assertTrue("both operations", all.any { it.op == Op.ADD } && all.any { it.op == Op.SUB })
        all.forEach { e ->
            assertTrue("two-digit numbers only: $e", e.a in 10..99 && e.b in 10..99)
            assertTrue("under a hundred: $e", e.answer in 10..99)
            assertEquals(e.op.apply(e.a, e.b), e.answer)
            if (e.op == Op.ADD) {
                assertTrue("the units carry in $e", e.a % 10 + e.b % 10 <= 9)
                assertTrue("the tens carry in $e", e.a / 10 + e.b / 10 <= 9)
            } else {
                assertTrue("the units borrow in $e", e.a % 10 >= e.b % 10)
                assertTrue("the tens borrow in $e", e.a / 10 > e.b / 10)
            }
            assertTrue("an option off the level: ${e.options}", e.options.all { it in 0..99 })
        }
    }

    @Test fun `level 9 always carries or borrows, and stops at a thousand`() {
        val all = many(9, 400).map { it as NumberExercise.Arithmetic }
        assertTrue("both operations", all.any { it.op == Op.ADD } && all.any { it.op == Op.SUB })
        all.forEach { e ->
            assertEquals(e.op.apply(e.a, e.b), e.answer)
            assertTrue("past a thousand: $e", e.answer in 1..1000)
            assertTrue("operands sayable: $e", e.a in 10..999 && e.b in 10..999)
            if (e.op == Op.ADD) assertTrue("nothing carried in $e", e.a % 10 + e.b % 10 >= 10)
            else assertTrue("nothing borrowed in $e", e.a % 10 < e.b % 10)
            assertTrue("an option off the level: ${e.options}", e.options.all { it in 0..1000 })
        }
        // The point of the level: sums that cross a hundred, not eighty-plus-eleven over and over.
        assertTrue("no sum ever crosses a hundred", all.any { it.op == Op.ADD && it.answer > 100 })
    }

    @Test fun `level 10 is the tables from two to ten`() {
        many(10, 400).map { it as NumberExercise.Arithmetic }.forEach { e ->
            assertEquals(Op.MUL, e.op)
            assertTrue("off the tables: $e", e.a in 2..10 && e.b in 2..10)
            assertEquals(e.a * e.b, e.answer)
            assertTrue("an option off the level: ${e.options}", e.options.all { it in 1..120 })
        }
    }

    @Test fun `level 11 divides exactly and asks which operand is missing`() {
        val all = many(11, 400)
        val divisions = all.filterIsInstance<NumberExercise.Arithmetic>()
        val blanks = all.filterIsInstance<NumberExercise.Missing>()
        assertTrue("both kinds", divisions.isNotEmpty() && blanks.isNotEmpty())
        assertEquals("nothing else at this level", all.size, divisions.size + blanks.size)
        divisions.forEach { e ->
            assertEquals(Op.DIV, e.op)
            // Never a remainder: «τριάντα δύο διά πέντε» is not a question he can answer with a tap.
            assertEquals("$e does not come out exactly", 0, e.a % e.b)
            assertEquals(e.a / e.b, e.answer)
            assertTrue("off the tables: $e", e.answer in 2..10 && e.b in 2..10)
        }
        blanks.forEach { e ->
            assertEquals(Op.MUL, e.op)
            // Whatever the blank is, filling it in has to make the line true.
            assertEquals("$e does not add up", e.result, e.op.apply(e.known, e.missing))
            assertEquals(e.missing, e.answer)
            assertTrue("no blank in ${e.equation}", e.equation.contains(NumberExercise.BLANK))
            assertTrue("${e.equation} keeps the result", e.equation.endsWith("= ${e.result}"))
        }
        assertTrue("the blank goes on both sides", blanks.any { it.missingFirst } && blanks.any { !it.missingFirst })
    }

    @Test fun `level 12 asks for change from a note that covers the price`() {
        val all = many(12, 400).map { it as NumberExercise.Change }
        all.forEach { e ->
            assertTrue("${e.paidCents} is not a note", e.paidCents in ExerciseGenerator.NOTES)
            assertTrue("the note does not cover ${e.priceCents}", e.priceCents < e.paidCents)
            assertEquals(e.paidCents - e.priceCents, e.answer)
            // Solvable with real money, to the cent: the coins that make the change add up to it.
            assertTrue("no coins make ${e.answer}", e.pieces.isNotEmpty())
            assertEquals("the coins do not add up: ${e.pieces}", e.answer, e.pieces.sum())
            assertTrue("change bigger than the note: ${e.options}", e.options.all { it in 1..e.paidCents })
            // Round tens only, so the phone never has to say «ένα λεπτά».
            assertEquals("${e.answer} is not a round ten", 0, e.answer % 10)
        }
    }

    /** His own shopping, where a caregiver priced it in the range a note gives change on. */
    @Test fun `a caregiver's price becomes a change question, and a fifty-cent one does not`() {
        val all = List(300) { ExerciseGenerator(Random(it)).generate(12, prices) }.map { it as NumberExercise.Change }
        assertTrue("none of his prices were ever used", all.any { it.priceCents == 250 || it.priceCents == 380 })
        // 0,50 € is under CHANGE_MIN_PRICE: the change would be almost the whole note back.
        assertTrue("a fifty-cent price has no change question in it", all.none { it.priceCents == 50 })
        assertTrue("a price that is not a round ten", all.all { it.priceCents % 10 == 0 })
    }

    @Test fun `level 12 without prices still asks for change`() {
        val all = List(100) { ExerciseGenerator(Random(it)).generate(12, emptyList()) }.map { it as NumberExercise.Change }
        all.forEach { e ->
            assertTrue(e.priceCents in ExerciseGenerator.CHANGE_MIN_PRICE..ExerciseGenerator.CHANGE_MAX_PRICE)
            assertEquals(e.answer, e.pieces.sum())
        }
    }

    @Test fun `level 13 draws a clock on a five-minute face and counts days forward`() {
        val all = many(13, 400)
        val clocks = all.filterIsInstance<NumberExercise.Clock>()
        val days = all.filterIsInstance<NumberExercise.DayAfter>()
        assertTrue("both kinds", clocks.isNotEmpty() && days.isNotEmpty())
        assertEquals("nothing else at this level", all.size, clocks.size + days.size)
        clocks.forEach { e ->
            assertTrue("off the face: ${e.minutes}", e.minutes in 0 until GreekTime.MINUTES)
            assertEquals("not on a five-minute mark: ${e.minutes}", 0, e.minutes % 5)
            assertEquals(GreekTime.normalise(e.minutes), e.answer)
            e.options.forEach { v ->
                assertTrue("an option off the face: $v", v in 0 until GreekTime.MINUTES)
                assertEquals("an option a face cannot show: $v", 0, v % 5)
            }
        }
        days.forEach { e ->
            assertTrue("$e", e.from in GreekTime.days.indices && e.plus in 2..6)
            assertEquals(GreekTime.dayAfter(e.from, e.plus), e.answer)
            assertTrue("an option that is not a day: ${e.options}", e.options.all { it in GreekTime.days.indices })
        }
        // The quarters are what a conversation uses, so they are what he mostly meets.
        assertTrue("the quarters never come up", clocks.count { it.minutes % 15 == 0 } > clocks.size / 3)
    }

    @Test fun `level 14 matches words and digits up to 9999, both ways round`() {
        val all = many(14, 400).map { it as NumberExercise.WordMatch }
        all.forEach { e ->
            assertTrue("out of range: ${e.number}", e.number in 100..GreekNumbers.MAX)
            assertTrue(e.number in e.options)
            assertTrue("an unsayable option: ${e.options}", e.options.all { it in 0..GreekNumbers.MAX })
        }
        assertTrue("one direction only", all.any { it.showWord } && all.any { !it.showWord })
        assertTrue("four digits are the point of this level", all.count { it.number >= 1000 } > all.size / 2)
        assertTrue("never a three-digit one", all.any { it.number < 1000 })
    }

    @Test fun `level 15 asks a two-step problem, shown in digits and said in words`() {
        val all = many(15, 400).map { it as NumberExercise.WordProblem }
        all.forEach { e ->
            assertTrue("an answer he could not reach: $e", e.answer > 0)
            assertTrue(e.answer in e.options)
            assertTrue("digits for the eye: ${e.prompt}", e.prompt.any { c -> c.isDigit() })
            // The ear gets words: Greek TTS handed "3" in the middle of a sentence is a coin toss.
            assertTrue("digits in the spoken form: ${e.spoken}", e.spoken.none { c -> c.isDigit() })
            assertTrue("not a question: ${e.prompt}", e.prompt.endsWith(";"))
            assertTrue("an option off the level: ${e.options}", e.options.all { it in 0..150 })
        }
        // Five situations, not five wordings of one: four different first words is the floor.
        assertTrue("one story over and over", all.map { it.prompt.substringBefore(' ') }.distinct().size >= 4)
    }

    /**
     * **Every story, over five thousand seeds each, has an answer that can happen and can be said.**
     *
     * The bus shape used to draw «κατεβαίνουν» free of «είναι» and «ανεβαίνουν», so about one draw in
     * 2,400 asked how many people are left on a bus after eight get off a bus that holds seven. The
     * answer was negative: an impossible question, a button reading `-1`, and — because a negative
     * number has no Greek word — `GreekNumbers.words` throwing out of the coroutine that speaks the
     * answer, killing the sitting and the rows it had not written.
     *
     * A rate like that is invisible to a test that takes one stream of 400 draws, which is exactly
     * how it survived: the assertion below existed and passed on the seed it happened to get. So this
     * one sweeps seeds rather than draws, checks every shape separately, and asserts the *range* both
     * ends — because too big is the same class of defect as below zero, and the option range is what
     * the buttons can actually hold.
     */
    @Test fun `every level 15 story stays inside the answers a story can have`() {
        val byShape = mutableMapOf<String, Int>()
        (0 until PROBLEM_SEEDS).forEach { seed ->
            val g = gen(seed)
            repeat(PROBLEM_DRAWS) {
                val e = g.generate(15, prices) as NumberExercise.WordProblem
                val shape = SHAPES.first { it in e.prompt }
                byShape[shape] = (byShape[shape] ?: 0) + 1
                assertTrue(
                    "seed $seed: an answer no story can have and no word can say: ${e.answer} — ${e.prompt}",
                    e.answer in ExerciseGenerator.PROBLEM_ANSWERS,
                )
                assertTrue("seed $seed: an option off the buttons: ${e.options} — ${e.prompt}",
                    e.options.all { it in ExerciseGenerator.PROBLEM_RANGE })
                assertEquals("seed $seed: not one right answer: ${e.options}", 1, e.options.count { it == e.answer })
                // Every one of them is sayable, which is the other half of what went wrong.
                assertTrue(GreekNumbers.words(e.answer).isNotBlank())
            }
        }
        assertEquals("a shape that never came up: $byShape", SHAPES.size, byShape.size)
        // Five shapes out of 25 000 draws is ~5 000 each; half of that is still five thousand seeds
        // deep and is a floor a fair draw cannot fall through.
        val floor = PROBLEM_SEEDS * PROBLEM_DRAWS / SHAPES.size / 2
        byShape.forEach { (shape, n) ->
            assertTrue("$shape was drawn only $n times, too few to have proved anything", n >= floor)
        }
    }

    /**
     * Level 15's one-step answer is the whole point of the level, so it may not be quietly filtered
     * out for colliding with the right one: at a 10 € bill paid with a twenty the change *is* the
     * cost, and the button that catches a man who stopped halfway disappears.
     */
    @Test fun `the buy-and-change story never pays with exactly twice the bill`() {
        val bought = (0 until 400).flatMap { seed -> gen(seed).let { g -> List(5) { g.generate(15, prices) } } }
            .filterIsInstance<NumberExercise.WordProblem>()
            .filter { BUY in it.prompt }
        assertTrue(bought.isNotEmpty())
        // 25 € is the one bill no other note covers (only the fifty is bigger), so there — and only
        // there, about one buy-story in twenty — the collision is allowed to stand.
        val collided = bought.filter { it.answer * 2 == paidOf(it) }
        assertTrue("the change equals the bill in ${collided.size} of ${bought.size}", collided.size < bought.size / 10)
        collided.forEach { assertEquals("only the 25 € bill may collide: ${it.prompt}", 50, paidOf(it)) }
    }

    /** The note in «Δίνεις 20 ευρώ», read back off the sentence. */
    private fun paidOf(e: NumberExercise.WordProblem): Int =
        e.prompt.substringAfter("Δίνεις ").substringBefore(" ευρώ").toInt()

    /** A run at the hardest level is still ten questions, and a mixed session still gets its four. */
    @Test fun `a session at the top of the ladder is a full session`() {
        assertEquals(10, gen().session(15, prices, 10).size)
        assertEquals(4, gen().session(13, prices, exercisesFor(4)).size)
        assertTrue(gen().session(12, emptyList(), 10).all { it.level == 12 })
    }

    private companion object {
        /** Draws per level in the sweep above. Enough for a one-in-three-hundred combination. */
        const val DRAWS = 500

        /** Seeds per level-15 shape, and draws per seed: 25 000 stories, ~5 000 of each shape. */
        const val PROBLEM_SEEDS = 5_000
        const val PROBLEM_DRAWS = 5

        /** One word out of each of the five stories, enough to tell them apart. */
        val SHAPES = listOf("κουτιά", "λεωφορείο", "Ξοδεύεις", "Μοιράζεις", "Αγοράζεις")
        const val BUY = "Αγοράζεις"
    }
}
