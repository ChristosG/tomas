package gr.dimitris.app.core.seed

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the import that decides *what* is added. A version bump has to reach an install that
 * has been in use for months: the 175 items already on it must not be duplicated, re-imported or
 * reset, and the new phrases must arrive.
 */
class SeedImporterTest {
    private fun entry(text: String) = SeedEntry(text = text, kind = "PHRASE", category = "QUICK", image = null, arasaacId = null)

    /** A bundled word with a grading on it, which is what every entry carries since phase 13. */
    private fun graded(text: String, tier: Int, gender: String? = null) =
        SeedEntry(text = text, kind = "WORD", category = "FOOD", image = null, arasaacId = null, tier = tier, gender = gender)

    /** The row that import would have written for [entry] at [version] — the same row on every phone. */
    private fun seeded(entry: SeedEntry, version: Int = 2) = SeedImporter.row(entry, version, imagePath = null)

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

    // ------------------------------------------------ the grading, and whose work it may not touch

    /**
     * What a bundled word arrives with: its tier, its gender, and an id and a stamp that come from
     * the manifest rather than from this phone.
     */
    @Test fun `a bundled word carries its tier and its gender`() {
        val row = seeded(graded("ελπίδα", tier = 5, gender = "F"), version = 3)
        assertEquals(5, row.tier)
        assertEquals("F", row.gender)
        assertEquals(SeedIds.item("ελπίδα"), row.id)
        assertEquals(SeedIds.stamp(3), row.updatedAt)
    }

    /** A manifest that says nothing is the easiest tier and an ungraded noun, never a zero. */
    @Test fun `a word with no grading in the manifest is tier one and no gender`() {
        val row = seeded(SeedEntry(text = "νερό", kind = "WORD", category = "FOOD", image = null, arasaacId = null, tier = 0))
        assertEquals(1, row.tier)
        assertNull(row.gender)
    }

    /** A typo in words.json is "nobody has said", not a value in the database no reader understands. */
    @Test fun `a gender this app cannot read is stored as no gender at all`() {
        assertNull(seeded(graded("καφές", tier = 1, gender = "Θ")).gender)
        assertEquals("M", seeded(graded("καφές", tier = 1, gender = " m ")).gender)
    }

    /**
     * The re-grade a version bump owes a phone that has had the vocabulary since day one: the two
     * hundred words it already holds gain the tier and the gender phase 13 gave them, and their
     * stamp moves to the new manifest's so the other phones take the change too.
     */
    @Test fun `a bump re-grades the bundled words the device already has`() {
        val v3 = SeedManifest(version = 3, items = listOf(graded("καφές", tier = 1, gender = "M"), graded("ελπίδα", tier = 5, gender = "F")))
        val onDevice = v3.items.map { seeded(it.copy(tier = 1, gender = null), version = 2) }

        val rows = SeedImporter.regraded(v3, onDevice, SeedIds.stamp(3))

        assertEquals(listOf("ελπίδα" to 5, "καφές" to 1), rows.sortedBy { it.text }.map { it.text to it.tier })
        assertEquals(setOf("F", "M"), rows.mapTo(mutableSetOf()) { it.gender })
        assertTrue("the re-grade travels as a newer row", rows.all { it.updatedAt == SeedIds.stamp(3) })
    }

    /** A re-import of the same manifest writes nothing, so no `updatedAt` moves for nobody's change. */
    @Test fun `a word that already says what the manifest says is left alone`() {
        val v3 = SeedManifest(version = 3, items = listOf(graded("ελπίδα", tier = 5, gender = "F")))
        val onDevice = v3.items.map { seeded(it, version = 3) }
        assertTrue(SeedImporter.regraded(v3, onDevice, SeedIds.stamp(3)).isEmpty())
    }

