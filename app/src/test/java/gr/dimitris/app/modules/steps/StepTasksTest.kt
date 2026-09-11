package gr.dimitris.app.modules.steps

import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.speech.SpeechMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The bundled tasks themselves, read as the phone reads them.
 *
 * Everything here was decided once and must not have to be found by hand again. A step longer than he
 * can read off a tile, two steps of one task worded the same (the strip is checked by text, so a
 * repeat would be unanswerable), a distractor that is not really a step of another task — which would
 * make difficulty 5 a trick question instead of a harder one — a difficulty that does not follow the
 * number of steps, or a drawing the manifest names and the APK has not got.
 */
class StepTasksTest {
    private val seed: StepTasks = StepTasks.parse(assetFile(StepTasks.ASSET).readText())
    private val tasks = seed.tasks

    @Test fun `the seed parses and holds the twenty tasks`() {
        assertEquals(20, tasks.size)
        assertEquals("two tasks share an id", tasks.size, tasks.distinctBy { it.id }.size)
        assertEquals("two tasks share a title", tasks.size, tasks.distinctBy { it.title }.size)
        for (task in tasks) {
            assertTrue("«${task.title}» has no id", task.id.isNotBlank())
            assertTrue("«${task.id}» is not graded 1..5: ${task.difficulty}", task.difficulty in 1..5)
        }
    }

    /**
     * Three to six steps, each of them a phrase he can read off a tile in one look. Six words is the
     * ceiling because three of these sit side by side on a phone.
     */
    @Test fun `every step is a short Greek phrase`() {
        for (task in tasks) {
            assertTrue("«${task.id}» has ${task.steps.size} steps", task.steps.size in StepTasks.MIN_STEPS..StepTasks.MAX_STEPS)
            for (step in task.steps + listOfNotNull(task.distractor)) {
                assertEquals("«${step.text}» is not stored trimmed", step.text.trim(), step.text)
                val words = step.text.split(' ').filter { it.isNotBlank() }
                assertTrue("«${step.text}» is ${words.size} words", words.size in 1..MAX_WORDS)
                // Greek only: this app has no English in it, and the `en` term is for the fetch script.
                assertTrue("«${step.text}» has Latin letters in it", step.text.none { it in 'a'..'z' || it in 'A'..'Z' })
            }
        }
    }

    /**
     * Every step names the English term its drawing was searched with. Nothing crashes without one —
     * `en` is read by the fetch script and by nothing on the phone — but a hand edit that drops one
     * makes that step ship text-led at the next fetch, silently.
     */
    @Test fun `every step keeps the term its drawing was found with`() {
        for (task in tasks) {
            for (step in task.steps + listOfNotNull(task.distractor)) {
                assertTrue("«${step.text}» has no en term", step.en?.isNotBlank() == true)
                assertEquals("«${step.en}» is not stored trimmed", step.en!!.trim(), step.en)
            }
        }
    }

    /** Two steps of one task worded the same would be a strip that cannot be checked. */
    @Test fun `the steps of a task are all different`() {
        for (task in tasks) {
            assertEquals("«${task.id}» repeats a step", task.steps.size, task.steps.distinctBy { it.text }.size)
        }
    }

    /**
     * And across the whole seed too, which is stricter than the module needs and is what makes the
     * distractor rule below unambiguous: a step worded the same in two tasks would belong to both.
     */
    @Test fun `no two tasks share a step`() {
        val all = tasks.flatMap { it.steps }.map { it.text }
        assertEquals("a step is in two tasks: ${all.groupBy { it }.filter { it.value.size > 1 }.keys}", all.size, all.distinct().size)
    }

    /**
     * The difficulty *is* the number of steps: 3 → 1, 4 → 2, 5 → 3, 6 → 4, and six with a distractor
     * → 5. It is the only thing the dot changes in this module, so a task graded against its own length
     * is a dot that means nothing.
     */
    @Test fun `the difficulty follows the number of steps`() {
        for (task in tasks) {
            val expected = if (task.distractor != null) 5 else task.steps.size - 2
            assertEquals("«${task.id}» has ${task.steps.size} steps and a distractor? ${task.distractor != null}", expected, task.difficulty)
            // A distractor is what dot 5 adds *on top of* the longest task, never a way to grade a
            // short one as hard: five steps and a distractor would be an easier board called harder.
            if (task.distractor != null) {
                assertEquals("«${task.id}» is a distractor task of ${task.steps.size} steps", StepTasks.MAX_STEPS, task.steps.size)
            }
        }
        // All five exist, four of each, so every dot is a different sitting and nothing clamps.
        assertEquals((1..5).associateWith { 4 }, tasks.groupingBy { it.difficulty }.eachCount())
    }

