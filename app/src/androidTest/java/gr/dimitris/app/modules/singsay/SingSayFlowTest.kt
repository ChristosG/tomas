package gr.dimitris.app.modules.singsay

import android.Manifest
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.speech.FakeSpeechToText
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.ui.components.LISTEN_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The things about sing-then-say that only a device can answer: that the five stages lead somewhere,
 * that finishing a phrase writes exactly one attempt however hard the last button is tapped, and
 * that a skip lands on the phrase it was meant to skip.
 *
 * Everything here waits for the button it is about to press to be *enabled*: the module deliberately
 * disables its buttons while the model is playing and while the next phrase is still being looked up.
 */
class SingSayFlowTest {
    @get:Rule(order = 0) val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    /** The recogniser the gentle-check case runs against; the real one does not exist on an emulator. */
    private val fake = FakeSpeechToText()
    private var realStt: SpeechToText? = null

    /** The phrase and the fake recogniser were ours, not his: take them both back out. */
    @After fun putBackWhatWasBorrowed() = runBlocking<Unit> {
        phrase?.let { graph.items.delete(it.id) }
        realStt?.let { graph.stt = it }
        graph.settings.setSttEnabled(false)
    }

    private var phrase: Item? = null

    /**
     * The gentle check at the fifth stage, which is the only one it belongs to: the four before it
     * are sung *with* the phone, and a recogniser listening there would be checking the wrong voice.
     *
     * He gets to «Πες το κανονικά», says most of the phrase, and the phone agrees: the row is his
     * own work at stage five, with what it heard written into it.
     */
    @Test fun theLastStageIsCheckedAndAMatchFinishesThePhraseForHim() {
        withRecognition().willHear("θέλω καφέ")
        val item = runBlocking { graph.items.save(Item(text = "Θέλω έναν καφέ", category = Category.FOOD)) }
        phrase = item
        val before = attempts()
        lateinit var vm: SingSayViewModel
        compose.runOnUiThread { vm = SingSayViewModel(graph, listOf(item), null) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttOn }
        tapToLastStage(vm)

        compose.runOnUiThread { vm.listen() }

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val row = (attempts() - before.toSet()).single()
        assertEquals("a phrase said alone at the last stage is his own", Outcome.CORRECT, row.outcome)
        assertTrue("the stage he reached is still his: ${row.detail}", row.detail.contains("\"stage\":5"))
        assertTrue("what it heard belongs in the row: ${row.detail}", row.detail.contains("\"sttHeard\":\"θέλω καφέ\""))
        assertTrue("and that it agreed: ${row.detail}", row.detail.contains("\"sttMatched\":true"))
        compose.runOnUiThread { vm.leave {} }
    }

    /** A take with nothing in it is deleted and said aloud; the phrase stays open and unwritten. */
    @Test fun aSilentTakeIsCaughtAndThePhraseStaysOpen() {
        val item = runBlocking { graph.items.save(Item(text = "Καλημέρα", category = Category.FOOD)) }
        phrase = item
        val before = attempts()
        lateinit var vm: SingSayViewModel
        compose.runOnUiThread { vm = SingSayViewModel(graph, listOf(item), null) }
        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.loading && !vm.state.value.playing }

