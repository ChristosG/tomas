package gr.dimitris.app.modules.sentences

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.accusative
import gr.dimitris.app.core.seed.SeedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The word order he is practising, generated from whatever vocabulary the device happens to hold.
 * Every case is run many times over, because the templates choose at random and a rule that holds
 * once has not been shown to hold.
 */
class SentenceTemplatesTest {
    private val templates = SentenceTemplates(Random(7))

    private fun word(text: String, category: Category) = Item(text = text, category = category, kind = ItemKind.WORD)

    private val pool = listOf(
        word("εγώ", Category.PEOPLE), word("μαμά", Category.PEOPLE),
        word("θέλω", Category.VERBS), word("τρώω", Category.VERBS), word("πίνω", Category.VERBS),
        word("πάμε", Category.VERBS), word("γράφω", Category.VERBS),
        word("καφές", Category.FOOD), word("νερό", Category.FOOD), word("ψωμί", Category.FOOD),
        word("χυμός", Category.FOOD), word("πατάτες", Category.FOOD), word("κρέας", Category.FOOD),
        word("βιβλίο", Category.THINGS), word("τηλέφωνο", Category.THINGS),
        word("σπίτι", Category.PLACES), word("καφετέρια", Category.PLACES),
        word("τώρα", Category.TIME), word("αύριο", Category.TIME), word("Δευτέρα", Category.TIME),
    )

    /** What «πίνω» takes and «τρώω» does not, written out here rather than read from the templates. */
    private val drinkable = setOf("νερό", "καφές", "τσάι", "γάλα", "μπύρα", "κρασί", "χυμός")

    private fun many(level: Int, times: Int = 60): List<Sentence> =
        List(times) { templates.generate(level, pool) }.map { assertNotNull("level $level made nothing", it); it!! }

    @Test fun `level 1 is a verb and its object in the accusative`() {
        for (s in many(1)) {
            assertEquals(1, s.level)
            assertEquals("two tiles: ${s.text}", 2, s.tiles.size)
            assertEquals(Category.VERBS, s.tiles[0].item.category)
            assertEquals(s.tiles[0].item.text, s.tiles[0].label)
            assertTrue("object out of place: ${s.text}", s.tiles[1].item.category in setOf(Category.FOOD, Category.THINGS))
            // «θέλω καφέ», never «θέλω καφές»: the ending is the whole point of the exercise.
            assertEquals(Greek.accusative(s.tiles[1].item.text), s.tiles[1].label)
            assertEquals(s.tiles.joinToString(" ") { it.label }, s.text)
        }
    }

    @Test fun `level 2 is three tiles that start with εγώ or πάμε`() {
        for (s in many(2)) {
            assertEquals("three tiles: ${s.text}", 3, s.tiles.size)
            assertTrue("starts with ${s.tiles[0].label}", s.tiles[0].label in setOf("εγώ", "πάμε"))
        }
    }

    @Test fun `level 3 ends with a time word`() {
        for (s in many(3)) {
            assertTrue("only ${s.tiles.size} tiles: ${s.text}", s.tiles.size >= 3)
            val last = s.tiles.last()
            assertEquals("does not end in time: ${s.text}", Category.TIME, last.item.category)
            assertTrue("not one of his time words: ${last.label}", last.label in setOf("τώρα", "σήμερα", "αύριο", "μετά"))
        }
    }

    @Test fun `level 4 adds a distractor that is not in the sentence`() {
        for (s in many(4)) {
            val distractor = s.distractor
            assertNotNull("no distractor at level 4: ${s.text}", distractor)
            assertTrue("the distractor is in the sentence: ${s.text}", s.tiles.none { it.item.id == distractor!!.item.id })
            assertTrue("the distractor repeats a word: ${s.text}", s.tiles.none { it.label == distractor!!.label })
        }
    }

    @Test fun `nothing below level 4 has a distractor`() {
        for (level in 1..3) for (s in many(level, times = 20)) assertNull("level $level: ${s.text}", s.distractor)
    }

    /** He drinks water and eats bread, and the pictures he is given never say otherwise. */
    @Test fun `πίνω never pairs with ψωμί`() {
        for (level in 1..4) for (s in many(level, times = 60)) {
            val verb = s.tiles.firstOrNull { it.label == "πίνω" } ?: continue
            val after = s.tiles[s.tiles.indexOf(verb) + 1]
            assertTrue("«${s.text}» is not a drink", after.item.text in drinkable)
        }
    }