    /**
     * A distractor is a plausible step of **another** task, never an invention and never one of this
     * task's own. That is the whole of what difficulty 5 adds: not "put these in order" but "decide
     * what is part of this at all".
     */
    @Test fun `every distractor is a real step of another task`() {
        val withOne = tasks.filter { it.distractor != null }
        assertEquals("difficulty 5 is the only one with a distractor", tasks.filter { it.difficulty == 5 }, withOne)
        for (task in withOne) {
            val text = task.distractor!!.text
            val owner = tasks.filter { other -> other.steps.any { it.text == text } }
            assertEquals("«$text» is a step of ${owner.size} tasks", 1, owner.size)
            assertTrue("«$text» is «${task.id}»'s own step", owner.single().id != task.id)
            // The pairing is decided by a person, once, and written down here: a later edit that moves
            // a distractor onto a task it could belong to is the defect this test exists for.
            assertEquals("«${task.id}»'s distractor is borrowed from somewhere new", BORROWED[task.id], owner.single().id)
            // And **which** step of it, to the letter. The owner alone is not enough: «Ρίχνω
            // απορρυπαντικό» — the rejected washing-up distractor, whose host's own step 4 reads
            // «Πλένω με σφουγγάρι και υγρό» — is a step of `laundry` exactly as its replacement is,
            // and shares no content word with `dishes` either, so both of the checks above passed it.
            // A revert to it has to fail something.
            assertEquals("«${task.id}» is not the distractor somebody chose", EXPECTED_DISTRACTOR[task.id], text)
        }
    }

    /**
     * A distractor must be **foreign** to the task it is dropped into, not merely absent from its step
     * list. The first cut had «Ρίχνω απορρυπαντικό» on the washing-up board — whose own step 4 is
     * «Πλένω με σφουγγάρι και υγρό» — so a man who left it out by every real-world standard was right
     * and a man who put it in was told he was wrong for a reason nobody could explain. Also «Παίρνω το
     * πορτοφόλι» at the cash machine and «Σκουπίζομαι με την πετσέτα» at bedtime.
     *
     * The heuristic that catches it: a distractor may share **no** content word with the host's title
     * or with any of its steps. Crude, and it is exactly what those three failed — «παίρνω» at the ATM,
     * «πλένω» at the sink, «πετσέτα» at bedtime — while every honest pairing passes it untouched.
     */
    @Test fun `no distractor could belong to the task it is dropped into`() {
        for (task in tasks.filter { it.distractor != null }) {
            val host = (task.order + task.title).flatMap(::contentWords).toSet()
            val shared = contentWords(task.distractor!!.text).filter { it in host }
            assertTrue("«${task.distractor!!.text}» shares $shared with «${task.title}»", shared.isEmpty())
        }
    }

    /** Every drawing the seed names is in the APK, and every drawing in the APK is named. */
    @Test fun `the seed and the pictogram folder agree`() {
        val dir = File(assetFile(StepTasks.ASSET).parentFile, "steps")
        val named = tasks.flatMap { it.steps + listOfNotNull(it.distractor) }.mapNotNullTo(mutableSetOf()) { it.image }
        val onDisk = dir.listFiles { f: File -> f.name.endsWith(".png") }.orEmpty().mapTo(mutableSetOf()) { "steps/${it.name}" }
        assertEquals("drawings the seed names that are not in the APK", emptySet<String>(), named - onDisk)
        assertEquals("drawings in the APK that no step points at", emptySet<String>(), onDisk - named)
    }

    /** What Coil is handed. A step with no drawing is text-led and says so by handing back null. */
    @Test fun `a step's drawing is read straight out of the assets`() {
        val step = tasks.first().steps.first()
        assertEquals("file:///android_asset/seed/${step.image}", step.asset)
        assertNull(Step(text = "χωρίς εικόνα").asset)
        assertNull(Step(text = "κενό", image = "  ").asset)
    }

    // ------------------------------------------------------------------ the telling

