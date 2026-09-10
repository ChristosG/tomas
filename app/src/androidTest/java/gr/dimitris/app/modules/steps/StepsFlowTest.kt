package gr.dimitris.app.modules.steps

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.judge.JudgeClient
import gr.dimitris.app.core.judge.TurnJudge
import gr.dimitris.app.core.speech.FakeSpeechToText
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.today.MODULE_GRID_TAG
import gr.dimitris.app.ui.components.LISTEN_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The things about «Βήματα» that only a device can answer: that the steps of a real bundled task reach
 * the board, that the order he taps into the strip is read as his own work, and that the telling after
 * it really goes to the judge and comes back as a second row.
 *
 * The rules themselves are argued with on the JVM (`StepsViewModelTest`) and the seed is checked there
 * too (`StepTasksTest`). What is left for a flow test is the wiring: the seed reaches the tiles, the
 * tiles reach the strip, the microphone reaches [TellCheck], and the two rows that come out of one task
 * say what he did.
 *
 * Dot 1 throughout, which is the three-step tasks: three tiles is the only board a test can name the
 * right order of without re-implementing the module.
 */
class StepsFlowTest {
    @get:Rule(order = 0) val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private var dotsBefore = 0
    private var since = 0L

    /** The recogniser the telling runs against; the real one does not exist on an emulator. */
    private val fake = FakeSpeechToText()
    private var realStt: SpeechToText? = null

    /** The judge it runs against: the emulator can reach the Anthropic API no more than a microphone. */
    private var realJudge: TurnJudge? = null
    private val replies = ArrayDeque<String>()

    @Before fun startAtDotOne() = runBlocking<Unit> {
        dotsBefore = graph.settings.difficulty(ModuleId.STEPS).first()
        graph.settings.setDifficulty(ModuleId.STEPS, 1)
        since = System.currentTimeMillis()
    }

    /** His difficulty is his, and so are his recogniser and his judge: a test that borrows them puts them back. */
    @After fun putItBack() = runBlocking<Unit> {
        graph.settings.setDifficulty(ModuleId.STEPS, dotsBefore)
        graph.settings.setSttEnabled(false)
        realStt?.let { graph.stt = it }
        realJudge?.let { graph.judge = it }
    }

    /** Recognition on, with a recogniser that hears whatever the case says it hears. */
    private fun withRecognition(): FakeSpeechToText {
        realStt = graph.stt
        runBlocking { graph.settings.setSttEnabled(true) }
        graph.stt = fake
        return fake
    }

    /**
     * A judge that answers with canned verdicts, one per window, with a key behind it so that
     * [TurnJudge.available] says yes. The secret store is not touched: the key is the judge's own,
     * which is what the seam is for.
     */
    private fun withJudge(vararg json: String) {
        realJudge = graph.judge
        replies.clear()
        replies += json
        graph.judge = TurnJudge(
            secrets = { KEY },
            enabled = { true },
            client = JudgeClient { _, _, _ -> replies.removeFirstOrNull() },
        )
    }

    /**
     * One whole task: the three steps into the strip in order, and then the telling.
     *
     * Two rows out of one task, which is the shape every reader of this module's data depends on:
     * `steps:order:<task>` for the sequencing and `steps:tell:<task>` for the telling, both of them his
     * own work because he did each of them at the first go.
     */
    @Test fun theOrderIsHisOwnWorkAndSoIsTheTellingAfterIt() {
        withRecognition().willHear("πρώτα βάζω νερό στο μπρίκι μετά ρίχνω καφέ και ζάχαρη τέλος το βάζω στη φωτιά")
        withJudge(ACCEPTED)
        val task = openTask()

        // The bottom block is the three «docs/UX.md» allows, in the order it says: the green primary,
        // «Άκου» under it, «Παράλειψη» at the foot — and «Έτοιμο» is dead until the strip is full.
        assertThreeActionsInTheBottom(READY)

        tapInOrder(task.order)
        assertStrip("the strip is not what he tapped", task.order)
        compose.onNodeWithText(READY).performClick()

        // Stage 2, on the same screen, with the strip he built still on it.
        compose.waitUntil(TIMEOUT_MS) { rows().any { it.itemId == "${StepsViewModel.ORDER_ITEM}${task.id}" } }
        val ordering = rows().single()
        assertEquals("an order he found himself", Outcome.CORRECT, ordering.outcome)
        assertEquals(ModuleId.STEPS, ordering.module)
        assertTrue("the row does not carry the task: ${ordering.detail}", ordering.detail.contains("\"task\""))
        assertTrue("nor what he put down: ${ordering.detail}", ordering.detail.contains("\"steps\""))
        compose.waitUntil(TIMEOUT_MS) { onScreen(StepsViewModel.TELL_THEM) }
        compose.onNodeWithText(StepsViewModel.TELL_THEM).assertIsDisplayed()

        // «Μίλα» — the window, the judge, and the end of the task.
        compose.onNodeWithText(GentleCheck.SPEAK).performClick()
        compose.waitUntil(TIMEOUT_MS) { rows().any { it.itemId == "${StepsViewModel.TELL_ITEM}${task.id}" } }
        val telling = rows().single { it.itemId == "${StepsViewModel.TELL_ITEM}${task.id}" }
        assertEquals("a telling he gave himself", Outcome.CORRECT, telling.outcome)
        assertTrue("the judge's verdict belongs in the row: ${telling.detail}", telling.detail.contains("\"judge\""))
        assertTrue("and what it decided: ${telling.detail}", telling.detail.contains("\"accept\":true"))
        assertEquals("one task, two rows", 2, rows().size)
        // And the sitting moves on by itself: no fourth button to press.
        compose.waitUntil(TIMEOUT_MS) { onScreen("${StepsModule.titleGreek} 2/") }
    }

