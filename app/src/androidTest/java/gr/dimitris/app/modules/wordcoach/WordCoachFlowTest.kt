package gr.dimitris.app.modules.wordcoach

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
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
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
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private lateinit var word: Item

    // runBlocking<Unit>: JUnit needs a void @Before, and ItemRepository.save returns the saved Item.
    @Before fun seedOneWord() = runBlocking<Unit> {
        word = graph.items.save(Item(text = "νερό", category = Category.FOOD))
    }

    /** The word was ours, not his: take it back out. */
    @After fun removeSeed() = runBlocking<Unit> { graph.items.delete(word.id) }

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
    }
}
