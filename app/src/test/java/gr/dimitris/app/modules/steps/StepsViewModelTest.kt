package gr.dimitris.app.modules.steps

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.LocalJudge
import gr.dimitris.app.core.judge.Source
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.core.speech.GentleCheck
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The rules [StepsViewModel] runs on, argued with in a second rather than on a phone.
 *
 * Everything here is free of the ViewModel on purpose: an [gr.dimitris.app.AppGraph] needs a `Context`
 * and cannot be built on the JVM, so the judgements that decide what happens to him — *is that the
 * order*, *which tile is wrong*, *did he tell them*, *what does this count as*, *what goes in the row*
 * — live as functions and as [TellCheck] beside it. `StepsFlowTest` proves the wiring on a device;
 * what is pinned here is what the wiring is for.
 */
class StepsViewModelTest {

    private val task = StepTask(
        id = "coffee",
        title = "Φτιάχνω καφέ",
        difficulty = 1,
        steps = listOf(
            Step("Βάζω νερό στο μπρίκι", en = "water", image = "steps/1.png"),
            Step("Ρίχνω καφέ και ζάχαρη", en = "coffee", image = "steps/2.png"),
            Step("Το βάζω στη φωτιά", en = "stove", image = "steps/3.png"),
        ),
        distractor = Step("Βγάζω εισιτήριο", en = "ticket", image = "steps/4.png"),
    )
    private val order = task.order

    /** The same task, with its first two steps a group: either of them may go down first. */
    private val grouped = task.copy(
        steps = task.steps.mapIndexed { i, step -> step.copy(group = if (i < 2) 1 else 2) },
    )

    private fun wrongIn(task: StepTask, chosen: List<String>): Int? =
        firstWrongStep(chosen, task.groups, task::groupOfTile)
    private val gson = Gson()

    // ------------------------------------------------------------- is that the order

    @Test fun `the order he was asked for is the order, and nothing is marked`() {
        assertNull(wrongIn(task, order))
    }

    /**
     * One tile marked and only one: the *first* that is out of place. A man who put step 3 where step 2
     * goes has everything after it wrong as a consequence, and a strip of red says "you got it all
     * wrong", which is both untrue and the one thing this app may never tell him.
     */
    @Test fun `a wrong order marks the first step that is out of place and no other`() {
        assertEquals(0, wrongIn(task, order.reversed()))
        // The first step right, the last two swapped: the mark is on position 2, not on 2 and 3.
        assertEquals(1, wrongIn(task, listOf(order[0], order[2], order[1])))
        // And the one that is right stays unmarked, which is what makes the mark readable.
        assertEquals(2, wrongIn(task, listOf(order[0], order[1], "κάτι άλλο")))
    }

    /** The distractor is wrong wherever he puts it: it is not a step of this task at all. */
    @Test fun `a step from another task is wrong wherever it goes`() {
        val wrong = task.distractor!!.text
        assertEquals(0, wrongIn(task, listOf(wrong, order[0], order[1])))
        assertEquals(2, wrongIn(task, listOf(order[0], order[1], wrong)))
    }

    /** A strip he has not finished is not wrong for being unfinished: only what he laid is compared. */
    @Test fun `half an order is not a wrong order`() {
        assertNull(wrongIn(task, emptyList()))
        assertNull(wrongIn(task, order.take(2)))
        assertEquals(1, wrongIn(task, listOf(order[0], order[2])))
    }

    /**
     * A group is a run of steps whose internal order is his to choose. Thirteen of the twenty tasks
     * have one, because thirteen of them have more than one right answer — and the first cut of this
     * check accepted exactly one permutation and ringed a correct step in all the others.
     */
    @Test fun `steps that share a group may go down either way round`() {
        assertEquals(listOf(1, 1, 2), grouped.groups)
        assertNull(wrongIn(grouped, grouped.order))
        assertNull(
            "the group's own two, the other way round",
            wrongIn(grouped, listOf(order[1], order[0], order[2])),
        )
        // The group after them still comes after them, and the group before still comes before.
        assertEquals(0, wrongIn(grouped, listOf(order[2], order[0], order[1])))
        assertEquals(1, wrongIn(grouped, listOf(order[0], order[2], order[1])))
        // And a tile in no group at all is wrong wherever it goes, group or no group.
        assertEquals(0, wrongIn(grouped, listOf(grouped.distractor!!.text, order[0], order[1])))
    }

    /** A step with no group of its own is its own place, and two of them are never interchangeable. */
    @Test fun `an ungrouped step matches only its own position`() {
        assertEquals(listOf(-1, -2, -3), task.groups)
        assertNull(task.groupOfTile("κάτι άλλο"))
        assertEquals(-2, task.groupOfTile(order[1]))
    }

