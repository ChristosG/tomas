package gr.dimitris.app.modules.wordcoach

import android.Manifest
import android.speech.SpeechRecognizer
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.speech.FakeSpeechToText
import gr.dimitris.app.core.speech.Recognition
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.ui.components.LISTEN_TAG
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.LocalFeedback
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * What only a device can answer about the word coach since spec §12: that «Άκου» is live before any
 * «Βοήθεια» has been pressed — Chris found the dialogues' listen button dead until it was — and that
 * using it reaches the row rather than being quietly free.
 */
class WordCoachFlowTest {
    @get:Rule(order = 0) val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private lateinit var word: Item

    /** The recogniser the gentle-check cases run against; the real one does not exist on an emulator. */
    private val fake = FakeSpeechToText()
    private lateinit var realStt: SpeechToText

    // runBlocking<Unit>: JUnit needs a void @Before, and ItemRepository.save returns the saved Item.
    @Before fun seedOneWord() = runBlocking<Unit> {
        word = graph.items.save(Item(text = "νερό", category = Category.FOOD))
        realStt = graph.stt
    }

    /** The word and the fake recogniser were ours, not his: take them both back out. */
    @After fun removeSeed() = runBlocking<Unit> {
        graph.items.delete(word.id)
        graph.stt = realStt
        graph.settings.setSttEnabled(false)
    }

    /** Recognition on, with a recogniser that hears whatever the case says it hears. */
    private fun withRecognition(): FakeSpeechToText {
        runBlocking { graph.settings.setSttEnabled(true) }
        graph.stt = fake
        return fake
    }

    /**
     * The whole of the rule in one run: the button is there and enabled at cue level 0, with nothing
     * asked for and nothing given away; pressing it says the word; and the attempt that follows is
     * written as assisted work at the listening level, with the count of listens in its detail. The
     * cue on the screen has not moved — «Βοήθεια» is still on offer, which is what tells us the
     * ladder was left where it stood.
     */
    @Test fun theModelIsOnOfferBeforeAnyHintAndTheRowSaysHeUsedIt() {
        val before = attempts()
        show(listOf(word))

        compose.onNodeWithTag(LISTEN_TAG).assertIsEnabled()
        compose.onNodeWithText(HELP).assertIsEnabled()
        compose.onNodeWithTag(LISTEN_TAG).performClick()

        compose.waitUntil(TIMEOUT_MS) { enabled(SAID_IT) }
        compose.onNodeWithText(SAID_IT).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("a word he had said to him is work done with help", Outcome.ASSISTED, written.outcome)
        assertTrue("hearing the model is level 3's worth of help: ${written.cueLevel}", (written.cueLevel ?: 0) >= 3)
        assertTrue("the row has to carry the listen: ${written.detail}", written.detail.contains("\"listened\":1"))
    }