    /**
     * The three ways a row is hers, and none of them is ever re-graded: she edited it (a real clock
     * is eleven digits past any stamp), she re-typed the word, or she deleted it. The last one is the
     * one with teeth — a deletion rewritten here would be pushed at the other phones on the next
     * sync for a change nobody asked for.
     */
    @Test fun `the re-grade never touches a word the caregiver has had a hand in`() {
        val v3 = SeedManifest(version = 3, items = listOf(graded("καφές", tier = 1, gender = "M")))
        val stamp = SeedIds.stamp(3)
        val seed = seeded(v3.items.single().copy(tier = 1, gender = null), version = 2)

        assertTrue("she edited it", SeedImporter.regraded(v3, listOf(seed.copy(updatedAt = 1_757_000_000_000)), stamp).isEmpty())
        assertTrue("she re-typed it", SeedImporter.regraded(v3, listOf(seed.copy(text = "καφες")), stamp).isEmpty())
        assertTrue("she deleted it", SeedImporter.regraded(v3, listOf(seed.copy(deleted = true)), stamp).isEmpty())
        // And a word of her own that happens to say the same thing is not our row at all.
        assertTrue(
            "hers, with an id of her own",
            SeedImporter.regraded(v3, listOf(Item(id = "hers", text = "καφές", source = Source.CAREGIVER, updatedAt = 1)), stamp).isEmpty(),
        )
        // The bundled row itself, untouched, is re-graded — so the three refusals above are the
        // conditions talking and not an empty list for some other reason.
        assertEquals(listOf("M"), SeedImporter.regraded(v3, listOf(seed), stamp).map { it.gender })
    }

    /**
     * v5's own re-grade: the twenty long sentences moved to [Category.SINGING], and a phone that had
     * already imported v4 has them filed under «Μέρη» and «Χρόνος».
     *
     * The shelf is not decoration — «Λέξεις» and «Μίλα» both exclude that category by name — so
     * without the category travelling with the tier and the gender, those phones would have gone on
     * offering «Μπορείτε να μου πείτε πού είναι το φαρμακείο;» as a talk-board card and a word-coach
     * target for ever, and the fix would have reached nobody who already had the app.
     */
    @Test fun `a version bump moves a bundled word to the category the manifest now gives it`() {
        val sung = SeedEntry(
            text = "Πάμε στη φυσιοθεραπεία", kind = "PHRASE", category = "SINGING",
            image = null, arasaacId = null, tier = 4,
        )
        val v5 = SeedManifest(version = 5, items = listOf(sung))
        val onDevice = listOf(seeded(sung.copy(category = "BODY"), version = 4))

        val rows = SeedImporter.regraded(v5, onDevice, SeedIds.stamp(5))
        assertEquals(listOf(Category.SINGING), rows.map { it.category })
        assertTrue("the re-grade travels as a newer row", rows.all { it.updatedAt == SeedIds.stamp(5) })
    }

    /** And a row a caregiver has filed somewhere herself keeps her shelf, as it keeps her tier. */
    @Test fun `the category re-grade never touches a row the caregiver has had a hand in`() {
        val sung = SeedEntry(
            text = "Πάμε στη φυσιοθεραπεία", kind = "PHRASE", category = "SINGING",
            image = null, arasaacId = null, tier = 4,
        )
        val v5 = SeedManifest(version = 5, items = listOf(sung))
        val hers = seeded(sung.copy(category = "BODY"), version = 4).copy(updatedAt = 1_757_000_000_000)
        assertTrue(SeedImporter.regraded(v5, listOf(hers), SeedIds.stamp(5)).isEmpty())
    }

    /** A word the manifest has twice is re-graded once, like everything else the importer reads. */
    @Test fun `a duplicate in the manifest re-grades once`() {
        val v3 = SeedManifest(version = 3, items = listOf(graded("ελπίδα", tier = 5, gender = "F"), graded("ελπίδα", tier = 5, gender = "F")))
        val onDevice = listOf(seeded(graded("ελπίδα", tier = 1), version = 2))
        assertEquals(1, SeedImporter.regraded(v3, onDevice, SeedIds.stamp(3)).size)
    }
}
