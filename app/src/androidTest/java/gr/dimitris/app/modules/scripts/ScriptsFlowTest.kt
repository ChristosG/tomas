package gr.dimitris.app.modules.scripts

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.ScriptWithLines
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.speech.FakeSpeechToText
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.ui.components.LISTEN_TAG
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.LocalFeedback
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * What only a device can answer about the dialogues: that a seeded one really opens from the Today
 * grid, that the other person's line hands over to him by itself — no timer, and no way to be left
 * on it when the phone has no Greek voice — and that his turn lands as exactly one Attempt row
 * carrying the dialogue it came from.
 *
 * The shapes none of the six shipped dialogues has — two of the other person's lines in a row, a
 * dialogue that ends on the other person, two of his turns back to back, a dialogue deleted between
 * the plan and the tap — are built here and driven directly, because every one of them is a
 * dialogue a caregiver can write and none of them can be reached through the seeds.
 *
 * Everything waits for the button it is about to press to be *enabled*: the module deliberately
 * leaves the bottom slot on «Ετοιμάζω...» until the dialogue is in hand.
 */
class ScriptsFlowTest {
    @get:Rule(order = 0) val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    /** Everything this class wrote, taken back out so the next class sees the seeds it expects. */
    private val written = mutableListOf<String>()

    /** The recogniser the gentle-check cases run against; the real one does not exist on an emulator. */
    private val fake = FakeSpeechToText()
    private var realStt: SpeechToText? = null

    @After fun removeWhatWasWritten() = runBlocking {
        written.forEach { graph.scripts.delete(it) }
        realStt?.let { graph.stt = it }
        graph.settings.setSttEnabled(false)
    }

    /** Recognition on, with a recogniser that hears whatever the case says it hears. */
    private fun withRecognition(): FakeSpeechToText {
        realStt = graph.stt
        runBlocking { graph.settings.setSttEnabled(true) }
        graph.stt = fake
        return fake
    }

    @Test fun aTurnHeSaysIsOneAttemptCarryingItsDialogue() {
        val before = attempts()
        openModule()

        compose.onNodeWithText(SAID_IT).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("said with no help is his own", Outcome.CORRECT, written.outcome)
        assertEquals("cue 0: nothing was given away", 0, written.cueLevel)
        assertTrue("the dialogue belongs in the attempt: ${written.detail}", written.detail.contains("\"scriptId\""))
        assertTrue("the turn's place in it too: ${written.detail}", written.detail.contains("\"position\""))
    }

    /**
     * The bug Chris found in the field: «Άκου ξανά» sat on his turn dead until «Βοήθεια» had been
     * pressed, so the one thing errorless learning says must never be withheld was the one thing he
     * had to earn. The button is now the big «Άκου» in the bottom row, live from the first second of
     * the turn, and using it reaches the row instead of being quietly free: the line was said to him,
     * which is cue level 3's worth of help, and the count of listens rides along in the detail.
     */
    @Test fun theModelIsOnOfferBeforeAnyHintAndTheRowSaysHeUsedIt() {
        val before = attempts()
        openModule()

        compose.onNodeWithTag(LISTEN_TAG).assertIsEnabled()
        compose.onNodeWithTag(LISTEN_TAG).performClick()
        compose.waitUntil(TIMEOUT_MS) { enabled(SAID_IT) }
        compose.onNodeWithText(SAID_IT).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("a line he had said to him is work done with help", Outcome.ASSISTED, written.outcome)
        assertTrue("hearing the model is level 3's worth of help: ${written.cueLevel}", (written.cueLevel ?: 0) >= 3)
        assertTrue("the row has to carry the listen: ${written.detail}", written.detail.contains("\"listened\":1"))
    }

    /** Passing on a turn is not doing it: the row has to say so, and the dialogue has to move on. */
    @Test fun aTurnHePassesOnIsWrittenAsSkipped() {
        val before = attempts()
        openModule()

        compose.onNodeWithText(SKIP).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("a turn passed on is not a turn taken", Outcome.SKIPPED, written.outcome)
        // Either the other person's next line or, at the end, the closing screen — never stuck.
        compose.waitUntil(TIMEOUT_MS) { onScreen(CONTINUE) || onScreen(SAID_IT) || onScreen(THE_END) }
    }

    /**
     * A dialogue that opens with two of the other person's lines and closes on a third. The first
     * pair proves the other side chains through itself — one utterance ending starts the next — and
     * the closing line proves the run finishes from the other person's turn, writing the dialogue's
     * schedule row on the way. Neither shape exists in the seeds; both are one edit away.
     */
    @Test fun twoLinesFromTheOtherSideRunOnAndAClosingOneStillEndsTheDialogue() {
        val script = dialogue(
            Speaker.OTHER to "Καλημέρα.",
            Speaker.OTHER to "Τι θα πάρετε;",
            Speaker.DIMITRIS to "Έναν καφέ, παρακαλώ.",
            Speaker.OTHER to "Αμέσως.",
        )
        val before = attempts()
        show(hisTurns(script))

        // Both of the other person's opening lines have been said by the time it is his turn.
        compose.waitUntil(OPEN_TIMEOUT_MS) { enabled(SAID_IT) }
        compose.onNodeWithText(SAID_IT).performClick()
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }

