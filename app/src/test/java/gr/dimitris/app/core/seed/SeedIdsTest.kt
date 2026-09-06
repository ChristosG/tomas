package gr.dimitris.app.core.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * The ids the bundled vocabulary and dialogues are born with.
 *
 * Chris's phone, the father's phone and Dimitris' phone all install the same APK and all import the
 * same seed. With a fresh `UUID` each, «ψωμί» was three different rows and the first sync — doing
 * exactly what it was told — put all three on all three phones. These ids are what make the three
 * imports write *one* row.
 */
class SeedIdsTest {

    @Test fun `the same word gives the same id, every time and on every phone`() {
        assertEquals(SeedIds.item("ψωμί"), SeedIds.item("ψωμί"))
        assertEquals(SeedIds.script("Στην καφετέρια"), SeedIds.script("Στην καφετέρια"))
        assertEquals(SeedIds.line("Στην καφετέρια", 2), SeedIds.line("Στην καφετέρια", 2))
        assertEquals(SeedIds.lineItem("Στην καφετέρια", 2), SeedIds.lineItem("Στην καφετέρια", 2))
    }

    @Test fun `different words, dialogues and turns get different ids`() {
        assertNotEquals(SeedIds.item("ψωμί"), SeedIds.item("νερό"))
        assertNotEquals(SeedIds.script("Στην καφετέρια"), SeedIds.script("Με έναν φίλο"))
        assertNotEquals(SeedIds.line("Στην καφετέρια", 1), SeedIds.line("Στην καφετέρια", 2))
        assertNotEquals(SeedIds.line("Στην καφετέρια", 1), SeedIds.line("Με έναν φίλο", 1))
    }

    /**
     * A dialogue turn worded «Ναι» and the talk-board card «Ναι» are two different rows on purpose —
     * the vocabulary importer excludes script lines from its "already here" set for exactly that
     * reason. One id for both would fuse them the first time either phone synced.
     */
    @Test fun `a turn and a word that read the same are still two rows`() {
        assertNotEquals(SeedIds.item("Ναι"), SeedIds.lineItem("Στην καφετέρια", 0))
    }

    /** The same key the importers dedup by, so «ΝΑΙ» and «Ναι» are one word here too. */
    @Test fun `accents and case do not make a second row`() {
        assertEquals(SeedIds.item("Ναι"), SeedIds.item("ΝΑΙ"))
        assertEquals(SeedIds.item("θέλω καφέ"), SeedIds.item("θελω καφε"))
    }

    @Test fun `the ids are real UUIDs, which is what the database column expects`() {
        val id = SeedIds.item("ψωμί")
        assertEquals(id, UUID.fromString(id).toString())
    }
}
