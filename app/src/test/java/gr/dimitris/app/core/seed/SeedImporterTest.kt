package gr.dimitris.app.core.seed

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the import that decides *what* is added. A version bump has to reach an install that
 * has been in use for months: the 175 items already on it must not be duplicated, re-imported or
 * reset, and the new phrases must arrive.
 */
class SeedImporterTest {
    private fun entry(text: String) = SeedEntry(text = text, kind = "PHRASE", category = "QUICK", image = null, arasaacId = null)

    private val v1 = listOf("Ναι", "Όχι", "καφές")
    private val v2 = v1 + listOf("θέλω καφέ", "πάμε σπίτι")

    @Test fun `a version bump adds only the new ids`() {
        val manifest = SeedManifest(version = 2, items = v2.map(::entry))
        val added = SeedImporter.newEntries(manifest, existingTexts = v1.toSet())
        assertEquals(listOf("θέλω καφέ", "πάμε σπίτι"), added.map { it.text })
    }

    @Test fun `re-running the same version adds nothing`() {
        val manifest = SeedManifest(version = 2, items = v2.map(::entry))
        assertTrue(SeedImporter.newEntries(manifest, existingTexts = v2.toSet()).isEmpty())
    }

    /** Whitespace around a phrase is not a different phrase: the device stores it trimmed. */
    @Test fun `text is matched trimmed, so nothing arrives twice`() {
        val manifest = SeedManifest(version = 2, items = listOf(entry("  θέλω καφέ  "), entry("πάμε σπίτι")))
        val added = SeedImporter.newEntries(manifest, existingTexts = setOf("θέλω καφέ"))
        assertEquals(listOf("πάμε σπίτι"), added.map { it.text })
    }

    /** A duplicate inside the manifest itself is a typo in words.json, not two items for him. */
    @Test fun `the same phrase twice in one manifest is imported once`() {
        val manifest = SeedManifest(version = 2, items = listOf(entry("θέλω καφέ"), entry("θέλω καφέ")))
        assertEquals(1, SeedImporter.newEntries(manifest, existingTexts = emptySet()).size)
    }

    /**
     * The phase 4 defect: the "already here" set was built from the seeded rows alone, so a phrase
     * the caregiver had typed herself, or a seed text she had edited, looked missing and the bump
     * added it a second time. Accents and case are hers to get wrong, not his to pay for.
     */
    @Test fun `version bump adds only the texts that do not exist, whatever their source`() {
        val manifest = SeedManifest(version = 2, items = v2.map(::entry))
        // «καφες» she typed without its tonos; «ναι» is the seed row she edited to lower case.
        val onDevice = setOf("ναι", "Όχι", "καφες")
        val added = SeedImporter.newEntries(manifest, existingTexts = onDevice)
        assertEquals(listOf("θέλω καφέ", "πάμε σπίτι"), added.map { it.text })
    }

    @Test fun `a fresh install gets everything`() {
        val manifest = SeedManifest(version = 2, items = v2.map(::entry))
        assertEquals(v2, SeedImporter.newEntries(manifest, existingTexts = emptySet()).map { it.text })
    }

    /**
     * A word the caregiver deleted is a decision she made, not a gap to fill. Matching only the live
     * rows handed it straight back on the next version bump, and would go on doing so for ever.
     */
    @Test fun `a deleted word still counts as being on the device`() {
        val manifest = SeedManifest(version = 2, items = v2.map(::entry))
        val rows = listOf(
            Item(text = "Ναι", kind = ItemKind.PHRASE, source = Source.SEED),
            Item(text = "Όχι", kind = ItemKind.PHRASE, source = Source.SEED, deleted = true),
            Item(text = "καφές", kind = ItemKind.WORD, source = Source.SEED),
        )
        val added = SeedImporter.newEntries(manifest, SeedImporter.onDevice(rows))
        assertEquals(listOf("θέλω καφέ", "πάμε σπίτι"), added.map { it.text })
    }

    /**
     * A dialogue turn is an ordinary item too, and a caregiver who writes «Πάμε» as a turn would
     * otherwise block the talk-board card of the same word from ever arriving — silently.
     */
    @Test fun `a dialogue turn does not block the card of the same words`() {
        val manifest = SeedManifest(version = 2, items = listOf(entry("Πάμε"), entry("Ναι")))
        val rows = listOf(
            Item(text = "Πάμε", kind = ItemKind.SCRIPT_LINE, source = Source.CAREGIVER),
            Item(text = "Ναι", kind = ItemKind.PHRASE, source = Source.SEED),
        )
        assertEquals(listOf("Πάμε"), SeedImporter.newEntries(manifest, SeedImporter.onDevice(rows)).map { it.text })
    }
}