        // The closing line is the other person's: it is said, and then the dialogue is over.
        compose.waitUntil(OPEN_TIMEOUT_MS) { onScreen(THE_END) }
        assertNotNull(
            "a finished dialogue owes its Leitner row",
            runBlocking { graph.db.schedules().get(script.script.id, ModuleId.SCRIPTS) },
        )
    }

    /**
     * The dialogue was deleted between the session being planned and him tapping it — a caregiver
     * tidying up while he is on the Today screen. There is nothing to run and nothing to write: it
     * says so in Greek and lets him out, rather than holding him on a screen that will never do
     * anything.
     */
    @Test fun aDialogueDeletedBetweenThePlanAndTheScreenSaysSoAndLetsHimOut() {
        val script = dialogue(Speaker.OTHER to "Γεια σου.", Speaker.DIMITRIS to "Γεια.")
        val items = hisTurns(script)
        runBlocking { graph.scripts.delete(script.script.id) }
        val done = AtomicInteger()

        show(items, onDone = { done.incrementAndGet() })

        compose.waitUntil(TIMEOUT_MS) { onScreen(GONE) }
        compose.onNodeWithText(OK).performClick()
        compose.waitUntil(TIMEOUT_MS) { done.get() == 1 }
        assertTrue("nothing was practised, so nothing was scheduled", scheduleOf(script) == null)
    }

    /** The same inside a session, where there is no «Εντάξει»: the module has to hand back by itself. */
    @Test fun aVanishedDialogueInASessionHandsStraightBack() {
        val script = dialogue(Speaker.OTHER to "Γεια σου.", Speaker.DIMITRIS to "Γεια.")
        val items = hisTurns(script)
        runBlocking { graph.scripts.delete(script.script.id) }
        val done = AtomicInteger()

        show(items, sessionId = "δοκιμή", onDone = { done.incrementAndGet() })

        compose.waitUntil(TIMEOUT_MS) { done.get() == 1 }
    }

    /**
     * The double tap. «Το είπα!» is a big green button and he presses it one-handed; two of his
     * turns in a row are a dialogue a caregiver can write in a minute. Before the guard, the second
     * tap wrote a phantom attempt for a turn that had never been on screen and skipped it in the
     * conversation. Driving the ViewModel is the point: the two taps land inside one call stack,
     * which is exactly what a slip of the thumb does and what no click through the test framework
     * can reproduce.
     */
    @Test fun aDoubleTapCannotConfirmTheTurnHeNeverSaw() {
        val script = dialogue(Speaker.DIMITRIS to "Γεια σου.", Speaker.DIMITRIS to "Τι κάνεις;")
        val items = hisTurns(script)
        val before = attempts()
        lateinit var vm: ScriptsViewModel
        compose.runOnUiThread { vm = ScriptsViewModel(graph, items.first().id, null) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.phase == ScriptPhase.WAITING_FOR_DIMITRIS }

        compose.runOnUiThread { vm.confirm(); vm.confirm() }

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        assertEquals("one tap, one attempt", 1, (attempts() - before.toSet()).size)
        assertEquals("the conversation waits on his second turn", 1, vm.state.value.index)
        assertEquals(ScriptPhase.WAITING_FOR_DIMITRIS, vm.state.value.phase)

        // Once the screen has shown that turn, the very same tap does its work.
        compose.runOnUiThread { vm.turnReady(); vm.confirm() }
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 2 }
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * The gentle check on a whole turn, which is where it earns its keep: he dropped «έναν», which
     * is the man having the conversation and not failing it. The phone agrees, the turn is confirmed
     * for him, and what it heard is in the row.
     */
    @Test fun mostOfHisLineIsHisLineAndTheTurnIsConfirmedForHim() {
        val stt = withRecognition().willHear("θέλω καφέ")
        val script = dialogue(Speaker.DIMITRIS to "Θέλω έναν καφέ.")
        val before = attempts()
        val vm = viewModel(script)

        compose.runOnUiThread { vm.listen() }

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val row = (attempts() - before.toSet()).single()
        assertEquals("said with no help is his own", Outcome.CORRECT, row.outcome)
        assertTrue("what it heard belongs in the row: ${row.detail}", row.detail.contains("\"sttHeard\":\"θέλω καφέ\""))
        assertTrue("and that it agreed: ${row.detail}", row.detail.contains("\"sttMatched\":true"))
        assertEquals("one window was enough", 1, stt.windows)
        compose.runOnUiThread { vm.leave {} }
    }

    /** A miss is a nudge and never a wall: one «Δοκίμασε ξανά», then «Το είπα!» confirms as always. */
    @Test fun twoMissesOnATurnLeaveHimTheConfirm() {
        withRecognition().willHear("καλημέρα").willHearNothing()
        val script = dialogue(Speaker.DIMITRIS to "Θέλω έναν καφέ.")
        val before = attempts()
        val vm = viewModel(script)

        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttTries == 1 }
        assertEquals("one miss is one nudge", true, vm.state.value.nudge)
        assertEquals("«Το είπα!» is not his yet", false, vm.state.value.canConfirm)
        assertEquals("the cue has not moved", 0, vm.state.value.level)
        assertEquals("nothing has been written", before.size, attempts().size)

        // The second window hears nothing at all — a bad moment for the recogniser, not for him.
        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttTries == 2 }
        assertEquals("the phone stops asking", true, vm.state.value.canConfirm)

        compose.runOnUiThread { vm.confirm() }
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val row = (attempts() - before.toSet()).single()
        assertEquals("the turn was still his to take", Outcome.CORRECT, row.outcome)
        assertTrue("the two goes belong in the row: ${row.detail}", row.detail.contains("\"sttTries\":2"))
        compose.runOnUiThread { vm.leave {} }
    }

    /** A take with nothing in it is deleted and said aloud; the turn stays open and unwritten. */
    @Test fun aSilentTakeIsCaughtAndTheTurnStaysOpen() {
        val script = dialogue(Speaker.DIMITRIS to "Γεια σου.")
        val before = attempts()
        val vm = viewModel(script)

        compose.runOnUiThread { vm.toggleRecording() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.isRecording }
        Thread.sleep(TAKE_MS)
        compose.runOnUiThread { vm.toggleRecording() }

        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.isRecording }
        assertEquals("he is told, in his own language", Recorded.SILENT_TAKE, vm.state.value.error)
        assertEquals("nothing of his was kept", null, vm.state.value.selfRecordingPath)
        assertEquals("the turn is still his to take", ScriptPhase.WAITING_FOR_DIMITRIS, vm.state.value.phase)
        assertEquals("and nothing was written for it", before.size, attempts().size)
        compose.runOnUiThread { vm.leave {} }
    }

    /** Waits for the dialogue to be loaded, his turn to be on screen, and recognition to be settled. */
    private fun viewModel(script: ScriptWithLines): ScriptsViewModel {
        val seed = hisTurns(script).first().id
        lateinit var vm: ScriptsViewModel
        compose.runOnUiThread { vm = ScriptsViewModel(graph, seed, null) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.phase == ScriptPhase.WAITING_FOR_DIMITRIS }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttOn == (graph.stt === fake) }
        compose.runOnUiThread { vm.turnReady() }
        return vm
    }

    /**
     * Four modules make the Today grid two rows, and the second one is off-screen on a short screen
     * or whenever the "missing Greek voice" card is showing: the card has to be scrolled to before
     * it can be clicked, or the class fails on a node that was simply never composed.
     *
     * Waiting for «Το είπα!» to be enabled is waiting for the whole opening move: the dialogue is
     * loaded, the other person's first line has been said, and the turn has come round to him.
     */
    private fun openModule() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(MODULE))
        compose.onNodeWithText(MODULE).performClick()
        compose.waitUntil(OPEN_TIMEOUT_MS) { enabled(SAID_IT) }
    }

    /** A dialogue of exactly the shape a case needs, written the way the caregiver's editor writes one. */
    private fun dialogue(vararg lines: Pair<Speaker, String>): ScriptWithLines = runBlocking {
        val saved = graph.scripts.save(null, "Δοκιμή ${System.nanoTime()}", lines.map { LineDraft(it.first, it.second) })
        written += saved.id
        graph.scripts.load(saved.id)!!
    }

    /** What the module hands its screen: the items of his own turns, and nothing else. */
    private fun hisTurns(script: ScriptWithLines): List<Item> =
        script.lines.filter { it.first.speaker == Speaker.DIMITRIS }.map { it.second }

    private fun scheduleOf(script: ScriptWithLines) =
        runBlocking { graph.db.schedules().get(script.script.id, ModuleId.SCRIPTS) }

    /** The module's screen on its own, so a dialogue shape can be driven without a session around it. */
    private fun show(items: List<Item>, sessionId: String? = null, onDone: () -> Unit = {}, onLeave: () -> Unit = {}) {
        compose.runOnUiThread {
            compose.activity.setContent {
                DimitrisTheme {
                    CompositionLocalProvider(LocalAppGraph provides graph, LocalFeedback provides graph.feedback) {
                        ScriptsScreen(items, sessionId, onDone, onLeave)
                    }
                }
            }
        }
    }

    private fun onScreen(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(text: String) = compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(0).filter { it.module == ModuleId.SCRIPTS } }

    private companion object {
        const val MODULE = "Διάλογοι"
        const val SAID_IT = "Το είπα!"
        const val SKIP = "Παράλειψη"
        const val CONTINUE = "Συνέχεια"
        const val THE_END = "Τέλος διαλόγου!"
        const val GONE = "Ο διάλογος δεν είναι πια εδώ."
        const val OK = "Εντάξει"
        const val TIMEOUT_MS = 20_000L

        /** Longer: the first utterance of a run also waits for the speech engine to come up. */
        const val OPEN_TIMEOUT_MS = 40_000L

        /** Long enough for a dozen loudness samples and for MediaRecorder to close cleanly. */
        const val TAKE_MS = 1_200L
    }
}
