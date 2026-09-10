package gr.dimitris.app.modules.singsay

import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.difficulty.Difficulty
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session budget counts exercises, and a sing-then-say exercise is five stages — two to four
 * minutes — not the half-minute a word-coach item takes. The module has to cap itself below the
 * shared allowance or a three-module session runs for the best part of an hour.
 */
class SingSayModuleTest {
    private val items = FakeItemDao()
    private val schedules = FakeScheduleDao()

    private suspend fun phrase(text: String, createdAt: Long) {
        items.upsert(Item(text = text, kind = ItemKind.PHRASE, source = Source.SEED, createdAt = createdAt))
    }

    @Test fun `a session gets at most three phrases however many are waiting`() = runTest {
        repeat(12) { phrase("φράση $it", createdAt = it.toLong()) }
        val plan = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_SESSION)
        assertTrue("five stages each: ${plan.size} phrases is not one sitting", plan.size <= SingSayModule.MAX_PER_SESSION)
        assertEquals(3, SingSayModule.MAX_PER_SESSION)
    }

    @Test fun `free practice may run to five`() = runTest {
        repeat(12) { phrase("φράση $it", createdAt = it.toLong()) }
        val plan = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE)
        assertTrue(plan.size <= SingSayModule.MAX_PER_PRACTICE)
        assertEquals(5, SingSayModule.MAX_PER_PRACTICE)
    }

    @Test fun `only phrases are ever planned`() = runTest {
        phrase("θέλω καφέ", createdAt = 1)
        items.upsert(Item(text = "νερό", kind = ItemKind.WORD, source = Source.SEED, createdAt = 2))
        val plan = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_SESSION)
        assertEquals(listOf("θέλω καφέ"), plan.map { it.text })
    }

    /**
     * The dots, in the only unit this module's difficulty has: syllables. A syllable is one tapped
     * beat of the melody, so the longer phrase is the harder sitting — and the dot he set is what
     * decides which of the two he is handed.
     */
    @Test fun `the difficulty he set decides how long a phrase he sings`() = runTest {
        phrase("ναι", createdAt = 1)                           // 1 syllable
        phrase("θέλω καφέ", createdAt = 2)                     // 4
        phrase("θέλω να πάω στο σπίτι μου", createdAt = 3)     // 9
        val easy = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 1)
        assertEquals(listOf("ναι"), easy.map { it.text })
        val middle = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 2)
        assertEquals(listOf("θέλω καφέ"), middle.map { it.text })
        val hard = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 5)
        assertEquals(listOf("θέλω να πάω στο σπίτι μου"), hard.map { it.text })
    }

    /**
     * A band the vocabulary cannot fill must not leave him a module with nothing in it. The seed's
     * phrases stop at seven syllables, so the top dot has nothing of its own until somebody writes
     * something longer — and a man who taps the hardest dot and is told «δεν υπάρχει υλικό» has been
     * punished for asking.
     */
    @Test fun `a band nothing falls in widens back to the whole vocabulary`() = runTest {
        phrase("θέλω καφέ", createdAt = 1)
        val hard = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 5)
        assertEquals(listOf("θέλω καφέ"), hard.map { it.text })
    }

    /** Nobody's phone changes on upgrade: the default dot plans what it always planned. */
    @Test fun `the default difficulty plans what the module always planned`() = runTest {
        repeat(12) { phrase("φράση $it", createdAt = it.toLong()) }
        val before = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_SESSION)
        val withDots = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_SESSION, difficulty = Difficulty.DEFAULT)
        assertEquals(before.map { it.id }.toSet(), withDots.map { it.id }.toSet())
    }
}