        compose.runOnUiThread { vm.toggleRecording() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.isRecording }
        Thread.sleep(TAKE_MS)
        compose.runOnUiThread { vm.toggleRecording() }

        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.isRecording }
        assertEquals("he is told, in his own language", Recorded.SILENT_TAKE, vm.state.value.error)
        assertEquals("nothing of his was kept", null, vm.state.value.selfRecordingPath)
        assertEquals("and nothing was written for it", before.size, attempts().size)
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * The phrase may not be sung into a window he has open.
     *
     * The last stage is the one where the phone checks him, and it is also the one where «Άκου» and
     * «Σύγκριση» will happily say the phrase aloud. Into a live recogniser that is the phone hearing
     * itself, matching, and congratulating him for a phrase he never said. Both doors are shut while
     * the window is open, and the refused «Άκου» is not counted against the row either.
     */
    @Test fun thePhraseCannotBeSungIntoAnOpenWindow() {
        val stt = withRecognition()
        stt.holdsOpen = true
        stt.willHearNothing()
        val item = runBlocking { graph.items.save(Item(text = "Θέλω έναν καφέ", category = Category.FOOD)) }
        phrase = item
        val before = attempts()
        lateinit var vm: SingSayViewModel
        compose.runOnUiThread { vm = SingSayViewModel(graph, listOf(item), null) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttOn }
        tapToLastStage(vm)

        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.listening }
        compose.runOnUiThread { vm.listenModel(); vm.playComparison() }

        assertEquals("nothing was sung over the open microphone", false, vm.state.value.playing)
        compose.runOnUiThread { vm.stopListening() }
        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.listening }

        stt.holdsOpen = false
        stt.willHearNothing()
        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.canConfirm }
        compose.runOnUiThread { vm.didIt() }
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val row = (attempts() - before.toSet()).single()
        assertTrue("the refused listen was not counted: ${row.detail}", row.detail.contains("\"listened\":0"))
        assertEquals("a phrase said alone at the last stage is still his own", Outcome.CORRECT, row.outcome)
        compose.runOnUiThread { vm.leave {} }
    }

    /** Recognition on, with a recogniser that hears whatever the case says it hears. */
    private fun withRecognition(): FakeSpeechToText {
        realStt = graph.stt
        runBlocking { graph.settings.setSttEnabled(true) }
        graph.stt = fake
        return fake
    }

    /**
     * One tap per syllable, one pass per stage — the same walk the screen-driven cases make, done
     * through the ViewModel so the recogniser can be reached without a permission dialog in the way.
     */
    private fun tapToLastStage(vm: SingSayViewModel) {
        repeat(MAX_TAPS) {
            if (vm.state.value.stage == SingStage.SPEAK) return
            compose.waitUntil(TIMEOUT_MS) { !vm.state.value.playing && !vm.state.value.loading }
            if (vm.state.value.stage == SingStage.SPEAK) return
            compose.runOnUiThread { vm.tap() }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.stage == SingStage.SPEAK && !vm.state.value.playing }
    }

    @Test fun theFiveStagesEndInOneAttemptEvenWhenTheLastTapIsDoubled() {
        val before = attempts()
        openModule()

        tapToSpeakStage()
        compose.waitUntil(TIMEOUT_MS) { enabled(SAID_IT) }
        compose.onNodeWithText(SAID_IT).performClick()
        // A second tap in the same breath — the button is still on screen for a frame. It must not
        // finish the phrase that has just arrived as well.
        runCatching { compose.onNodeWithText(SAID_IT).performClick() }

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("a phrase carried to the last stage is his own", Outcome.CORRECT, written.outcome)
        assertEquals("cue 0: he said it with nothing left to lean on", 0, written.cueLevel)
        assertTrue("the stage reached belongs in the attempt: ${written.detail}", written.detail.contains("\"stage\":5"))

        // And it stays one: the next phrase (or the end screen) arrives without a second row.
        compose.waitUntil(TIMEOUT_MS) { onScreen(STEP_ONE) || onScreen(THE_END) }
        assertEquals("one attempt per phrase", before.size + 1, attempts().size)
    }

    /**
     * The stage he reached is the score. «Το έκανα» at the fading stage is a phrase produced with
     * the music still under him: it has to land as assisted work at cue 2, not as a skip that
     * demotes the phrase and not as one said alone.
     */
    @Test fun doingItAtTheFadingStageIsAssistedWithCueTwo() {
        val before = attempts()
        openModule()

        tapUntil { onScreen(STEP_THREE) }
        compose.waitUntil(TIMEOUT_MS) { onScreen(STEP_THREE) }
        compose.waitUntil(TIMEOUT_MS) { enabled(DID_IT) }
        compose.onNodeWithText(DID_IT).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("the backing was still under him", Outcome.ASSISTED, written.outcome)
        assertEquals("cue 2: two props still to give up", 2, written.cueLevel)
        assertTrue("the stage reached belongs in the attempt: ${written.detail}", written.detail.contains("\"stage\":3"))
    }

    /**
     * «Άκου» is live at the first stage before anything has been tapped, and it is still live at the
     * fifth, where the whole point is that the music and the model are gone — spec §12 does not make
     * an exception for the stage that is hardest. What it costs is the row: a phrase said alone after
     * asking to hear it is assisted work at the listening level, not the clean cue 0 it would be
     * otherwise, and the count of listens rides along in the detail.
     */
    @Test fun theModelIsOnOfferAtEveryStageAndTheRowSaysHeUsedIt() {
        val before = attempts()
        openModule()

        // Before a single tap: the model is already on offer, once the stage has finished speaking.
        compose.waitUntil(TIMEOUT_MS) { enabledTag(LISTEN_TAG) }

        tapToSpeakStage()
        compose.waitUntil(TIMEOUT_MS) { enabledTag(LISTEN_TAG) }
        compose.onNodeWithTag(LISTEN_TAG).performClick()

        compose.waitUntil(TIMEOUT_MS) { enabled(SAID_IT) }
        compose.onNodeWithText(SAID_IT).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("a phrase he had said to him is work done with help", Outcome.ASSISTED, written.outcome)
        assertTrue("hearing the model is level 3's worth of help: ${written.cueLevel}", (written.cueLevel ?: 0) >= 3)
        assertTrue("the row has to carry the listen: ${written.detail}", written.detail.contains("\"listened\":1"))
        assertTrue("and the stage he reached is still his: ${written.detail}", written.detail.contains("\"stage\":5"))
    }

    @Test fun skipMovesOnAndSaysSoInTheRow() {
        val before = attempts()
        openModule()

        // Enabled only once this phrase has finished loading: skipping into a half-loaded phrase is
        // what used to leave the next one wearing the previous one's sung model.
        compose.waitUntil(TIMEOUT_MS) { enabled(SKIP) }
        compose.onNodeWithText(SKIP).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("a phrase passed on is not a phrase done", Outcome.SKIPPED, written.outcome)
        // Either the next phrase or, in a one-phrase run, the end of it — never a stuck screen.
        compose.waitUntil(TIMEOUT_MS) { onScreen(STEP_ONE) || onScreen(THE_END) }
    }

    /**
     * The tap pad, over and over: a pass through the phrase's syllables finishes a repetition and
     * the module moves to the next stage by itself, so the number of taps depends on how many
     * syllables this phrase has. The cap is generous — six passes of a long phrase — and the loop
     * stops the moment the last stage's «Το είπα!» appears.
     */
    private fun tapToSpeakStage() = tapUntil { onScreen(SAID_IT) }

    private fun tapUntil(there: () -> Boolean) {
        repeat(MAX_TAPS) {
            if (there()) return
            compose.waitUntil(TIMEOUT_MS) { enabled(TAP) || there() }
            if (there()) return
            compose.onNodeWithText(TAP).performClick()
        }
    }

    /**
     * Three modules make the Today grid two rows, and the second one is off-screen on a short
     * screen or whenever the "missing Greek voice" card is showing: the card has to be scrolled to
     * before it can be clicked, or the class fails on a node that was simply never composed.
     */
    private fun openModule() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(MODULE))
        compose.onNodeWithText(MODULE).performClick()
        compose.waitUntil(TIMEOUT_MS) { onScreen(STEP_ONE) }
    }

    private fun onScreen(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(text: String) = compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    /** «Άκου» is found by its tag: the first stage's own label is the same word. */
    private fun enabledTag(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(0).filter { it.module == ModuleId.SINGSAY } }

    private companion object {
        const val MODULE = "Τραγούδα και πες το"
        const val STEP_ONE = "Βήμα 1 από 5"
        const val STEP_THREE = "Βήμα 3 από 5"
        const val TAP = "Χτύπα"
        const val DID_IT = "Το έκανα"
        const val SAID_IT = "Το είπα!"
        const val SKIP = "Παράλειψη"
        const val THE_END = "Τέλος με το τραγούδι!"
        const val TIMEOUT_MS = 20_000L

        /** Six passes (1 + 1 + 3 fading + 1) of a phrase far longer than any seeded one. */
        const val MAX_TAPS = 120

        /** Long enough for a dozen loudness samples and for MediaRecorder to close cleanly. */
        const val TAKE_MS = 1_200L
    }
}