    /** Nor does he eat water: what a verb takes is part of the verb. */
    @Test fun `τρώω never pairs with a drink`() {
        for (level in 1..4) for (s in many(level, times = 60)) {
            val verb = s.tiles.firstOrNull { it.label == "τρώω" } ?: continue
            val after = s.tiles[s.tiles.indexOf(verb) + 1]
            assertTrue("«${s.text}» is a drink", after.item.text !in drinkable)
        }
    }

    /** Two tiles of the same word would be two tiles he cannot tell apart on the board. */
    @Test fun `a sentence never uses the same word twice`() {
        for (level in 1..4) for (s in many(level, times = 40)) {
            assertEquals("repeats itself: ${s.text}", s.tiles.size, s.tiles.map { it.item.id }.toSet().size)
        }
    }

    @Test fun `generate returns null when the pool has no verbs`() {
        val verbless = pool.filterNot { it.category == Category.VERBS }
        for (level in 1..4) assertNull("level $level built something out of nothing", templates.generate(level, verbless))
    }

    /** A level the vocabulary cannot fill is a null, not a shorter sentence pretending to be one. */
    @Test fun `generate returns null when there is no time word to end on`() {
        val timeless = pool.filterNot { it.category == Category.TIME }
        assertNotNull(templates.generate(2, timeless))
        assertNull(templates.generate(3, timeless.filterNot { it.text == "εγώ" }))
    }

    /**
     * The level-4 defect: with «εγώ θέλω νερό τώρα» on the board and «καφέ» beside it, his own
     * «εγώ θέλω καφέ τώρα» is faultless Greek and nothing on the screen ever said which of the two
     * the app meant. Over the vocabulary he actually has, no distractor may be a word that could
     * stand in the slot it is sitting next to — the sentence on the board has one right answer.
     */
    @Test fun `no level 4 distractor could fill the slot it stands next to`() {
        val pool = seedPool()
        repeat(500) {
            val s = templates.generate(4, pool) ?: throw AssertionError("the seed cannot build level 4")
            val verb = s.tiles.first { it.item.category == Category.VERBS }.label
            val odd = s.distractor!!.item
            assertTrue("«${odd.text}» could fill the object of «$verb» in «${s.text}»", !couldFollow(verb, odd))
            assertTrue("«${odd.text}» could be the time word of «${s.text}»", odd.category != Category.TIME)
            assertTrue("«${odd.text}» could be the subject of «${s.text}»", odd.category != Category.PEOPLE)
        }
    }

    /** What each verb can really take, written out here rather than read from the templates. */
    private fun couldFollow(verb: String, item: Item): Boolean = when (verb) {
        "πάμε" -> item.category == Category.PLACES
        "θέλω" -> item.category in setOf(Category.FOOD, Category.THINGS)
        "τρώω" -> item.category == Category.FOOD && item.text !in drinkable
        "πίνω" -> item.text in drinkable
        else -> throw AssertionError("unknown verb $verb")
    }

    /**
     * «πάμε» takes a bare place only where Greek lets it. A room or a car needs the preposition he
     * is not being asked for, and this module must not put «πάμε κρεβάτι» on the screen as the
     * model sentence.
     */
    @Test fun `πάμε only ever goes somewhere it can go without a preposition`() {
        val pool = seedPool()
        val notDestinations = setOf("ταξί", "λεωφορείο", "αυτοκίνητο", "δρόμος", "κουζίνα", "κρεβάτι", "μπαλκόνι")
        for (level in 2..4) repeat(200) {
            val s = templates.generate(level, pool)!!
            val words = (s.tiles + listOfNotNull(s.distractor)).map { it.item.text }
            assertTrue("«${s.text}» goes where it cannot go", words.none { it in notDestinations })
        }
    }

    @Test fun `session returns the count it was asked for`() {
        assertEquals(8, templates.session(1, pool).size)
        assertEquals(5, templates.session(3, pool, count = 5).size)
        assertEquals(8, templates.session(4, pool).size)
    }

    /** Skipping nulls means skipping them, not looping for ever over a pool that cannot fill the shape. */
    @Test fun `session comes back empty rather than hanging when nothing can be built`() {
        assertEquals(emptyList<Sentence>(), templates.session(1, pool.filterNot { it.category == Category.VERBS }))
    }

    /**
     * The vocabulary he actually has: the bundled seed manifest, single words only, exactly as the
     * importer would put them in the database. A hand-written pool proves the rules; only the real
     * one proves they hold over the cards on his phone.
     */
    private fun seedPool(): List<Item> = SeedManifest.parse(asset("seed/seed.json").readText()).items
        .filter { it.kind == ItemKind.WORD.name }
        .map { Item(text = it.text, kind = ItemKind.WORD, category = Category.valueOf(it.category)) }

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
}