    // --------------------------------------------------------------- moving one tile

    /**
     * What the mark promises: one tile moved, not the tail re-laid.
     *
     * Correct A B C D, he lays A C D B. The mark lands on C. Taking C out used to give him A D B with
     * everything shifted up, so the only way to get B into position 2 was to take D and B out as well
     * and lay three tiles again — five, on a six-step task. Now the slot stays open where the mark is
     * and the next tile he taps drops into it.
     */
    @Test fun `the marked slot stays open and the next tile drops into it`() {
        val a = Step("Α"); val b = Step("Β"); val c = Step("Γ"); val d = Step("Δ")
        val his = listOf(a, c, d, b)
        val slot = 1

        val (afterC, markC) = removedFrom(his, c, slot)
        assertEquals(listOf(a, d, b), afterC)
        assertEquals("the mark is the slot he is filling", 1, markC)

        val (afterB, markB) = removedFrom(afterC, b, markC)
        assertEquals(listOf(a, d), afterB)
        assertEquals("taking a tile from below the slot leaves the slot where it was", 1, markB)

        assertEquals(listOf(a, b, d), insertedAt(afterB, b, markB))
        // And the tile after that goes at the end, because filling the slot answers the mark.
        assertEquals(listOf(a, b, d, c), insertedAt(insertedAt(afterB, b, markB), c, null))
    }

    /** A tile taken out from *above* the slot takes the slot up with it: it is a place, not an index. */
    @Test fun `the slot moves up with the tiles above it`() {
        val a = Step("Α"); val b = Step("Β"); val c = Step("Γ")
        val (left, mark) = removedFrom(listOf(a, b, c), a, 2)
        assertEquals(listOf(b, c), left)
        assertEquals(1, mark)
        assertEquals(listOf(b, a, c), insertedAt(left, a, mark))
    }

    /** With nothing marked a tile goes at the end, which is every tap of an untouched board. */
    @Test fun `with no mark a tile goes at the end`() {
        val a = Step("Α"); val b = Step("Β")
        assertEquals(listOf(a, b), insertedAt(listOf(a), b, null))
        assertEquals(listOf(a, b), insertedAt(listOf(a), b, 7))
        val (left, mark) = removedFrom(listOf(a, b), a, null)
        assertEquals(listOf(b), left)
        assertNull(mark)
        // A tile that is not in the strip at all changes nothing.
        assertEquals(listOf(a, b) to null, removedFrom(listOf(a, b), Step("Γ"), null))
    }

    // ----------------------------------------------------------- what a sitting is worth

    /**
     * A task is two exercises and leaves two rows, so it costs the session two items. The first cut
     * planned one, and every sitting with «Βήματα» in it closed with `completedItemCount` at twice
     * `plannedItemCount`.
     */
    @Test fun `a task is worth two of the session's items`() {
        assertEquals(2, StepsModule.ITEMS_PER_TASK)
        assertEquals(4, StepsModule.tasksFor(StepsModule.TASKS_PER_SESSION * StepsModule.ITEMS_PER_TASK))
        assertEquals(2, StepsModule.tasksFor(4))
        // An odd budget — `SessionBudget.MIN_PER_MODULE` is three — rounds down rather than promising
        // an exercise it will not run.
        assertEquals(1, StepsModule.tasksFor(3))
        // And a module the session opened at all owes it one task, whatever it was handed.
        assertEquals(1, StepsModule.tasksFor(1))
        assertEquals(1, StepsModule.tasksFor(0))
    }

    // ------------------------------------------------------------ what counts as what

    /**
     * Found it himself is his own; found it after a miss, or with the answer on the screen, is
     * assisted; passed on is passed on. There is no fourth outcome — the ordering has unlimited
     * retries and the telling ends in «Το είπα!», so nothing here can end with him having failed.
     */
    @Test fun `a miss is assisted work and never a failure`() {
        assertEquals(Outcome.CORRECT, stepsOutcome(firstTry = true, skipped = false))
        assertEquals(Outcome.ASSISTED, stepsOutcome(firstTry = false, skipped = false))
        assertEquals(Outcome.SKIPPED, stepsOutcome(firstTry = false, skipped = true))
        // A skip is a skip even where nothing had gone wrong yet: he passed on the exercise.
        assertEquals(Outcome.SKIPPED, stepsOutcome(firstTry = true, skipped = true))
    }

    // ------------------------------------------------------------------ what is written

