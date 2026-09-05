package gr.dimitris.app.core.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeedManifestTest {
    @Test fun `parses version and entries including a missing image`() {
        val json = """{"version":3,"items":[
            {"text":"καφές","kind":"WORD","category":"FOOD","image":"2296.png","arasaacId":2296},
            {"text":"Δεν ξέρω","kind":"PHRASE","category":"QUICK","image":null,"arasaacId":null}]}"""
        val m = SeedManifest.parse(json)
        assertEquals(3, m.version)
        assertEquals(2, m.items.size)
        assertEquals("2296.png", m.items[0].image)
        assertNull(m.items[1].image)
        assertEquals("PHRASE", m.items[1].kind)
    }
}
