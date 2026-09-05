package gr.dimitris.app.modules.numbers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExerciseGeneratorTest {
    private val gen = ExerciseGenerator(Random(42))
    private val prices = listOf(Price("καφές", 250), Price("σουβλάκι", 380), Price("νερό", 50))

    private fun many(level: Int, n: Int = 200) = List(n) { gen.generate(level, prices) }

    @Test fun `level 1 compares distinct numbers 0 to 10 with dots`() {
        many(1).forEach { e ->
            e as NumberExercise.Compare
            assertTrue(e.a in 0..10 && e.b in 0..10 && e.a != e.b && e.showDots)
            assertEquals(maxOf(e.a, e.b), e.answer)
            assertEquals(listOf(e.a, e.b), e.options)
        }
    }

    @Test fun `level 2 number line has eleven ticks and the target on it`() {
        many(2).forEach { e -> e as NumberExercise.NumberLine; assertEquals((0..10).toList(), e.ticks); assertTrue(e.target in e.ticks); assertEquals(e.target, e.answer) }
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

    @Test fun `level 6 mixes compare and number line up to 1000`() {
        val all = many(6)
        assertTrue(all.any { it is NumberExercise.Compare } && all.any { it is NumberExercise.NumberLine })
        all.forEach {
            when (it) {
                is NumberExercise.Compare -> assertTrue(it.a in 0..1000 && it.b in 0..1000 && it.a != it.b)
                is NumberExercise.NumberLine -> { assertEquals((0..1000 step 100).toList(), it.ticks); assertTrue(it.target in it.ticks) }
                else -> throw AssertionError("unexpected $it")
            }
        }
    }

    @Test fun `level 7 uses euro exercises and real prices`() {
        val all = many(7)
        assertTrue(all.any { it is NumberExercise.CoinPick } && all.any { it is NumberExercise.PriceCompare } && all.any { it is NumberExercise.Pay })
        all.filterIsInstance<NumberExercise.PriceCompare>().forEach { assertTrue(it.a in prices && it.b in prices && it.a != it.b); assertEquals(maxOf(it.a.cents, it.b.cents), it.answer) }
        all.filterIsInstance<NumberExercise.Pay>().forEach { e -> assertTrue(e.answer in e.options); assertTrue(e.answer >= e.priceCents); assertTrue(e.options.filter { it >= e.priceCents }.min() == e.answer) }
        all.filterIsInstance<NumberExercise.CoinPick>().forEach { assertTrue(it.answer in it.options && it.options.toSet().size == 3) }
    }

    @Test fun `level 7 without prices still works`() {
        val list = List(50) { ExerciseGenerator(Random(it)).generate(7, emptyList()) }
        assertTrue(list.none { it is NumberExercise.PriceCompare })
    }

    @Test fun `session has the requested size and only that level`() {
        val s = gen.session(3, prices, 10)
        assertEquals(10, s.size); assertTrue(s.all { it.level == 3 })
    }

    @Test fun `every exercise has a Greek prompt`() {
        (1..7).forEach { level -> assertTrue(gen.generate(level, prices).prompt.isNotBlank()) }
    }
}
