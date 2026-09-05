package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount
import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesTest {
    private val a = Item(text = "α"); private val b = Item(text = "β"); private val c = Item(text = "γ"); private val d = Item(text = "δ")
    private val all = listOf(a, b, c, d)

    @Test fun `pinned first, then by usage, then nothing else`() {
        val ranked = Favourites.rank(all, pinnedIds = setOf(c.id), usage = listOf(ItemCount(b.id, 5), ItemCount(a.id, 2), ItemCount(c.id, 9)))
        assertEquals(listOf(c, b, a), ranked)
    }

    @Test fun `limit applies after pinned`() {
        val ranked = Favourites.rank(all, pinnedIds = setOf(d.id), usage = listOf(ItemCount(a.id, 3), ItemCount(b.id, 2), ItemCount(c.id, 1)), limit = 2)
        assertEquals(listOf(d, a), ranked)
    }

    @Test fun `usage of unknown or deleted ids is ignored`() {
        assertEquals(listOf(a), Favourites.rank(all, emptySet(), listOf(ItemCount("ghost", 99), ItemCount(a.id, 1))))
    }

    /** Day one: nothing pinned, nothing tapped. The tab must not open empty. */
    @Test fun `with no pins and no usage it falls back to the start of the vocabulary`() {
        val quick = Item(text = "Ναι", category = Category.QUICK)
        val food = Item(text = "νερό", category = Category.FOOD)
        val alsoQuick = Item(text = "Βοήθεια", category = Category.QUICK)
        val ranked = Favourites.rank(listOf(food, quick, alsoQuick), pinnedIds = emptySet(), usage = emptyList(), limit = 2)
        assertEquals(listOf(alsoQuick, quick), ranked)
    }

    @Test fun `usage that names only unknown ids still falls back`() {
        assertEquals(all, Favourites.rank(all, emptySet(), listOf(ItemCount("ghost", 99))))
    }
}