    @Test fun `the ordering row says the task, his own order and how many goes it took`() {
        val his = listOf(order[0], order[2], order[1])
        val detail = stepsDetail(task, steps = his, tries = 1, ms = 4_200)
        val read: Map<String, Any?> = gson.fromJson(detail, object : TypeToken<Map<String, Any?>>() {}.type)
        assertEquals(
            "the keys, in the order they were agreed",
            listOf("task", "steps", "difficulty", "tries", "ms"),
            read.keys.toList(),
        )
        assertEquals(task.title, read["task"])
        assertEquals("his order, tile by tile: where the wrong step went is the whole of what went wrong", his, read["steps"])
        assertEquals(1.0, read["difficulty"])
        assertEquals(1.0, read["tries"])
        assertEquals(4_200.0, read["ms"])
    }

    /** The judge's verdict rides along on the telling row, in the shape every module writes it in. */
    @Test fun `the telling row carries what the judge decided`() {
        val verdict = Verdict(accept = true, expanded = null, feedback = null, score = 1f, source = Source.JUDGE)
        val detail = stepsDetail(task, steps = order, tries = 0, ms = 900, judge = verdict.detail(ms = 120))
        val read: Map<String, Any?> = gson.fromJson(detail, object : TypeToken<Map<String, Any?>>() {}.type)
        assertEquals(listOf("task", "steps", "difficulty", "tries", "judge", "ms"), read.keys.toList())
        @Suppress("UNCHECKED_CAST")
        val judge = read["judge"] as Map<String, Any?>
        assertEquals(Source.JUDGE.name, judge["source"])
        assertEquals(true, judge["accept"])
        // A row the phone decided itself carries no judge key at all, rather than an empty object.
        val alone: Map<String, Any?> = gson.fromJson(
            stepsDetail(task, steps = order, tries = 0, ms = 900),
            object : TypeToken<Map<String, Any?>>() {}.type,
        )
        assertFalse(alone.containsKey("judge"))
    }

    /** Nothing in a row is ever an id or a path, and an empty strip is written as absence. See `Adapt`. */
    @Test fun `a skip with nothing in the strip writes no steps at all`() {
        val read: Map<String, Any?> = gson.fromJson(
            stepsDetail(task, steps = emptyList(), tries = 0, ms = 0),
            object : TypeToken<Map<String, Any?>>() {}.type,
        )
        assertFalse(read.containsKey("steps"))
        assertEquals(task.title, read["task"])
    }

    // ---------------------------------------------------------------- did he tell them

    private val asked = mutableListOf<Ask>()
    private val verdicts = ArrayDeque<Verdict>()
    private var judged = true
    private var dots = 3
    private var judgeThrows = false

    private fun check() = TellCheck(
        judged = { judged },
        difficulty = { dots },
        askJudge = { ask ->
            asked += ask
            if (judgeThrows) throw IOException("offline")
            verdicts.removeFirst()
        },
    )

    private fun accepted(feedback: String? = null) =
        Verdict(accept = true, expanded = null, feedback = feedback, score = 1f, source = Source.JUDGE)

    private fun refused(feedback: String? = null) =
        Verdict(accept = false, expanded = null, feedback = feedback, score = 0f, source = Source.JUDGE)

    @Test fun `a telling the judge accepts is the task done, and nothing is said to him`() = runTest {
        verdicts += accepted()
        val told = check().weigh("πρώτα βάζω νερό μετά καφέ και τέλος στη φωτιά", task)

        assertTrue(told.accepted)
        assertNull("a telling that counted needs nothing repeated", told.whole)
        assertEquals(0, told.tries)
        assertTrue(told.canConfirm)
        // What went up: the task as the question, the telling as the answer, and what a good one has
        // to do. Nothing about him beyond the words he just said.
        val ask = asked.single()
        assertEquals("one right order, so it is a sentence and not an open answer", Kind.SENTENCE, ask.kind)
        assertEquals(task.title, ask.prompt)
        assertEquals(task.telling, ask.target)
        assertEquals(TellCheck.INTENT, ask.intent)
        assertEquals("πρώτα βάζω νερό μετά καφέ και τέλος στη φωτιά", ask.heard)
        assertEquals("his own dot row decides how much grammar is asked of him", 3, ask.difficulty)
        assertEquals(Source.JUDGE.name, told.judge["source"])
        assertEquals(true, told.judge["accept"])
    }

