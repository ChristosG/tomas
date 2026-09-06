package gr.dimitris.app.modules.singsay

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
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.ui.components.LISTEN_TAG
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
    }
}
