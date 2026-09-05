package gr.dimitris.app.core.seed

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
}