    /**
     * «Πρώτα …, μετά …, τέλος ….» — the three connectors the whole second stage exists for, and the
     * one sentence in this module that is generated rather than written by hand.
     */
    @Test fun `the telling is first, then and last`() {
        val coffee = tasks.single { it.id == "coffee" }
        assertEquals(
            "Πρώτα βάζω νερό στο μπρίκι, μετά ρίχνω καφέ και ζάχαρη, τέλος το βάζω στη φωτιά.",
            coffee.telling,
        )
        // Every step appears once, the first letters are lowered, and «μετά» carries the middle.
        for (task in tasks) {
            val telling = task.telling
            assertTrue("«${task.id}»: $telling", telling.startsWith("${StepTask.FIRST} "))
            assertTrue("«${task.id}»: $telling", telling.endsWith("."))
            assertEquals(
                "«${task.id}» does not say «${StepTask.LAST}» once",
                1, Regex(", ${StepTask.LAST} ").findAll(telling).count(),
            )
            assertEquals(
                "«${task.id}» has the wrong number of «${StepTask.THEN}»",
                task.steps.size - 2, Regex(", ${StepTask.THEN} ").findAll(telling).count(),
            )
            assertTrue("«${task.id}» leaves the distractor out of the telling", task.distractor?.text?.let { it !in telling } ?: true)
        }
    }

    @Test fun `a telling of two steps is first and last, and of one is the step itself`() {
        assertEquals("Πρώτα α, τέλος β.", StepTask.tellingOf(listOf("Α", "Β")))
        assertEquals("Α.", StepTask.tellingOf(listOf("Α.")))
        assertEquals("", StepTask.tellingOf(emptyList()))
        assertEquals("blank steps are not steps", "", StepTask.tellingOf(listOf("  ", "")))
    }

    // ------------------------------------------------------------------- the groups

    /**
     * A group is a **run** of consecutive steps: the seed may say "these three go in any order", never
     * "step 2 and step 5 are interchangeable but step 3 is not". A scattered group would be a rule
     * nobody could read off the screen, and the check — which compares the group at each position —
     * would then accept orders nobody intended.
     */
    @Test fun `groups are runs of consecutive steps, numbered from the front`() {
        for (task in tasks) {
            val stated = task.steps.mapNotNull { it.group }
            assertTrue(
                "«${task.id}» grades some of its steps and not others",
                stated.isEmpty() || stated.size == task.steps.size,
            )
            if (stated.isEmpty()) continue
            assertTrue("«${task.id}» has a group below 1: $stated", stated.all { it >= 1 })
            for ((group, positions) in stated.withIndex().groupBy({ it.value }, { it.index })) {
                assertEquals(
                    "«${task.id}» scatters group $group over $positions",
                    (positions.first()..positions.last()).toList(), positions,
                )
            }
            assertEquals("«${task.id}» does not number its groups front to back", stated.sorted(), stated)
            assertEquals("«${task.id}» skips a group number", (1..stated.max()).toList(), stated.distinct())
            assertTrue("«${task.id}» is one group and no sequence at all", stated.distinct().size > 1)
        }
    }

    /** The distractor is in no group, which is why it is wrong wherever he puts it. */
    @Test fun `the distractor belongs to no group`() {
        for (task in tasks.filter { it.distractor != null }) {
            assertNull(task.distractor!!.group)
            assertNull(task.groupOfTile(task.distractor!!.text))
        }
    }

    /**
     * «Φτιάχνω τη βαλίτσα» is the task this was found on: open the case, put four things in in any
     * order, shut it. All **24** of those orders are his own work, and the first cut answered 23 of
     * them with «Σχεδόν.» and a ring round a step he had got right.
     */
    @Test fun `the suitcase accepts all twenty-four of its right orders`() {
        val task = tasks.single { it.id == "suitcase" }
        assertEquals(listOf(1, 2, 2, 2, 2, 3), task.groups)
        val orders = permutations(task.order.subList(1, 5))
        assertEquals(24, orders.size)
        for (order in orders) {
            val his = listOf(task.order.first()) + order + task.order.last()
            assertNull("«${his.joinToString(" → ")}» was refused", firstWrongStep(his, task.groups, task::groupOfTile))
        }
        // And the two ends are still fixed: nothing is packed before the case is open.
        val packFirst = listOf(task.order[1], task.order[0]) + task.order.drop(2)
        assertEquals(0, firstWrongStep(packFirst, task.groups, task::groupOfTile))
        val shutFirst = listOf(task.order.first(), task.order.last()) + task.order.subList(1, 5)
        assertEquals(1, firstWrongStep(shutFirst, task.groups, task::groupOfTile))
    }

