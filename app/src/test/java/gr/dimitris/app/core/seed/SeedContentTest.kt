package gr.dimitris.app.core.seed

import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.greek.Gender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled vocabulary itself, read as the phone reads it.
 *
 * Everything here was found by hand once and must not have to be found by hand again: a word graded
 * outside the row of dots, a gender code nothing understands, two words handed the same drawing, a
 * manifest pointing at a picture that is not in the APK, or a picture in the APK that no word points
 * at any more.
 *
 * The pictogram rule is the one with teeth. The word coach's first rung shows the picture and
 * nothing else — no sound, no syllable, the word withheld — so two words with one drawing means he
 * is shown the same image and scored on different targets on different days, which is an error he
 * cannot avoid and which teaches nothing. `tools/seed/fetch-arasaac.mjs` takes the first English
 * result when a term has no exact match, and that is exactly how it happens: «ελπίζω» asked for
 * "hope" and got «ελπίδα»'s drawing. Such a word ships text-led instead (`noPicture` in
 * `words.json`), and [gr.dimitris.app.modules.wordcoach.CueLadder] leaves the picture rung out.
 */
class SeedContentTest {
    private val manifest: SeedManifest = SeedManifest.parse(assetFile("seed/seed.json").readText())

    @Test fun `every bundled word is graded inside the row of dots`() {
        for (entry in manifest.items) {
            assertTrue("«${entry.text}» is tier ${entry.tier}", entry.tier in 1..5)
        }
    }

    /** A code nothing can read is a column nothing can use: the importer would store null anyway. */
    @Test fun `every stated gender is one of the three the app knows`() {
        for (entry in manifest.items) {
            val gender = entry.gender ?: continue
            assertEquals("«${entry.text}» carries «$gender»", gender, Gender.of(gender)?.code)
        }
    }

    /** A phrase, a number and a dialogue turn take no article, so none of them may claim a gender. */
    @Test fun `only single words carry a gender`() {
        for (entry in manifest.items) {
            if (entry.gender == null) continue
            assertEquals("«${entry.text}» is a ${entry.kind} with a gender", ItemKind.WORD.name, entry.kind)
        }
    }

    /** The importer dedups by this key, so two entries that share one are one word he never gets twice. */
    @Test fun `no two entries are the same word`() {
        val seen = mutableSetOf<String>()
        for (entry in manifest.items) {
            assertEquals("«${entry.text}» is not stored trimmed", entry.text.trim(), entry.text)
            assertTrue("«${entry.text}» twice", seen.add(SeedText.key(entry.text)))
        }
    }

    /**
     * No two single words share a drawing — see the class comment.
     *
     * A phrase and its head word are allowed to: «θέλω καφέ» and «καφές» were seeded that way in
     * phase 4, they are never in one sitting at the same rung (the word coach's dot 1 asks for words
     * alone), and re-picking either would change a picture he has learned. [SHARED] is the rest of
     * what phases 0–4 left, named so that it is a decision rather than a silence.
     */
    @Test fun `no two words are handed the same pictogram`() {
        val byImage = manifest.items
            .filter { it.kind == ItemKind.WORD.name && it.image != null }
            .groupBy { it.image!! }
        for ((image, entries) in byImage) {
            if (entries.size == 1) continue
            val texts = entries.map { it.text }.toSet()
            assertTrue("$image is shared by $texts", texts in SHARED)
        }
    }

    /** Every picture the manifest names is in the APK, and every picture in the APK is named. */
    @Test fun `the manifest and the assets folder agree`() {
        val dir = assetFile("seed/seed.json").parentFile!!
        val named = manifest.items.mapNotNullTo(mutableSetOf()) { it.image }
        val onDisk = dir.listFiles { f: File -> f.name.endsWith(".png") }.orEmpty().mapTo(mutableSetOf()) { it.name }
        assertEquals("pictures the manifest names that are not in the APK", emptySet<String>(), named - onDisk)
        assertEquals("pictures in the APK that no word points at", emptySet<String>(), onDisk - named)
    }

    private companion object {
        /**
         * The word pairs that shared a drawing before phase 13 and still do. Both are pre-existing
         * and both are near-synonyms in use — a man buying something and a man paying for it, a
         * friend and a friend — so the picture is not the thing that tells them apart in any case.
         */
        val SHARED: List<Set<String>> = listOf(setOf("αγοράζω", "πληρώνω"), setOf("φίλος", "φίλη"))

        /** Assets are not on the unit-test classpath: found by walking up from wherever Gradle started us. */
        fun assetFile(name: String): File {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                for (candidate in listOf(File(dir, "src/main/assets/$name"), File(dir, "app/src/main/assets/$name"))) {
                    if (candidate.exists()) return candidate
                }
                dir = dir.parentFile
            }
            throw AssertionError("δεν βρέθηκε το asset $name")
        }
    }
}
