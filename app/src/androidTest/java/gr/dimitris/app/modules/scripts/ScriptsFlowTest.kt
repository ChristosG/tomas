package gr.dimitris.app.modules.scripts

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What only a device can answer about the dialogues: that a seeded one really opens from the Today
 * grid, that the other person's line hands over to him by itself — no timer, and no way to be left
 * on it when the phone has no Greek voice — and that his turn lands as exactly one Attempt row
 * carrying the dialogue it came from.
 *
 * Everything waits for the button it is about to press to be *enabled*: the module deliberately
 * leaves a disabled «Ακούω...» in the bottom slot while the other side is talking.
 */
class ScriptsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

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

    /** Passing on a turn is not doing it: the row has to say so, and the dialogue has to move on. */
    @Test fun aTurnHePassesOnIsWrittenAsSkipped() {
        val before = attempts()
        openModule()

        compose.onNodeWithText(SKIP).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().size == before.size + 1 }
        val written = (attempts() - before.toSet()).single()
        assertEquals("a turn passed on is not a turn taken", Outcome.SKIPPED, written.outcome)
        // Either the other person's next line or, at the end, the closing screen — never stuck.
        compose.waitUntil(TIMEOUT_MS) { onScreen(LISTENING) || onScreen(SAID_IT) || onScreen(THE_END) }
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

    private fun onScreen(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(text: String) = compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(0).filter { it.module == ModuleId.SCRIPTS } }

    private companion object {
        const val MODULE = "Διάλογοι"
        const val SAID_IT = "Το είπα!"
        const val SKIP = "Παράλειψη"
        const val LISTENING = "Ακούω..."
        const val THE_END = "Τέλος διαλόγου!"
        const val TIMEOUT_MS = 20_000L

        /** Longer: the first utterance of a run also waits for the speech engine to come up. */
        const val OPEN_TIMEOUT_MS = 40_000L
    }
}
