package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.difficulty.Difficulty
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What «Λέξεις» asks him for: the tier the dots admit, and never a sentence that belongs to the
 * singing tile.
 *
 * The second cut is phase 13's. The twenty long sentences «Τραγούδα και πες το» was moved to are
 * phrases of tier 3 to 5, so the dots alone would have handed «Θα ήθελα να κλείσω ένα ραντεβού για
 * αύριο το πρωί» to a man whose help on this screen is a first sound, a first syllable and the word
 * said aloud. Sung in breath groups it is an exercise; asked for cold it is a wall.
 */
class WordCoachModuleTest {
    private val items = FakeItemDao()
    private val schedules = FakeScheduleDao()

    private suspend fun word(text: String, tier: Int = 1, category: Category = Category.THINGS, kind: ItemKind = ItemKind.WORD) {
        items.upsert(Item(text = text, kind = kind, category = category, source = Source.SEED, tier = tier, createdAt = 1))
    }

    private suspend fun plan(difficulty: Int) =
        WordCoachModule.plan(items, schedules, difficulty = difficulty).map { it.text }.toSet()

    @Test fun `the hardest dot never plans a phrase that belongs to the singing tile`() = runTest {
        word("νερό")
        word("ελευθερία", tier = 5, category = Category.FEELINGS)
        word("Πού είναι η τράπεζα, παρακαλώ;", tier = 4, category = Category.SINGING, kind = ItemKind.PHRASE)
        assertEquals(setOf("νερό", "ελευθερία"), plan(5))
    }

    /** And at no dot at all, easy or hard — the category is a cut of its own, not a tier. */
    @Test fun `no dot reaches the singing sentences`() = runTest {
        val sung = Item(
            text = "Πάμε στη φυσιοθεραπεία", kind = ItemKind.PHRASE, category = Category.SINGING,
            source = Source.SEED, tier = 4, createdAt = 1,
        )
        for (dot in Difficulty.MIN..Difficulty.MAX) {
            assertFalse("dot $dot asks for a sung sentence", WordCoachModule.asks(sung, dot))
        }
        // The same sentence filed anywhere else is ordinary content, admitted by its tier as always.
        for (dot in 4..5) assertTrue(WordCoachModule.asks(sung.copy(category = Category.PLACES), dot))
    }

    /** The tier cut is untouched: dot n takes tier n and below, and nothing above it. */
    @Test fun `the dots still take every tier up to their own`() = runTest {
        word("νερό", tier = 1)
        word("λογαριασμός", tier = 3)
        word("ελευθερία", tier = 5)
        assertEquals(setOf("νερό"), plan(1))
        assertEquals(setOf("νερό", "λογαριασμός"), plan(3))
        assertEquals(setOf("νερό", "λογαριασμός", "ελευθερία"), plan(5))
    }
}
