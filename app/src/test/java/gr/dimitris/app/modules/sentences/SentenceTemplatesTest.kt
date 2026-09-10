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

    // ------------------------------------------------- the small words (levels 5–8)

    /**
     * Every noun a level-5 board puts an article in front of gets the article its own gender and
     * number ask for, over the whole seed vocabulary and five hundred boards.
     *
     * The genders are written out again below, by hand, rather than read from
     * [gr.dimitris.app.core.greek.nounForm]: a test that asked the code what «γάλα» is and then
     * checked the code had used that answer would prove nothing. [FORMS] is the second opinion, and
     * a seed word missing from it fails by name rather than being quietly skipped — which is what
     * keeps it complete as the vocabulary grows.
     */
    @Test fun `level 5 puts the right article on the subject and on the object`() {
        val pool = seedPool()
        repeat(BOARDS) {
            val s = templates.generate(5, pool) ?: throw AssertionError("the seed cannot build level 5")
            assertEquals("five cards: ${s.text}", 5, s.tiles.size)
            val (article, subject, verb, objectArticle, obj) = s.tiles
            assertEquals("the subject is a person: ${s.text}", Category.PEOPLE, subject.item.category)
            assertTrue("a pronoun took an article: ${s.text}", subject.item.text !in setOf("εγώ", "εσύ"))
            assertEquals("wrong article on «${subject.item.text}»: ${s.text}", nominative(subject.item.text), article.label)
            assertEquals("the subject is written as it stands: ${s.text}", subject.item.text, subject.label)
            assertTrue("not a third-person verb: ${verb.label}", verb.label in setOf("θέλει", "τρώει", "πίνει"))
            assertTrue("the object is not a thing: ${s.text}", obj.item.category in setOf(Category.FOOD, Category.THINGS))
            assertEquals("wrong article on «${obj.item.text}»: ${s.text}", accusative(obj.item.text), objectArticle.label)
            assertEquals("the object is not in the accusative: ${s.text}", Greek.accusative(obj.item.text), obj.label)
            assertTrue("the small words are cards of their own: ${s.text}", article.madeUp && objectArticle.madeUp)
        }
    }

    /** «σε» is never a card of its own: Greek writes it into the article, and that is the level. */
    @Test fun `level 6 contracts σε into the article and rides with the right one`() {
        val pool = seedPool()
        repeat(BOARDS) {
            val s = templates.generate(6, pool) ?: throw AssertionError("the seed cannot build level 6")
            assertEquals("six cards: ${s.text}", 6, s.tiles.size)
            val (verb, to, place, with, by) = s.tiles
            val ride = s.tiles[5]
            assertEquals("«πάω» is what one man going somewhere says: ${s.text}", "πάω", verb.label)
            assertEquals("wrong contraction for «${place.item.text}»: ${s.text}", "σ" + accusative(place.item.text), to.label)
            assertEquals("the place is not in the accusative: ${s.text}", Greek.accusative(place.item.text), place.label)
            assertEquals("«με» went missing: ${s.text}", "με", with.label)
            assertTrue("that is not something to ride in: ${s.text}", ride.item.text in VEHICLES)
            assertEquals("wrong article on «${ride.item.text}»: ${s.text}", accusative(ride.item.text), by.label)
            assertTrue("a vehicle is not a destination: ${s.text}", place.item.text !in VEHICLES)
        }
    }

    /** Two clauses and the two words that join them, and the reason belongs to the verb. */
    @Test fun `level 7 joins two clauses with να and γιατί`() {
        val pool = seedPool()
        repeat(BOARDS) {
            val s = templates.generate(7, pool) ?: throw AssertionError("the seed cannot build level 7")
            assertEquals("six cards: ${s.text}", 6, s.tiles.size)
            val (want, na, subjunctive, obj, because) = s.tiles
            val reason = s.tiles[5]
            assertEquals("θέλω", want.label)
            assertEquals("να", na.label)
            assertEquals("γιατί", because.label)
            assertEquals(
                "«${subjunctive.label}» is not the reason for «${reason.label}»: ${s.text}",
                if (subjunctive.label == "φάω") "πεινάω" else "διψάω", reason.label,
            )
            assertTrue("not a subjunctive: ${subjunctive.label}", subjunctive.label in setOf("φάω", "πιω"))
            // He eats what can be eaten and drinks what can be drunk, three levels up as well.
            if (subjunctive.label == "πιω") assertTrue("«${s.text}» is not a drink", obj.item.text in drinkable)
            else assertTrue("«${s.text}» is a drink", obj.item.text !in drinkable)
            assertEquals("the object is not in the accusative: ${s.text}", Greek.accusative(obj.item.text), obj.label)
        }
    }

    @Test fun `level 8 is a question, with the article the answer takes`() {
        val pool = seedPool()
        repeat(BOARDS) {
            val s = templates.generate(8, pool) ?: throw AssertionError("the seed cannot build level 8")
            assertEquals("four cards: ${s.text}", 4, s.tiles.size)
            val (where, isThere, article, thing) = s.tiles
            assertEquals("πού", where.label)
            assertEquals("είναι", isThere.label)
            assertEquals("wrong article on «${thing.item.text}»: ${s.text}", nominative(thing.item.text), article.label)
            assertEquals("the noun of a question is its subject: ${s.text}", thing.item.text, thing.label)
            assertTrue("a question without a question mark: ${s.text}", s.text.endsWith(";"))
            assertTrue("the mark belongs to the sentence, not to a card", s.tiles.none { it.label.contains(";") })
            assertTrue("a level-8 board has to know it is a question: ${s.text}", s.question)
            // Not everything with a picture is somewhere. «πού είναι ο ήλιος;» parses and nobody says it.
            assertTrue("«${thing.item.text}» is not anywhere: ${s.text}", thing.item.text !in NOT_SOMEWHERE)
        }
    }

    /**
     * A typed board says what it wants, and the judge is told the same thing.
     *
     * A picture of a chemist's under «Γράψε την πρόταση» is answered just as well by «θέλω να πάω στο
     * φαρμακείο» — faultless Greek, and not the question level 8 asked for. Nothing on the screen
     * said which, and the only other way to find out is «Άκου», which reads the answer out loud and
     * spends the mark: every level-8 typed board would have come out ASSISTED.
     */
    @Test fun `a typed board asks for a question at level 8 and a sentence at level 7`() {
        val pool = seedPool()
        repeat(BOARDS / 5) {
            val seven = templates.generate(7, pool, Variant.TYPED)!!
            assertEquals(false, seven.question)
            assertEquals("${SentencesViewModel.WRITE_IT} ${seven.picture!!.item.text}", SentencesViewModel.asked(seven))

            val eight = templates.generate(8, pool, Variant.TYPED)!!
            assertEquals(true, eight.question)
            val line = SentencesViewModel.asked(eight)
            assertEquals("${SentencesViewModel.WRITE_A_QUESTION} ${eight.picture!!.item.text}", line)
            assertTrue("the line does not say a question is wanted: $line", "ερώτηση" in line)
        }
    }

    /**
     * The top-up loop must not restart the every-third rhythm: a first pass that came back one short
     * would otherwise put two variants next to each other in the same sitting.
     */
    @Test fun `a sitting topped back up keeps counting from where it got to`() {
        val pool = seedPool()
        val first = templates.session(5, pool, count = 4)
        val rest = templates.session(5, pool, count = 4, from = first.size)
        val whole = first + rest
        for ((i, s) in whole.withIndex()) {
            val wanted = if (i % 3 == 2) Variant.GAP else Variant.BUILD
            assertEquals("board $i of a topped-up sitting", wanted, s.variant)
        }
    }

    /**
     * The odd card out at the article levels is an article that agrees with **nothing** on the
     * board, which is what makes it unplaceable rather than merely unused: an article can only stand
     * in front of a noun, and every noun he has is one of the sentence's own.
     */
    @Test fun `the odd card at the article levels fits no noun on the board`() {
        val pool = seedPool()
        for (level in 5..8) repeat(BOARDS / 4) {
            val s = templates.generate(level, pool)!!
            val odd = s.distractor ?: throw AssertionError("level $level owes him an odd card: ${s.text}")
            assertTrue("the odd card is not a small word: ${odd.label}", odd.madeUp && odd.label in ARTICLES)
            assertTrue("the odd card is already in the sentence: ${s.text}", s.tiles.none { it.label == odd.label })
            for (tile in s.tiles.filterNot { it.madeUp }) {
                if (tile.item.category == Category.VERBS) continue
                // No `?: continue` here: a seed word missing from the hand-written table has to fail
                // by name, which is what `nominative`/`accusative` do when they cannot find one.
                val word = tile.item.text
                assertTrue(
                    "«${odd.label}» would fit «$word» in «${s.text}»",
                    odd.label !in setOf(nominative(word), accusative(word), "σ" + accusative(word)),
                )
            }
        }
    }

    /**
     * A gap board offers three small words, one of which is the sentence's own and two of which
     * agree with nothing it holds. Anything looser and he would be marked wrong for an answer that
     * was also right, which is the level-4 defect all over again in the part of the sentence he
     * actually drops.
     */
    @Test fun `a gap board has exactly one filler that fits`() {
        val pool = seedPool()
        for (level in SentenceTemplates.GAP_LEVELS) repeat(BOARDS / 2) {
            val s = templates.generate(level, pool, Variant.GAP)!!
            assertEquals("no gap was made at level $level: ${s.text}", Variant.GAP, s.variant)
            val gap = s.gap!!
            assertEquals("three cards and no more", SentenceTemplates.OPTIONS, gap.options.size)
            assertEquals("the same card twice: ${gap.options}", gap.options.size, gap.options.toSet().size)
            assertTrue("an option is not a small word: ${gap.options}", gap.options.all { it in ARTICLES })
            assertEquals("the blank is not a small word: ${s.text}", true, s.tiles[gap.at].madeUp)
            assertTrue("the answer is not on offer: ${gap.options}", gap.options.any { it == s.answer })
            // The noun the blank stands in front of, and what would really agree with it.
            val noun = s.tiles[gap.at + 1].item.text
            val fits = setOf(nominative(noun), accusative(noun), "σ" + accusative(noun))
            for (wrong in gap.options.filterNot { it == s.answer }) {
                assertTrue("«$wrong» also fits «$noun» in «${s.withBlank("__")}»", wrong !in fits)
            }
            assertTrue("the blank is not written out: ${s.withBlank("__")}", "__" in s.withBlank("__"))
        }
    }

    /** A typed board is a picture and a sentence about it, and there is always something to show. */
    @Test fun `a typed board always has a picture to write about`() {
        val pool = seedPool()
        for (level in SentenceTemplates.TYPED_LEVELS) repeat(BOARDS / 2) {
            val s = templates.generate(level, pool, Variant.TYPED)!!
            assertEquals("no typed board at level $level: ${s.text}", Variant.TYPED, s.variant)
            val picture = s.picture!!
            assertTrue("the picture is a card the module made up: ${picture.label}", !picture.madeUp)
            assertTrue("the picture is not in the sentence: ${s.text}", s.tiles.any { it.item.id == picture.item.id })
        }
    }

    /** One board in three, and only where the level has something to ask that way. */
    @Test fun `every third board is the variant and the others are the cards`() {
        for (level in 1..4) for (i in 0..8) {
            assertEquals("level $level board $i", Variant.BUILD, templates.variantFor(i, level, judged = true))
        }
        for (level in 5..6) for (i in 0..8) {
            val wanted = if (i % 3 == 2) Variant.GAP else Variant.BUILD
            assertEquals("level $level board $i", wanted, templates.variantFor(i, level, judged = false))
        }
        for (level in 7..8) for (i in 0..8) {
            assertEquals("level $level board $i", if (i % 3 == 2) Variant.TYPED else Variant.BUILD, templates.variantFor(i, level, judged = true))
            // Nothing on the phone can read a sentence he typed: with the judge off there is no
            // typed board at all, and the sitting is eight ordinary ones.
            assertEquals("judge off, level $level board $i", Variant.BUILD, templates.variantFor(i, level, judged = false))
        }
    }

    @Test fun `a sitting at the typed levels comes back with typed boards only when the judge will answer`() {
        val pool = seedPool()
        assertTrue("no typed board with the judge on", templates.session(7, pool, judged = true).any { it.variant == Variant.TYPED })
        assertTrue(
            "a typed board with the judge off",
            templates.session(7, pool, judged = false).none { it.variant == Variant.TYPED },
        )
        assertTrue("no gap board at level 5", templates.session(5, pool, judged = false).any { it.variant == Variant.GAP })
    }

    /**
     * A word this app cannot read the gender of is a word the article levels do not use. «η ρούχα»
     * on the screen, as the sentence he is being asked to copy, is worse than one card fewer.
     *
     * The three words added here are the shapes the rules deliberately refuse: an -α that could be
     * a feminine singular or a neuter plural and no ending tells which, and two foreign nouns. A
     * caregiver may add any of them and the talk board, the word coach and levels 1–4 will use them
     * exactly as before — this is the one place in the app that would have to guess, and does not.
     */
    @Test fun `no board above level 4 uses a word whose article we would have to guess`() {
        val pool = seedPool() + listOf(
            word("τυρόπιτα", Category.FOOD), word("ζαμπόν", Category.FOOD), word("κρουασάν", Category.FOOD),
        )
        val guesses = setOf("τυρόπιτα", "ζαμπόν", "κρουασάν")
        var bare = 0
        for (level in 5..8) repeat(BOARDS / 4) {
            val s = templates.generate(level, pool)!!
            for ((i, tile) in s.tiles.withIndex()) {
                if (!tile.madeUp || tile.label !in ARTICLES) continue
                val noun = s.tiles[i + 1].item.text
                assertNotNull("«$noun» took an article we would have to guess: ${s.text}", FORMS[noun])
                assertTrue("«$noun» took an article: ${s.text}", noun !in guesses)
            }
            // Level 7 puts its object bare — «θέλω να φάω κρουασάν γιατί πεινάω» is faultless Greek
            // and needs no gender at all — so a word this file cannot read is welcome there.
            if (s.tiles.any { it.item.text in guesses }) bare++
        }
        assertTrue("a word with no article we know was never used at all", bare > 0)
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

    /**
     * The one string on this screen that answers an order he got wrong, pinned by value.
     *
     * Spec §12: nothing may call an assisted, listened or retried turn wrong, and since «Άκου» he
     * can reach this line straight after doing the thing the app tells him to do. The flow test
     * reads the constant, so only this assertion makes changing the wording a deliberate act.
     */
    @Test fun `a rebuilt sentence is answered with encouragement, never a no`() {
        assertEquals("Σχεδόν.", SentencesViewModel.WRONG_ORDER)
    }

    // --------------------------------- the second opinion the article levels are checked against

    /**
     * What every word of the seed is, written out by hand.
     *
     * This is the whole point of the article tests: [gr.dimitris.app.core.greek.nounForm] has its own
     * list and its own ending rules, and asking it what «γάλα» is and then checking it had used that
     * answer would prove nothing at all. So the genders live here a second time, read off the
     * vocabulary one word at a time, and the two have to agree over five hundred boards.
     *
     * A seed word that is missing from here fails the sweep by name — see the last test — which is
     * what keeps this list complete as the vocabulary grows.
     */
    private val FORMS: Map<String, Pair<Char, Boolean>> = buildMap {
        fun put(gender: Char, plural: Boolean, vararg words: String) = words.forEach { put(it, gender to plural) }
        // PEOPLE, minus the two pronouns, which never take an article.
        put('m', false, "μπαμπάς", "αδελφός", "φίλος", "γιατρός", "φυσιοθεραπευτής", "γείτονας", "άντρας")
        put('f', false, "μαμά", "αδελφή", "φίλη", "νοσοκόμα", "λογοθεραπεύτρια", "γυναίκα")
        put('n', false, "παιδί")
        // FOOD
        put('m', false, "καφές", "χυμός")
        put('f', false, "μπύρα", "πίτσα", "σαλάτα", "μπανάνα", "σοκολάτα", "σούπα", "ζάχαρη")
        put(
            'n', false, "νερό", "ψωμί", "τυρί", "γάλα", "τσάι", "κρασί", "σουβλάκι", "ρύζι", "κρέας",
            "κοτόπουλο", "ψάρι", "αυγό", "μήλο", "πορτοκάλι", "παγωτό", "γλυκό", "αλάτι", "πρωινό",
            "μεσημεριανό", "βραδινό",
        )
        put('f', true, "πατάτες")
        put('n', true, "μακαρόνια", "φρούτα")
        // THINGS
        put('m', false, "ήλιος")
        put('f', false, "τηλεόραση", "τσάντα", "ομπρέλα", "καρέκλα", "πόρτα", "μουσική", "μπάλα", "βροχή")
        put('n', false, "τηλέφωνο", "πορτοφόλι", "ρολόι", "τραπέζι", "παράθυρο", "βιβλίο")
        put('n', true, "κλειδιά", "γυαλιά", "ρούχα", "παπούτσια", "φάρμακα", "χρήματα")
        // PLACES
        put('m', false, "δρόμος")
        put('f', false, "καφετέρια", "θάλασσα", "εκκλησία", "τράπεζα", "ταβέρνα", "κουζίνα", "δουλειά")
        put(
            'n', false, "σπίτι", "νοσοκομείο", "φαρμακείο", "σούπερ μάρκετ", "πάρκο", "γυμναστήριο",
            "σχολείο", "ταξί", "λεωφορείο", "αυτοκίνητο", "μπάνιο", "κρεβάτι", "γήπεδο", "μπαλκόνι",
        )
    }

    /** «ο», «η», «το», «οι», «τα». */
    private fun nominative(word: String): String {
        val (gender, plural) = FORMS[word] ?: throw AssertionError("no gender written down for «$word»")
        return when {
            plural && gender == 'n' -> "τα"
            plural -> "οι"
            gender == 'm' -> "ο"
            gender == 'f' -> "η"
            else -> "το"
        }
    }

    /**
     * «τον», «την»/«τη», «το», «τους», «τις», «τα» — in front of the word as it is written after a
     * verb or a preposition, which is the accusative and which is what decides the feminine's «ν».
     *
     * The masculine keeps its «ν» always: that is what tells «τον καφέ» from «το γάλα» out loud, and
     * a module about the small words that dropped it would be teaching the deficit.
     */
    private fun accusative(word: String): String {
        val (gender, plural) = FORMS[word] ?: throw AssertionError("no gender written down for «$word»")
        return when {
            plural && gender == 'n' -> "τα"
            plural && gender == 'f' -> "τις"
            plural -> "τους"
            gender == 'm' -> "τον"
            gender == 'n' -> "το"
            else -> if (keepsNu(Greek.accusative(word))) "την" else "τη"
        }
    }

    /**
     * The school rule for the feminine article's final «ν»: it stays before a vowel and before the
     * sounds that can carry it — κ, π, τ, ξ, ψ and the digraphs μπ, ντ, γκ, τσ, τζ — and goes
     * everywhere else. «την τράπεζα», «την μπύρα», «την ομπρέλα»; «τη θάλασσα», «τη σούπα».
     */
    private fun keepsNu(next: String): Boolean {
        val w = Greek.stripAccents(Greek.normalize(next))
        if (w.isEmpty()) return true
        return w.take(2) in setOf("μπ", "ντ", "γκ", "τσ", "τζ") || w[0] in "αεηιουωκπτξψ"
    }

    /** Every article that can appear on a card of its own, plus the «σε» contractions. */
    private val ARTICLES = setOf(
        "ο", "η", "το", "οι", "τα", "τον", "την", "τη", "τους", "τις",
        "στον", "στην", "στη", "στο", "στους", "στις", "στα",
    )

    /** The PLACES cards that are something to go in rather than somewhere to be. */
    private val VEHICLES = setOf("ταξί", "λεωφορείο", "αυτοκίνητο")

    /** The cards that are not anywhere, so no level-8 board asks where they are. */
    private val NOT_SOMEWHERE = setOf("ήλιος", "βροχή", "μουσική")

    /** How many boards a sweep over the real vocabulary is worth. See the level-4 test above. */
    private val BOARDS = 500

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
