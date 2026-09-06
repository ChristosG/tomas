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