    /** «Φτιάχνω καφέ»: the water and the coffee into the briki either way round, then the flame. */
    @Test fun `the coffee accepts both of its right orders`() {
        val task = tasks.single { it.id == "coffee" }
        assertEquals(listOf(1, 1, 2), task.groups)
        assertNull(firstWrongStep(task.order, task.groups, task::groupOfTile))
        val swapped = listOf(task.order[1], task.order[0], task.order[2])
        assertNull("the other order of the first two was refused", firstWrongStep(swapped, task.groups, task::groupOfTile))
        // The flame is still last: it is a group of its own.
        val flameFirst = listOf(task.order[2], task.order[0], task.order[1])
        assertEquals(0, firstWrongStep(flameFirst, task.groups, task::groupOfTile))
    }

    /** The seven tasks whose order is causally forced keep exactly one right answer. */
    @Test fun `a task with no groups still has one order`() {
        val task = tasks.single { it.id == "teeth" }
        assertTrue(task.steps.all { it.group == null })
        assertNull(firstWrongStep(task.order, task.groups, task::groupOfTile))
        assertEquals(0, firstWrongStep(task.order.reversed(), task.groups, task::groupOfTile))
    }

    /** A tile in no group at all — the distractor — is wrong wherever in the strip it lands. */
    @Test fun `the distractor is wrong in every slot`() {
        val task = tasks.single { it.id == "atm" }
        val foreign = task.distractor!!.text
        for (at in task.order.indices) {
            val his = task.order.toMutableList().also { it[at] = foreign }
            assertEquals("«$foreign» passed at ${at + 1}", at, firstWrongStep(his, task.groups, task::groupOfTile))
        }
    }

    // ------------------------------------------------------ the telling, with no judge

    /**
     * What a phone with no judge accepts — which is every phone until a caregiver saves a key.
     *
     * The old check was a bag of words: a telling with a whole step missing scored 8 of 12 and passed,
     * and the same words spoken backwards scored 12 of 12. Both are refused now, and the leniency his
     * telegraphic speech needs is untouched — the connectors, the small words and the exact wording are
     * all still free.
     */
    @Test fun `a telling has to name every step, in group order`() {
        val coffee = tasks.single { it.id == "coffee" }
        assertTrue("the telling itself", StepTasks.toldInOrder(coffee, coffee.telling))
        assertTrue(
            "the words he would really say",
            StepTasks.toldInOrder(coffee, "πρώτα νερό στο μπρίκι, μετά καφέ και ζάχαρη, τέλος φωτιά"),
        )
        assertFalse(
            "a step he never said",
            StepTasks.toldInOrder(coffee, "βάζω νερό στο μπρίκι, ρίχνω καφέ και ζάχαρη"),
        )
        assertFalse("nothing said at all", StepTasks.toldInOrder(coffee, ""))
        assertFalse("another sentence altogether", StepTasks.toldInOrder(coffee, "θέλω έναν καφέ"))
    }

    /** Backwards is the case a bag of words could never see: the same words, in the wrong sequence. */
    @Test fun `a telling said backwards is refused`() {
        val teeth = tasks.single { it.id == "teeth" }
        assertTrue(StepTasks.toldInOrder(teeth, teeth.telling))
        assertFalse(StepTasks.toldInOrder(teeth, StepTask.tellingOf(teeth.order.reversed())))
    }

    /** Inside a group there is no order to get wrong — that is what a group is. */
    @Test fun `a telling may say a group's own steps either way round`() {
        val coffee = tasks.single { it.id == "coffee" }
        val swapped = listOf(coffee.order[1], coffee.order[0], coffee.order[2])
        assertTrue(StepTasks.toldInOrder(coffee, StepTask.tellingOf(swapped)))
        // But the group after them still has to come after them.
        val flameFirst = listOf(coffee.order[2], coffee.order[0], coffee.order[1])
        assertFalse(StepTasks.toldInOrder(coffee, StepTask.tellingOf(flameFirst)))
    }

    /** Every task's own telling passes its own check. A seed that fails this is unanswerable. */
    @Test fun `every task accepts the telling the app reads out`() {
        for (task in tasks) {
            assertTrue("«${task.id}» refuses its own telling", StepTasks.toldInOrder(task, task.telling))
        }
    }

