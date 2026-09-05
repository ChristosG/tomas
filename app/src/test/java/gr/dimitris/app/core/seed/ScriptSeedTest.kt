package gr.dimitris.app.core.seed

import gr.dimitris.app.core.data.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSeedTest {
    private fun script(title: String) = SeedScript(title, listOf(SeedLine("OTHER", "Γεια"), SeedLine("DIMITRIS", "Γεια σου")))

    @Test fun `parses scripts and lines`() {
        val m = ScriptSeedManifest.parse("""{"version":2,"scripts":[{"title":"Α","lines":[{"speaker":"OTHER","text":"Γεια"},{"speaker":"DIMITRIS","text":"Γεια σου"}]}]}""")
        assertEquals(2, m.version)
        assertEquals("Α", m.scripts.single().title)
        assertEquals(listOf("OTHER", "DIMITRIS"), m.scripts.single().lines.map { it.speaker })
    }

    @Test fun `the bundled dialogues parse and are all his and someone else`() {
        val json = asset("seed/scripts.json").readText()
        val m = ScriptSeedManifest.parse(json)
        assertEquals(2, m.version)
        assertEquals(6, m.scripts.size)
        assertEquals(6, m.scripts.map { it.title }.distinct().size)
        for (s in m.scripts) {
            assertTrue("${s.title} should have lines", s.lines.size >= 4)
            assertTrue("${s.title} needs a turn for Dimitris", s.lines.any { speakerOf(it) == Speaker.DIMITRIS })
            assertTrue("${s.title} needs a turn for the other person", s.lines.any { speakerOf(it) == Speaker.OTHER })
            assertTrue("${s.title} should have no blank line", s.lines.none { it.text.isBlank() })
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

    @Test fun `the same title twice in one manifest is imported once`() {
        val m = ScriptSeedManifest(version = 1, scripts = listOf(script("Στο ταξί"), script("Στο ταξί")))
        assertEquals(1, ScriptSeedImporter.newScripts(m, emptySet()).size)
    }
}
