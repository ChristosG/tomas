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
    @Test fun `with no pins and no usage it falls back to the vocabulary by category then word`() {
        val water = Item(text = "νερό", category = Category.FOOD)
        val bread = Item(text = "ψωμί", category = Category.FOOD)
        val home = Item(text = "σπίτι", category = Category.PLACES)
        val ranked = Favourites.rank(listOf(home, bread, water), pinnedIds = emptySet(), usage = emptyList())
        assertEquals(listOf(water, bread, home), ranked)
    }

    /** The quick row above the tabs already shows every quick phrase; the fallback must not repeat them. */
    @Test fun `the fallback leaves out the quick phrases`() {
        val quick = Item(text = "Ναι", category = Category.QUICK)
        val alsoQuick = Item(text = "Βοήθεια", category = Category.QUICK)
        val water = Item(text = "νερό", category = Category.FOOD)
        assertEquals(listOf(water), Favourites.rank(listOf(quick, water, alsoQuick), emptySet(), emptyList()))
    }

    /** Nothing but quick phrases means there is genuinely nothing to fall back to. */
    @Test fun `a quick-only vocabulary falls back to nothing`() {
        val quick = Item(text = "Ναι", category = Category.QUICK)
        assertEquals(emptyList<Item>(), Favourites.rank(listOf(quick), emptySet(), emptyList()))
    }

    @Test fun `usage that names only unknown ids still falls back`() {
        assertEquals(all, Favourites.rank(all, emptySet(), listOf(ItemCount("ghost", 99))))
    }
}