    /**
     * And every task can be told in *its* other right orders too: the groups mean the same thing on
     * both stages, or a man who ordered the suitcase his own way would be refused for telling it that
     * way.
     */
    @Test fun `a task that accepts an order accepts the telling of it`() {
        val suitcase = tasks.single { it.id == "suitcase" }
        for (order in permutations(suitcase.order.subList(1, 5))) {
            val his = listOf(suitcase.order.first()) + order + suitcase.order.last()
            assertTrue("«${his.joinToString(" → ")}» was refused", StepTasks.toldInOrder(suitcase, StepTask.tellingOf(his)))
        }
    }

    // --------------------------------------------------------------- what a dot asks for

    /** Cumulative: dot n is every task of difficulty n and below — see [Difficulty.stepsTier]. */
    @Test fun `a dot admits every task at or below it`() {
        assertEquals(4, seed.forDifficulty(1).size)
        assertEquals(8, seed.forDifficulty(2).size)
        assertEquals(20, seed.forDifficulty(5).size)
        for (d in 1..5) {
            assertTrue("dot $d admits something harder", seed.forDifficulty(d).all { it.difficulty <= d })
        }
    }

    /** A sitting is as long as it promised, drawn without repeats while there are tasks to draw from. */
    @Test fun `a sitting is four tasks the dot allows, without repeats`() {
        val sitting = seed.plan(StepsModule.TASKS_PER_SESSION, dots = 1, random = Random(7))
        assertEquals(4, sitting.size)
        assertEquals("dot 1 has exactly four tasks and a sitting must not repeat one", 4, sitting.distinctBy { it.id }.size)
        assertTrue(sitting.all { it.difficulty == 1 })

        val harder = seed.plan(StepsModule.TASKS_PER_SESSION, dots = 5, random = Random(7))
        assertEquals(4, harder.size)
        assertEquals(4, harder.distinctBy { it.id }.size)
    }

    /**
     * **The one task of a mixed sitting is the difficulty he set**, whenever the dot has one.
     *
     * A mixed sitting buys one task ([StepsModule.ITEMS_PER_TASK] against a budget of three), and the
     * plan used to be a flat shuffle of everything the dot admits — so at dot 5 that one task was a
     * six-step-plus-distractor board four times in twenty and «Φτιάχνω καφέ» the rest of the time.
     * Every seed in the drawer, not one lucky one: a dot whose own difficulty has any task at all
     * must spend the single task on it.
     */
    @Test fun `a one-task sitting is a task of the dot's own difficulty`() {
        for (dots in 1..5) {
            val own = seed.forDifficulty(dots).filter { it.difficulty == dots }
            assertTrue("dot $dots has no task of its own in the seed", own.isNotEmpty())
            for (seedValue in 1..40) {
                val one = seed.plan(count = 1, dots = dots, random = Random(seedValue.toLong())).single()
                assertEquals("dot $dots, draw $seedValue: «${one.id}» is difficulty ${one.difficulty}", dots, one.difficulty)
            }
        }
        // And dot 5's single task really is the hardest board there is: six steps and a foreign tile.
        val five = seed.plan(count = 1, dots = 5, random = Random(11)).single()
        assertEquals(StepTasks.MAX_STEPS, five.steps.size)
        assertNotNull("dot 5 is the distractor board", five.distractor)
    }

    /**
     * A four-task sitting keeps the easy end the cumulative pool is for: half at the dot (rounded up),
     * the rest from under it — never four of the hardest thing the app has.
     */
    @Test fun `a four-task sitting is half at the dot and half under it`() {
        for (seedValue in 1..20) {
            val sitting = seed.plan(StepsModule.TASKS_PER_SESSION, dots = 5, random = Random(seedValue.toLong()))
            assertEquals(4, sitting.size)
            assertEquals("draw $seedValue: $sitting", 2, sitting.count { it.difficulty == 5 })
            assertTrue("draw $seedValue: nothing easier in it", sitting.any { it.difficulty < 5 })
        }
    }

    /** The board is the steps and the distractor, and nothing else ever reaches the screen. */
    @Test fun `the board carries the distractor and the steps`() {
        val five = tasks.first { it.difficulty == 5 }
        val board = five.board(Random(3))
        assertEquals(five.steps.size + 1, board.size)
        assertTrue(board.map { it.text }.containsAll(five.order))
        assertTrue(five.distractor!!.text in board.map { it.text })

        val easy = tasks.first { it.difficulty == 1 }
        assertEquals(easy.steps.size, easy.board(Random(3)).size)
    }