    /**
     * The microphone never opens under the model, and the next word never opens greyed.
     *
     * This is what «Πες το» and «Άκου» hang off: `modelPlaying` is raised *before* the utterance is
     * launched, so «Πες το» (`isRecording || (!modelPlaying && !listening)`) and «Άκου» itself are
     * both off for exactly as long as the model sounds. He hears the first syllable and reaches
     * straight for the microphone — the behaviour this screen now invites — and the take would
     * otherwise be the phone's own voice, played back to him by «Σύγκριση» as his.
     *
     * Driven through the ViewModel because the assertions have to land in the same call stack as
     * the taps: on a device with a working Greek voice the flag lives for as long as the word takes
     * to say, and on this emulator it is gone by the next frame — a click-and-look test would be
     * measuring the speech engine, not the rule.
     */
    @Test fun theMicrophoneNeverOpensUnderTheModelAndTheNextWordIsLive() {
        lateinit var vm: WordCoachViewModel
        compose.runOnUiThread { vm = WordCoachViewModel(graph, listOf(word, word.copy(id = "second", text = "ψωμί")), null) }
        assertEquals("nothing is sounding yet", false, vm.state.value.modelPlaying)

        // Two taps in one breath: the second lands while the model still holds the screen, and is
        // not a second listen — the same guard that keeps the microphone shut.
        compose.runOnUiThread { vm.listenModel(); vm.listenModel() }
        assertEquals("the model has the screen, so «Πες το» and «Άκου» are off", true, vm.state.value.modelPlaying)

        compose.runOnUiThread { vm.confirm() }
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.itemId == word.id } }
        assertTrue(
            "one listen, not two: ${attempts().last { it.itemId == word.id }.detail}",
            attempts().last { it.itemId == word.id }.detail.contains("\"listened\":1"),
        )

        // On to the next word: whatever was still being said goes with the word it belonged to.
        compose.runOnUiThread { vm.next() }
        assertEquals("the next word's «Άκου» is live from its first frame", false, vm.state.value.modelPlaying)
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * The gentle check, all the way through, when the phone agrees with him: one window, a match,
     * and the word is confirmed *for* him at the cue level he was on. Being made to press a button
     * to agree with the phone is one step too many for a man who has just done the hard part.
     */
    @Test fun aMatchConfirmsTheWordForHim() {
        withRecognition().willHear("νερό")
        val before = attempts()
        val vm = viewModel()

        compose.runOnUiThread { vm.listen() }

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        assertEquals("the phone agreed with him: the word is done", true, vm.state.value.confirmed)
        assertEquals("and it is his own, with nothing given away", Outcome.CORRECT, written(before).outcome)
        val detail = written(before).detail
        assertTrue("what it heard belongs in the row: $detail", detail.contains("\"sttHeard\":\"νερό\""))
        assertTrue("and that it agreed: $detail", detail.contains("\"sttMatched\":true"))
        // Zero, not one: a try is a window the phone *disagreed* with him on, and it agreed.
        assertTrue("agreeing with him cost him no try: $detail", detail.contains("\"sttTries\":0"))
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * The other half of the rule: a miss is a nudge and never a wall. One «Δοκίμασε ξανά» with the
     * cue exactly where it was, and then «Το είπα!» comes back and confirms as it always did — here
     * after he had asked to hear the word, so the row is assisted work at the listening level.
     */
    @Test fun twoMissesLeaveHimTheConfirmAndTheLadderStillDecidesTheRow() {
        withRecognition().willHear("ψωμί").willHear("γάλα")
        val before = attempts()
        val vm = viewModel()
        compose.runOnUiThread { vm.listenModel() }

        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttTries == 1 }
        assertEquals("one miss is one nudge", true, vm.state.value.nudge)
        assertEquals("«Το είπα!» is not his yet", false, vm.state.value.canConfirm)
        assertEquals("the cue has not moved", 0, vm.state.value.level)
        assertEquals("«Βοήθεια» is still on offer", true, vm.state.value.canHint)
        assertEquals("and nothing has been written", before.size, attempts().size)

        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttTries == 2 }
        assertEquals("the phone stops asking", false, vm.state.value.nudge)
        assertEquals("and the button is his", true, vm.state.value.canConfirm)

        compose.runOnUiThread { vm.confirm() }
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val row = written(before)
        assertEquals("a word he had said to him is work done with help", Outcome.ASSISTED, row.outcome)
        assertTrue("at the listening level: ${row.cueLevel}", (row.cueLevel ?: 0) >= 3)
        assertTrue("the two goes belong in the row: ${row.detail}", row.detail.contains("\"sttTries\":2"))
        assertTrue("and that the phone never agreed: ${row.detail}", row.detail.contains("\"sttMatched\":false"))
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * A take with nothing in it. The emulator's microphone is silent, which is exactly the case
     * Chris found: the app used to keep it, write a recording row for it, and play it back to him as
     * his own voice. Now it is deleted, he is told in Greek, and the word stays open.
     */
    @Test fun aSilentTakeIsCaughtAndTheWordStaysOpen() {
        val before = attempts()
        val vm = viewModel()

        compose.runOnUiThread { vm.toggleRecording() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.isRecording }
        Thread.sleep(TAKE_MS)
        compose.runOnUiThread { vm.toggleRecording() }

        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.isRecording }
        assertEquals("he is told, in his own language", Recorded.SILENT_TAKE, vm.state.value.error)
        assertEquals("nothing of his was kept", null, vm.state.value.selfRecordingPath)
        assertEquals("and the word is still his to say", false, vm.state.value.confirmed)
        assertEquals("nothing was written for it", before.size, attempts().size)
        compose.runOnUiThread { vm.leave {} }
    }

    /** «Στοπ» closes the window his way, and what the phone had already heard still counts. */
    @Test fun stopClosesTheWindowAndTheAnswerStillArrives() {
        val stt = withRecognition()
        stt.holdsOpen = true
        stt.willHear("νερό")
        val before = attempts()
        val vm = viewModel()

        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.listening }
        // The bar is the only thing on the screen that answers "is it hearing me?".
        stt.loudness(0.8f)
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.listenLevel > 0.5f }

        compose.runOnUiThread { vm.stopListening() }
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        assertEquals("«Στοπ» closed the window he had open, and only that one", 1, stt.stops)
        assertEquals("and the word he got out still counted", true, vm.state.value.confirmed)
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * The phone could not listen at all — offline, no Greek model. That is the *phone's* failure,
     * and Chris' whole report was about the app putting its own trouble on him: before this, every
     * word cost two dead «Μίλα» taps and two red lines about *his* voice before «Το είπα!» came
     * back. Now it costs him nothing: no try is spent, the confirm opens at once, and the line
     * points at the settings.
     */
    @Test fun aPhoneThatCannotListenCostsHimNothing() {
        val stt = withRecognition()
        stt.willFail(SpeechRecognizer.ERROR_NETWORK)
        val before = attempts()
        val vm = viewModel()

        compose.runOnUiThread { vm.listen() }

        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.listening }
        assertEquals("the line is about the phone, not about his voice", Recognition.NOT_WORKING, vm.state.value.error)
        assertEquals("no try was spent on the phone's bad morning", 0, vm.state.value.sttTries)
        assertEquals("and he is not nudged for it", false, vm.state.value.nudge)
        assertEquals("«Το είπα!» is his at once", true, vm.state.value.canConfirm)
        assertEquals("nothing was written", before.size, attempts().size)

        // And a second failure does not take the confirm away again.
        stt.willFail(SpeechRecognizer.ERROR_SERVER)
        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.listening }
        assertEquals("the confirm a broken recogniser opened is not taken back", true, vm.state.value.canConfirm)
        assertEquals(0, vm.state.value.sttTries)

        compose.runOnUiThread { vm.confirm() }
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        assertEquals("the word was still his to say", Outcome.CORRECT, written(before).outcome)
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * A window that heard nothing costs him nothing at all: no try, no nudge, the cue where it was.
     *
     * The wait itself is where silence is answered — the session is restarted rather than scored —
     * so by the time one of these comes back the phone has been listening for as long as it can,
     * and it has learned nothing about whether he spoke.
     */
    @Test fun aWindowThatHeardNothingCostsHimNothing() {
        withRecognition().willHearNothing()
        val before = attempts()
        val vm = viewModel()

        compose.runOnUiThread { vm.listen() }

        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.listening }
        assertEquals("no try was spent on a phone that did not hear him", 0, vm.state.value.sttTries)
        assertEquals("and he is not nudged for it", false, vm.state.value.nudge)
        assertEquals("the cue has not moved", 0, vm.state.value.level)
        assertEquals("«Βοήθεια» is still on offer", true, vm.state.value.canHint)
        assertEquals("nothing was written", before.size, attempts().size)
        compose.runOnUiThread { vm.leave {} }
    }

    /** The model may not be spoken into a window he has open: the phone would hear itself. */
    @Test fun theModelCannotBeSpokenIntoAnOpenWindow() {
        val stt = withRecognition()
        stt.holdsOpen = true
        stt.willHear("ψωμί")
        val before = attempts()
        val vm = viewModel()

        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.listening }
        // «Βοήθεια» too: at cue level 3 it says the whole word, which is the same door.
        compose.runOnUiThread { vm.listenModel(); vm.playComparison(); vm.hint() }

        assertEquals("nothing was said over the open microphone", false, vm.state.value.modelPlaying)
        assertEquals("and the ladder did not move under him either", 0, vm.state.value.level)
        compose.runOnUiThread { vm.stopListening() }
        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.listening }

        // The refused «Άκου» must not have been counted either: the row would claim help he never got.
        stt.holdsOpen = false
        stt.willHear("γάλα")
        compose.runOnUiThread { vm.listen() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.canConfirm }
        compose.runOnUiThread { vm.confirm() }
        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val row = written(before)
        assertTrue("the refused listen was not counted: ${row.detail}", row.detail.contains("\"listened\":0"))
        assertTrue("and the phone never agreed with him: ${row.detail}", row.detail.contains("\"sttMatched\":false"))
        compose.runOnUiThread { vm.leave {} }
    }

    private fun viewModel(): WordCoachViewModel {
        lateinit var vm: WordCoachViewModel
        compose.runOnUiThread { vm = WordCoachViewModel(graph, listOf(word), null) }
        // The settings and the package manager are asked off the drawing thread: the screen is only
        // in its recognition state once that has landed.
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.sttOn == (graph.stt === fake) }
        return vm
    }

    private fun written(before: List<Attempt>): Attempt = (attempts() - before.toSet()).single()

    /** The module's screen on its own: a session around it would plan words that are not ours. */
    private fun show(items: List<Item>) {
        compose.runOnUiThread {
            compose.activity.setContent {
                DimitrisTheme {
                    CompositionLocalProvider(LocalAppGraph provides graph, LocalFeedback provides graph.feedback) {
                        WordCoachScreen(items, sessionId = null, onDone = {}, onLeave = {})
                    }
                }
            }
        }
    }

    private fun enabled(text: String) = compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(0).filter { it.module == ModuleId.WORDCOACH } }

    private companion object {
        const val HELP = "Βοήθεια"
        const val SAID_IT = "Το είπα!"
        const val TIMEOUT_MS = 20_000L

        /** Long enough for a dozen loudness samples and for MediaRecorder to close cleanly. */
        const val TAKE_MS = 1_200L
    }
}
