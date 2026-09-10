package gr.dimitris.app.modules.steps

import gr.dimitris.app.core.difficulty.Difficulty
import org.junit.Assert.assertEquals
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

    /**
     * What a phone with no judge compares him against: the steps' own words, with no connectors in
     * them. A local matcher that insisted on «πρώτα» would refuse every telling on a phone with no key.
     */
    @Test fun `the local target is the steps and not the telling`() {
        val coffee = tasks.single { it.id == "coffee" }
        assertEquals(coffee.joined, StepTasks.localTarget(coffee))
        assertTrue(StepTask.FIRST !in StepTasks.localTarget(coffee))
        for (step in coffee.steps) assertTrue(step.text in StepTasks.localTarget(coffee))
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

    private companion object {
        /** Six words: three tiles sit side by side on a phone, and a tile is one look. */
        const val MAX_WORDS = 6

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