    /**
     * A telling that did not land is never a wall: the whole telling comes back, **once**, so his next
     * «Μίλα» has a sentence to repeat — and the second miss does not spend it again, because it is
     * already on the screen.
     */
    @Test fun `a telling that did not land is given the whole thing once, and then the confirm`() = runTest {
        val check = check()
        verdicts += refused(feedback = "Πάμε ξανά, σιγά σιγά.")
        val first = check.weigh("καφές", task)

        assertFalse(first.accepted)
        assertEquals("the telling, for him to repeat", task.telling, first.whole)
        assertEquals("Πάμε ξανά, σιγά σιγά.", first.feedback)
        assertTrue("one miss is a nudge and nothing else changes", first.nudging)
        assertFalse("one go is not two", first.canConfirm)

        verdicts += refused()
        val second = check.weigh("καφές ζάχαρη", task)
        assertFalse(second.accepted)
        assertNull("said once, not on every miss", second.whole)
        assertFalse(second.nudging)
        assertTrue("after two goes «Το είπα!» is his", second.canConfirm)
        assertEquals(GentleCheck.TRIES_BEFORE_CONFIRM, second.tries)
    }

    /**
     * A window that heard nothing costs him no *try* — the phone did not disagree with him, it did not
     * hear him — and it still counts as one of his two goes, so a recogniser that never hears him
     * cannot hold the confirm shut. He is given the telling anyway: a man who was not heard is owed
     * the model as much as one who was (spec §12).
     */
    @Test fun `a window that heard nothing costs him no try and still opens the confirm`() = runTest {
        val check = check()
        val first = check.weigh(null, task)
        assertFalse(first.accepted)
        assertEquals(0, first.tries)
        assertEquals(task.telling, first.whole)
        assertFalse(first.canConfirm)
        assertTrue("no judge is asked about an empty string", asked.isEmpty())

        val second = check.weigh(null, task)
        assertEquals(0, second.tries)
        assertTrue(second.canConfirm)
    }

    /**
     * With the judge off the comparison is the steps' own words, leniently — «πρώτα νερό, μετά καφές,
     * τέλος φωτιά» is the man doing the exercise. The connectors are **not** insisted on: they are what
     * the judge is asked about, and a local matcher that demanded them would refuse every telling on a
     * phone with no key.
     */
    @Test fun `with the judge off it is the steps that are compared, not the connectors`() = runTest {
        judged = false
        val told = check().weigh("βάζω νερό στο μπρίκι ρίχνω καφέ και ζάχαρη το βάζω στη φωτιά", task)
        assertTrue(told.accepted)
        assertTrue("no judge was asked", asked.isEmpty())
        assertTrue("and nothing it said is written down", told.judge.isEmpty())

        judged = false
        val other = check().weigh("θέλω έναν καφέ", task)
        assertFalse("a different sentence is not the steps", other.accepted)
        assertEquals("and he still gets the telling to repeat", task.telling, other.whole)
    }

    /**
     * And with the judge off the **order** is what is checked, which is the whole subject of the
     * module. The bag-of-words check this replaced passed a telling with a step missing (8 of 12
     * words) and the same telling said backwards (12 of 12).
     */
    @Test fun `with the judge off a telling out of order is refused`() = runTest {
        judged = false
        val backwards = check().weigh("το βάζω στη φωτιά, ρίχνω καφέ και ζάχαρη, βάζω νερό στο μπρίκι", task)
        assertFalse("the same words, in the wrong sequence", backwards.accepted)
        assertEquals(task.telling, backwards.whole)

        judged = false
        val missing = check().weigh("βάζω νερό στο μπρίκι, ρίχνω καφέ και ζάχαρη", task)
        assertFalse("a step he never said", missing.accepted)
    }

    /**
     * A judge that was on and could not be reached lands in exactly that same place. Without this, a
     * phone on a bus with no signal answered «Μπράβο» to every sound he made and wrote a CORRECT row
     * for each of them — worse for him than having the judge switched off.
     */
    @Test fun `a judge that could not be reached falls back to the comparison, not to yes`() = runTest {
        verdicts += LocalJudge.judge(Ask(kind = Kind.SENTENCE, target = task.telling, heard = "ό,τι να 'ναι"))
        val told = check().weigh("ό,τι να 'ναι", task)
        assertFalse("a fallback verdict is not a judgement", told.accepted)
        assertTrue("nothing a fallback wrote goes in the row", told.judge.isEmpty())
        assertEquals(task.telling, told.whole)
        assertNotNull("the judge really was asked", asked.singleOrNull())
    }

    /** And so does a judge that threw, which its own contract says can never happen. */
    @Test fun `a judge that threw does not take the task down with it`() = runTest {
        judgeThrows = true
        val told = check().weigh("νερό στο μπρίκι, καφέ και ζάχαρη, στη φωτιά", task)
        assertTrue("the steps were said in order, so the telling counts", told.accepted)
        assertTrue(told.judge.isEmpty())
    }
}
