package gr.dimitris.app.core.seed

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.difficulty.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSeedTest {
    private fun script(title: String) = SeedScript(title, listOf(SeedLine("OTHER", "Γεια"), SeedLine("DIMITRIS", "Γεια σου")))

    @Test fun `parses scripts and lines`() {
        val m = ScriptSeedManifest.parse("""{"version":3,"scripts":[{"title":"Α","tier":4,"lines":[{"speaker":"OTHER","text":"Γεια"},{"speaker":"DIMITRIS","text":"Γεια σου","intent":"χαιρετάει"}]}]}""")
        assertEquals(3, m.version)
        assertEquals("Α", m.scripts.single().title)
        assertEquals(4, m.scripts.single().tier)
        assertEquals(listOf("OTHER", "DIMITRIS"), m.scripts.single().lines.map { it.speaker })
        assertEquals(listOf(null, "χαιρετάει"), m.scripts.single().lines.map { it.intent })
    }

    /**
     * Gson allocates the object and fills its fields, so a default declared in Kotlin is not what an
     * absent key gives back: a manifest with no tier reads as zero, which
     * [gr.dimitris.app.core.difficulty.Difficulty.clamp] takes to the easiest tier on the way in.
     */
    @Test fun `a dialogue with no tier at all is the easiest one`() {
        val m = ScriptSeedManifest.parse("""{"version":3,"scripts":[{"title":"Α","lines":[{"speaker":"DIMITRIS","text":"Γεια"}]}]}""")
        assertEquals(0, m.scripts.single().tier)
        assertEquals(1, Difficulty.clamp(m.scripts.single().tier))
    }

    @Test fun `the bundled dialogues parse and are all his and someone else`() {
        val json = asset("seed/scripts.json").readText()
        val m = ScriptSeedManifest.parse(json)
        assertEquals(3, m.version)
        assertEquals(14, m.scripts.size)
        assertEquals(14, m.scripts.map { it.title }.distinct().size)
        for (s in m.scripts) {
            assertTrue("${s.title} should have lines", s.lines.size >= 4)
            assertTrue("${s.title} needs a turn for Dimitris", s.lines.any { speakerOf(it) == Speaker.DIMITRIS })
            assertTrue("${s.title} needs a turn for the other person", s.lines.any { speakerOf(it) == Speaker.OTHER })
            assertTrue("${s.title} should have no blank line", s.lines.none { it.text.isBlank() })
            // One sitting's worth, the same cap the caregiver's editor holds her to.
            assertTrue("${s.title} is longer than one sitting", s.lines.size <= 12)
        }
    }

    /**
     * The eight harder dialogues are the answer to Dimitris' own report, relayed by Chris: the app
     * is too easy. The six it shipped with stay where they were — tiers 1 and 2, so an upgraded
     * phone at the default dot 2 sees exactly what it saw yesterday — and everything new sits above
     * them, up to the two at tier 5.
     */
    @Test fun `the bundled dialogues are graded, and the harder ones are new`() {
        val m = ScriptSeedManifest.parse(asset("seed/scripts.json").readText())
        val byTitle = m.scripts.associate { it.title to it.tier }
        for (s in m.scripts) {
            assertTrue("${s.title} has a tier outside 1..5: ${s.tier}", s.tier in Difficulty.MIN..Difficulty.MAX)
        }
        val shipped = listOf("Στην καφετέρια", "Στον φούρνο", "Με έναν φίλο", "Τηλέφωνο στον πατέρα", "Στη φυσικοθεραπεία", "Στο ταξί")
        for (title in shipped) {
            assertTrue("$title should still be here", title in byTitle)
            assertTrue("$title must stay in reach at the default dot", byTitle.getValue(title) <= Difficulty.DEFAULT)
        }
        val added = m.scripts.filter { it.title !in shipped }
        assertEquals("eight harder dialogues", 8, added.size)
        for (s in added) assertTrue("${s.title} is a hard dialogue: ${s.tier}", s.tier >= 3)
        assertEquals("the hardest work in the app is tier 5", 5, m.scripts.maxOf { it.tier })
    }

    /**
     * Every turn of his says what a good answer has to convey. It is what the caregiver reads in the
     * editor, and what the attempt row carries: his line is only ever an example, because an open
     * question has more than one right answer.
     */
    @Test fun `every turn of his says what a good answer has to convey`() {
        val m = ScriptSeedManifest.parse(asset("seed/scripts.json").readText())
        for (s in m.scripts) {
            for (line in s.lines) {
                if (speakerOf(line) == Speaker.DIMITRIS) {
                    assertTrue("«${line.text}» in ${s.title} has no intent", !line.intent.isNullOrBlank())
                } else {
                    assertTrue("the other person's line is said, not judged", line.intent == null)
                }
            }
        }
    }

    /**
     * The version has to move with the file or a device already in use never sees a dialogue added
     * later: [ScriptSeedImporter.importIfNeeded] returns early while the stored version is not
     * behind the manifest's, and the dedup by title is what keeps the bump from duplicating the five
     * that are already there.
     */
    @Test fun `the taxi dialogue is what the version bump owes a device already in use`() {
        val m = ScriptSeedManifest.parse(asset("seed/scripts.json").readText())
        val onDevice = m.scripts.map { it.title }.filter { it != "Στο ταξί" }.toSet()
        assertEquals(listOf("Στο ταξί"), ScriptSeedImporter.newScripts(m, onDevice).map { it.title })
    }

    private fun speakerOf(line: SeedLine) = ScriptSeedImporter.speakerOf(line.speaker)

    /** Assets are not on the unit-test classpath, so the file is found by walking up from wherever Gradle started us. */
    private fun asset(name: String): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, "src/main/assets/$name"), java.io.File(dir, "app/src/main/assets/$name"))) {
                if (candidate.exists()) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("δεν βρέθηκε το asset $name")
    }

    /** A tag nobody recognises still leaves a line on the screen, said by the other person. */
    @Test fun `an unknown speaker tag falls back to the other person`() {
        assertEquals(Speaker.DIMITRIS, ScriptSeedImporter.speakerOf("dimitris"))
        assertEquals(Speaker.OTHER, ScriptSeedImporter.speakerOf("WAITER"))
        assertEquals(Speaker.OTHER, ScriptSeedImporter.speakerOf(""))
    }

    @Test fun `a fresh install gets every dialogue`() {
        val m = ScriptSeedManifest(version = 1, scripts = listOf(script("Στην καφετέρια"), script("Στον φούρνο")))
        assertEquals(listOf("Στην καφετέρια", "Στον φούρνο"), ScriptSeedImporter.newScripts(m, emptySet()).map { it.title })
    }

    /** Same rule as the vocabulary seed: her script keeps its place, whatever she called it. */
    @Test fun `a title already on the device is not added again, whatever its source`() {
        val m = ScriptSeedManifest(version = 2, scripts = listOf(script("Στην καφετέρια"), script("Στον φούρνο")))
        val added = ScriptSeedImporter.newScripts(m, existingTitles = setOf("στην καφετερια"))
        assertEquals(listOf("Στον φούρνο"), added.map { it.title })
    }

    /**
     * The dialogue she deleted stays deleted. This is the defect the task-3 upgrade check found:
     * «Με έναν φίλο», removed by hand, came back beside «Στο ταξί» on the version bump — and would
     * come back again on every bump after that.
     */
    @Test fun `a deleted dialogue does not come back on a version bump`() {
        val m = ScriptSeedManifest(version = 2, scripts = listOf(script("Με έναν φίλο"), script("Στο ταξί")))
        val rows = listOf(
            Script(title = "Με έναν φίλο", source = Source.SEED, deleted = true),
            Script(title = "Στην καφετέρια", source = Source.SEED),
        )
        assertEquals(listOf("Στο ταξί"), ScriptSeedImporter.newScripts(m, ScriptSeedImporter.onDevice(rows)).map { it.title })
    }

    // --- what a bump owes a dialogue that is already here ------------------------------------------

    private val bumped = SeedScript(
        "Στην καφετέρια", tier = 3,
        lines = listOf(
            SeedLine("OTHER", "Καλημέρα! Τι θα πάρετε;"),
            SeedLine("DIMITRIS", "Έναν καφέ, παρακαλώ.", intent = "λέει τι θέλει να πιει"),
        ),
    )

    /** The id of his turn in [bumped] — the one the cases below make hers, one way at a time. */
    private val hisLine = SeedIds.line("Στην καφετέρια", 1)

    /** The rows a first import wrote, as they sit on a phone that has been in use since. */
    private fun seeded(
        text: String = "Έναν καφέ, παρακαλώ.",
        tier: Int = 1,
        intent: String? = null,
        at: Long = SeedIds.stamp(2),
        deleted: Boolean = false,
    ): List<Pair<ScriptLine, Item>> {
        val title = bumped.title
        return listOf(
            ScriptLine(id = SeedIds.line(title, 0), scriptId = SeedIds.script(title), position = 0,
                speaker = Speaker.OTHER, itemId = SeedIds.lineItem(title, 0), tier = tier,
                createdAt = at, updatedAt = at)
                to Item(id = SeedIds.lineItem(title, 0), text = "Καλημέρα! Τι θα πάρετε;", kind = ItemKind.SCRIPT_LINE),
            ScriptLine(id = SeedIds.line(title, 1), scriptId = SeedIds.script(title), position = 1,
                speaker = Speaker.DIMITRIS, itemId = SeedIds.lineItem(title, 1), tier = tier, intent = intent,
                createdAt = at, updatedAt = at, deleted = deleted)
                to Item(id = SeedIds.lineItem(title, 1), text = text, kind = ItemKind.SCRIPT_LINE),
        )
    }

    /**
     * Phase 12 gave every bundled dialogue a tier and every turn of his an intent. A device that
     * already had «Στην καφετέρια» is not sent the dialogue again — its words may be hers now — but
     * it is owed the grading, or the six that shipped before this phase stay tier 1 with nothing
     * said about what their turns are after, on every phone in use, for ever.
     */
    @Test fun `a bump re-grades the seeded lines it still recognises`() {
        val stamp = SeedIds.stamp(3)
        val rows = ScriptSeedImporter.regraded(bumped.title, bumped.tier, bumped.lines, seeded(), stamp)

        assertEquals(2, rows.size)
        assertEquals(listOf(3, 3), rows.map { it.tier })
        assertEquals(listOf(null, "λέει τι θέλει να πιει"), rows.map { it.intent })
        assertTrue("the stamp is the manifest's, never the clock", rows.all { it.updatedAt == stamp })
    }

    /** Re-importing what is already there writes nothing, so no row's `updatedAt` moves for nothing. */
    @Test fun `a re-import of what is already there writes nothing`() {
        val stamp = SeedIds.stamp(3)
        val settled = seeded(tier = 3, intent = "λέει τι θέλει να πιει", at = stamp)
        assertEquals(emptyList<ScriptLine>(), ScriptSeedImporter.regraded(bumped.title, bumped.tier, bumped.lines, settled, stamp))
    }

    /**
     * And the ways a line stops being ours. Each of them is a caregiver's work, and none of them may
     * be touched by a version bump — the rest of the dialogue is re-graded around it.
     */
    @Test fun `a bump never touches a line she has made her own`() {
        val stamp = SeedIds.stamp(3)
        fun touched(rows: List<Pair<ScriptLine, Item>>) =
            ScriptSeedImporter.regraded(bumped.title, bumped.tier, bumped.lines, rows, stamp).map { it.id }

        assertFalse("she reworded the turn", hisLine in touched(seeded(text = "Έναν καφέ χωρίς ζάχαρη.")))
        assertFalse("she edited it, so its stamp is a real clock", hisLine in touched(seeded(at = 1_757_000_000_000L)))
        assertFalse("she deleted the turn", hisLine in touched(seeded(deleted = true)))
        // A dialogue she wrote herself and happened to give the same title: different row ids, so
        // this never sees it at all.
        val hers = seeded().map { (row, item) -> row.copy(id = "hers-${row.position}") to item }
        assertEquals(emptyList<String>(), touched(hers))
    }

    @Test fun `the same title twice in one manifest is imported once`() {
        val m = ScriptSeedManifest(version = 1, scripts = listOf(script("Στο ταξί"), script("Στο ταξί")))
        assertEquals(1, ScriptSeedImporter.newScripts(m, emptySet()).size)
    }
}