    /**
     * A wrong order costs him the mark and nothing else — and the mark is **one tile to move**, not the
     * tail to lay again.
     *
     * The two taps this drives are the whole of that promise: he takes the misplaced step out of the
     * strip, the hole stays open where the mark is, and the next tile he taps drops into it. Before the
     * slot existed, taking a tile out shifted everything up and the only way to put a step back in the
     * middle was to dismantle the rest of the strip — five tiles, on a six-step task.
     */
    @Test fun aWrongOrderIsOneTileToMove() {
        val task = openTask()
        // Right first step, last two swapped: the mark lands on slot 2, and the step that belongs
        // there is the one he put last.
        val wrong = listOf(task.order[0], task.order[2], task.order[1])

        tapInOrder(wrong)
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { onScreen(StepsViewModel.ALMOST) }
        assertTrue("a miss was written down as an attempt", rows().isEmpty())
        assertStrip("the strip was taken away from him", wrong)
        // Still stage 1: nothing has moved on.
        compose.onNodeWithText(StepsViewModel.PUT_IN_ORDER).assertIsDisplayed()

        // One tile out — the step that belongs in the marked slot — and the hole stays open there.
        tapChosen(task.order[1])
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(STEP_SLOT_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertStrip("the rest of the strip was disturbed", listOf(task.order[0], task.order[2]), slotAt = 1)

        // And one tile in: it lands in the slot, not at the end.
        tapBoard(task.order[1])
        assertStrip("the tile did not land in the marked slot", task.order)
        assertTrue("the slot outlived the tile that filled it", compose.onAllNodesWithTag(STEP_SLOT_TAG).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { rows().isNotEmpty() }
        val ordering = rows().single()
        assertEquals("an order he found after a miss is assisted work", Outcome.ASSISTED, ordering.outcome)
        assertTrue("the row does not say how many goes: ${ordering.detail}", ordering.detail.contains("\"tries\":1"))
    }

    /**
     * A telling the judge did not accept: the whole telling comes back for him to repeat, and after two
     * goes «Το είπα!» is his — the phase-11 window rule, unchanged. It is assisted work, because the
     * phone read him the answer before he said it.
     */
    @Test fun aTellingThatDidNotLandIsGivenTheWholeThingAndThenTheConfirm() {
        withRecognition().willHear("καφές").willHear("καφές ζάχαρη")
        withJudge(REFUSED, REFUSED)
        val task = openTask()

        tapInOrder(task.order)
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { onScreen(StepsViewModel.TELL_THEM) }

        compose.onNodeWithText(GentleCheck.SPEAK).performClick()
        // The telling, on the screen and said once, so his next go has a sentence to repeat.
        compose.waitUntil(TIMEOUT_MS) { onScreen(task.telling) }
        compose.waitUntil(TIMEOUT_MS) { onScreen(GentleCheck.TRY_AGAIN) }

        compose.onNodeWithText(GentleCheck.SPEAK).performClick()
        compose.waitUntil(TIMEOUT_MS) { onScreen(SAID_IT) }
        compose.onNodeWithText(SAID_IT).performClick()

        compose.waitUntil(TIMEOUT_MS) { rows().any { it.itemId == "${StepsViewModel.TELL_ITEM}${task.id}" } }
        val telling = rows().single { it.itemId == "${StepsViewModel.TELL_ITEM}${task.id}" }
        assertEquals("a telling he was read first is assisted work", Outcome.ASSISTED, telling.outcome)
        assertTrue("the row does not say he was read the telling: ${telling.cueLevel}", (telling.cueLevel ?: 0) >= 3)
        assertTrue("nor how many goes the phone disagreed: ${telling.detail}", telling.detail.contains("\"tries\":2"))
    }

    // ------------------------------------------------------------------------- helpers

    /** Opens free practice and returns the three-step task whose tiles are on the board. */
    private fun openTask(): StepTask {
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(StepsModule.titleGreek))
        compose.onNodeWithText(StepsModule.titleGreek).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(STEP_TILE_TAG).fetchSemanticsNodes().isNotEmpty() }
        val tiles = textsOf(STEP_TILE_TAG)
        val seed = runBlocking { StepTasks.load(graph.app.assets) }
        return seed.tasks.single { it.order.toSet() == tiles.toSet() }
    }

