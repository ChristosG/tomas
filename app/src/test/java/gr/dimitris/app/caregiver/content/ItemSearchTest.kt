package gr.dimitris.app.caregiver.content

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemSearchTest {
    private val items = listOf(
        Item(text = "Καφές", category = Category.FOOD),
        Item(text = "καφετέρια", category = Category.PLACES),
        Item(text = "νερό", category = Category.FOOD),
    )

    @Test fun `blank query returns everything`() = assertEquals(items, ItemSearch.filter(items, "  "))
    @Test fun `matches ignore case and accents`() =
        assertEquals(listOf("Καφές", "καφετέρια"), ItemSearch.filter(items, "ΚΑΦΕ").map { it.text })
    @Test fun `accented query still matches`() = assertEquals(listOf("νερό"), ItemSearch.filter(items, "νέρ").map { it.text })
}
