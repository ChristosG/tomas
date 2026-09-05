package gr.dimitris.app.modules.singsay

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * The things about sing-then-say that only a device can answer: that the five stages lead somewhere,
 * that finishing a phrase writes exactly one attempt however hard the last button is tapped, and
 * that a skip lands on the phrase it was meant to skip.
 *
 * Everything here waits for the button it is about to press to be *enabled*: the module deliberately
 * disables its buttons while the model is playing and while the next phrase is still being looked up.
 */
class SingSayFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    @Test fun theFiveStagesEndInOneAttemptEvenWhenTheLastTapIsDoubled() {
        val before = attempts()
        openModule()

        walkToSpeakStage()
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

    /** «Το έκανα» until the last stage offers «Το είπα!» instead. Ten is more than the five stages need. */
    private fun walkToSpeakStage() {
        repeat(10) {
            if (onScreen(SAID_IT)) return
            compose.waitUntil(TIMEOUT_MS) { enabled(DID_IT) || onScreen(SAID_IT) }
            if (onScreen(SAID_IT)) return
            compose.onNodeWithText(DID_IT).performClick()
        }
    }

    private fun openModule() {
        compose.onNodeWithText("Τραγούδα και πες το").performClick()
        compose.waitUntil(TIMEOUT_MS) { onScreen(STEP_ONE) }
    }

    private fun onScreen(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(text: String) = compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(0).filter { it.module == ModuleId.SINGSAY } }

    private companion object {
        const val STEP_ONE = "Βήμα 1 από 5"
        const val DID_IT = "Το έκανα"
        const val SAID_IT = "Το είπα!"
        const val SKIP = "Παράλειψη"
        const val THE_END = "Τέλος με το τραγούδι!"
        const val TIMEOUT_MS = 20_000L
    }
}