    /**
     * Taps the steps in [steps]' own order.
     *
     * Scrolled to first, every time: the board sits under the task, the instruction and the strip, so a
     * tile can be below the fold — and a `performClick` on a node whose centre is off the window lands
     * nowhere and is silently lost, which reads here as "the right order was refused".
     */
    private fun tapInOrder(steps: List<String>) = steps.forEach(::tapBoard)

    /** One tile on the board, scrolled to first — see [tapInOrder]. */
    private fun tapBoard(step: String) {
        compose.onNode(hasTestTag(STEP_TILE_TAG) and hasText(step)).performScrollTo().performClick()
    }

    /** Takes one step back out of the strip by tapping it there. */
    private fun tapChosen(step: String) =
        compose.onNode(hasTestTag(STEP_CHOSEN_TAG) and hasText(step)).performScrollTo().performClick()

    /**
     * The strip holds exactly [steps], numbered from one, in that order.
     *
     * Read off each line's own text, which is «1.» and the step together: the numbers are half of what
     * the strip is for — a sequence he can read back — so they are part of what is asserted. The empty
     * slot has a tag of its own ([STEP_SLOT_TAG]) and is deliberately not one of these lines: it is a
     * hole, not a step he put down.
     */
    private fun assertStrip(why: String, steps: List<String>, slotAt: Int? = null) {
        val lines = textsOf(STEP_CHOSEN_TAG)
        assertEquals("$why — the strip holds $lines", steps.size, lines.size)
        steps.forEachIndexed { at, step ->
            // The empty slot takes a number of its own, so the steps below it are numbered one higher:
            // the strip he reads is «1., 2., 3.» top to bottom whether the second line is a step or a
            // hole waiting for one.
            val number = at + 1 + if (slotAt != null && at >= slotAt) 1 else 0
            assertEquals("$why — line ${at + 1} of the strip is «${lines[at]}»", "$number.$step", lines[at])
        }
    }

    private fun textsOf(tag: String): List<String> = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    private fun onScreen(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /**
     * The bottom block is the three actions it is allowed, in the order this module uses them: the green
     * primary on top, «Άκου» under it, «Παράλειψη» at the foot — the same three places in both stages,
     * because his thumb learns where they are. Read by position, which is all a semantics tree can see.
     */
    private fun assertThreeActionsInTheBottom(primary: String) {
        val green = compose.onNodeWithText(primary).fetchSemanticsNode().boundsInRoot.top
        val listen = compose.onNodeWithTag(LISTEN_TAG).fetchSemanticsNode().boundsInRoot.top
        val skip = compose.onNodeWithText(SKIP).fetchSemanticsNode().boundsInRoot.top
        assertTrue("«$primary» is not above «Άκου»: $green vs $listen", green < listen)
        assertTrue("«Άκου» is not above «Παράλειψη»: $listen vs $skip", listen < skip)
    }

    private fun rows(): List<Attempt> =
        runBlocking { graph.db.attempts().since(since) }.filter { it.module == ModuleId.STEPS }

    private companion object {
        const val READY = "Έτοιμο"
        const val SKIP = "Παράλειψη"
        const val SAID_IT = "Το είπα!"
        const val TIMEOUT_MS = 20_000L

        /** Not a real key and never used as one: the fake client never looks at it. */
        const val KEY = "sk-ant-test"

        const val ACCEPTED = """{"accept":true,"expanded":null,"feedback":null,"score":1}"""
        const val REFUSED = """{"accept":false,"expanded":null,"feedback":"Πάμε ξανά, σιγά σιγά.","score":0}"""
    }
}
