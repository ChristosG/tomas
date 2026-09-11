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
     * beat of the melody, so the longer phrase is the harder sitting — and the dot he set is the
     * **longest** he is willing to be asked for.
     */
    @Test fun `the difficulty he set caps how long a phrase he sings`() = runTest {
        phrase("ναι", createdAt = 1)                                  // 1 syllable
        phrase("θέλω καφέ", createdAt = 2)                            // 4
        phrase("Πού είναι η στάση του λεωφορείου;", createdAt = 3)    // 12
        val easy = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 1)
        assertEquals(setOf("ναι", "θέλω καφέ"), easy.map { it.text }.toSet())
        val hard = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 5)
        assertEquals(3, hard.size)
    }

    /**
     * The pool at each dot, after phase 13 moved the ceilings up to 5 / 7 / 9 / 11 / any. One phrase
     * inside each ceiling and one outside it, so every dot is pinned from both sides.
     *
     * Nobody's phone lost anything in the move: «πού είναι το κινητό» was the longest phrase the app
     * shipped before phase 13 at seven syllables, and it is still in reach at the default dot 2.
     * What changed is the other end — dots 3 to 5 now name the long sentences the module was moved
     * here for, where before dot 5 meant "nine syllables or more" and nothing in the app was that
     * long.
     *
     * A fresh DAO per dot, and never more than three phrases in it, because
     * [SingSayModule.NEW_PER_DAY] caps how many phrases he has never seen a sitting may introduce —
     * this test is about the ceiling, not about that cap.
     */
    @Test fun `each dot admits every phrase up to its own ceiling and nothing above it`() = runTest {
        val five = "καλημέρα σας"                        // 5 syllables
        val seven = "πού είναι το κινητό"                // 7
        val nine = "Πού μπορώ να βγάλω χρήματα;"         // 9
        val eleven = "Πού είναι η τράπεζα, παρακαλώ;"    // 11
        val twelve = "Πού είναι η στάση του λεωφορείου;" // 12

        assertEquals(setOf(five), pool(1, five, seven))
        assertEquals(setOf(five, seven), pool(2, five, seven, nine))
        assertEquals(setOf(five, nine), pool(3, five, nine, eleven))
        assertEquals(setOf(nine, eleven), pool(4, nine, eleven, twelve))
        assertEquals(setOf(eleven, twelve), pool(5, eleven, twelve))
    }

    /** The brief's own sentence — twenty syllables of everyday Greek — is dot 5's and nobody else's. */
    @Test fun `the longest sentence in the seed belongs to the hardest dot`() = runTest {
        val short = "θέλω καφέ"
        val long = "Θα ήθελα να κλείσω ένα ραντεβού για αύριο το πρωί"
        assertEquals(setOf(short), pool(4, short, long))
        assertEquals(setOf(short, long), pool(5, short, long))
    }

    /** What [difficulty] plans out of [texts], over DAOs of its own. */
    private suspend fun pool(difficulty: Int, vararg texts: String): Set<String> {
        val dao = FakeItemDao()
        texts.forEachIndexed { i, text ->
            dao.upsert(Item(text = text, kind = ItemKind.PHRASE, source = Source.SEED, createdAt = i.toLong()))
        }
        return SingSayModule.plan(dao, FakeScheduleDao(), SingSayModule.MAX_PER_PRACTICE, difficulty = difficulty)
            .map { it.text }
            .toSet()
    }

    /**
     * The dot is a ceiling, not a window: the easiest phrases stay in the pool at every dot above
     * them.
     *
     * The first cut of this dropped them, and it was a quiet disaster — at the default dot «Ναι» and
     * «Όχι» were no longer offered, their Leitner rows went overdue and stayed overdue for ever, and
     * no dot setting brought them back. Short phrases are also what the sandwich opens and closes a
     * sitting with.
     */
    @Test fun `dot two keeps the easiest phrases as well as its own`() = runTest {
        phrase("ναι", createdAt = 1)                              // 1 syllable — below the band
        phrase("πού είναι το κινητό", createdAt = 2)              // 7 — the band's own ceiling
        phrase("Πού μπορώ να βγάλω χρήματα;", createdAt = 3)      // 9 — above it
        val plan = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 2)
        assertEquals(setOf("ναι", "πού είναι το κινητό"), plan.map { it.text }.toSet())
    }

    /**
     * A ceiling nothing is under must not leave him a module with nothing in it: a device whose whole
     * vocabulary is long phrases still answers the easiest dot with something, because a man who taps
     * a dot and is told «δεν υπάρχει υλικό» has been punished for asking.
     */
    @Test fun `a ceiling no phrase is under widens back to the whole vocabulary`() = runTest {
        phrase("θέλω να πάω στο σπίτι μου", createdAt = 1)     // 9 syllables
        val easy = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_PRACTICE, difficulty = 1)
        assertEquals(listOf("θέλω να πάω στο σπίτι μου"), easy.map { it.text })
    }

    /** Nobody's phone changes on upgrade: the default dot plans what it always planned. */
    @Test fun `the default difficulty plans what the module always planned`() = runTest {
        repeat(12) { phrase("φράση $it", createdAt = it.toLong()) }
        val before = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_SESSION)
        val withDots = SingSayModule.plan(items, schedules, SingSayModule.MAX_PER_SESSION, difficulty = Difficulty.DEFAULT)
        assertEquals(before.map { it.id }.toSet(), withDots.map { it.id }.toSet())
    }
}