    // ------------------------------------------------------------- a seed it cannot use

    @Test fun `a seed that is not readable is no tasks rather than a crash`() {
        assertEquals(emptyList<StepTask>(), StepTasks.parse("").tasks)
        assertEquals(emptyList<StepTask>(), StepTasks.parse("{").tasks)
        assertEquals(emptyList<StepTask>(), StepTasks.parse("""{"version":1}""").tasks)
        assertEquals(emptyList<StepTask>(), StepTasks.parse("""{"version":1,"tasks":[{"id":"x"}]}""").tasks)
    }

    /**
     * A hand edit that would make an unanswerable exercise is dropped, not shown: too few steps, a step
     * with no words, a repeated step, and a distractor that is one of the task's own steps.
     */
    @Test fun `a task the module could not run is left out`() {
        val twoSteps = """{"version":1,"tasks":[{"id":"x","title":"Δύο","difficulty":1,
            "steps":[{"text":"Ένα"},{"text":"Δύο"}]}]}"""
        assertEquals(emptyList<StepTask>(), StepTasks.parse(twoSteps).tasks)

        val repeated = """{"version":1,"tasks":[{"id":"x","title":"Ίδια","difficulty":1,
            "steps":[{"text":"Ένα"},{"text":"Ένα"},{"text":"Δύο"}]}]}"""
        assertEquals(emptyList<StepTask>(), StepTasks.parse(repeated).tasks)

        val ownStep = """{"version":1,"tasks":[{"id":"x","title":"Καλή","difficulty":5,
            "steps":[{"text":"Ένα"},{"text":"Δύο"},{"text":"Τρία"}],"distractor":{"text":"Δύο"}}]}"""
        val kept = StepTasks.parse(ownStep).tasks.single()
        assertNull("a distractor that is one of the task's own steps is not a distractor", kept.distractor)
        assertEquals(listOf("Ένα", "Δύο", "Τρία"), kept.order)
    }

    /** Every permutation of [xs]. Only ever asked for four things: 24 orders. */
    private fun permutations(xs: List<String>): List<List<String>> =
        if (xs.size <= 1) listOf(xs)
        else xs.flatMap { head -> permutations(xs - head).map { listOf(head) + it } }

    /** The words of a phrase that carry its meaning, as [SpeechMatch] keys. */
    private fun contentWords(text: String): List<String> =
        SpeechMatch.key(text).split(' ').filter { it.isNotBlank() && it !in SMALL_WORDS }

    private companion object {
        /** Six words: three tiles sit side by side on a phone, and a tile is one look. */
        const val MAX_WORDS = 6

        /**
         * Which task each dot-5 distractor is borrowed from — decided by a person, checked here.
         *
         * The heuristic above says a pairing is not *obviously* wrong; this says it is the one somebody
         * chose. Together they stop a later edit from quietly putting a plausible step back on a board
         * where he cannot reason his way to the answer.
         */
        val BORROWED = mapOf(
            "atm" to "teeth",
            "dishes" to "laundry",
            "bed" to "pasta",
            "cafe" to "bus",
        )

        /**
         * The exact step each dot-5 board borrows, because [BORROWED] cannot see the one reversion
         * that matters: `dishes` takes a step of `laundry` either way, and the rejected «Ρίχνω
         * απορρυπαντικό» shares no content word with «Πλένω τα πιάτα» — so only the text itself says
         * that the washing-up board asks him about the laundry basket and not about washing-up liquid.
         */
        val EXPECTED_DISTRACTOR = mapOf(
            "atm" to "Βουρτσίζω τα δόντια μου",
            "dishes" to "Βάζω τα ρούχα μέσα",
            "bed" to "Ρίχνω τα μακαρόνια",
            "cafe" to "Βγάζω εισιτήριο",
        )

        /** Articles, prepositions and the possessive: shared by everything, so they say nothing. */
        val SMALL_WORDS = setOf(
            "ο", "η", "το", "τον", "την", "τη", "οι", "τα", "τους", "τις", "των", "του", "της",
            "ενα", "εναν", "μια", "και", "με", "σε", "στο", "στον", "στην", "στη", "στα", "στις",
            "απο", "για", "να", "θα", "μου", "τι", "που",
        )

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
