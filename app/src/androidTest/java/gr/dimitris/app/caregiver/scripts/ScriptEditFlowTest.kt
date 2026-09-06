package gr.dimitris.app.caregiver.scripts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.today.CAREGIVER_HOLD_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The dialogue half of «Δοκίμασέ το»: that «Παίξ' το» runs *this* conversation — the one she has
 * just written — and not whichever one the Leitner boxes would have brought round, and that coming
 * back out lands on the editor she left.
 *
 * A device question, because it is about navigation and about the dialogue the module resolves from
 * the turns it is handed.
 */
class ScriptEditFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private var scriptId: String? = null
    private var lockBefore = false

    @Before fun writeOneDialogue() = runBlocking<Unit> {
        lockBefore = graph.settings.caregiverLock.first()
        graph.settings.setCaregiverLock(false)
        scriptId = graph.scripts.save(
            null, TITLE,
            listOf(LineDraft(Speaker.OTHER, OTHERS_LINE), LineDraft(Speaker.DIMITRIS, HIS_LINE)),
        ).id
    }

    /** The dialogue and the setting were ours, not his. */
    @After fun removeIt() = runBlocking<Unit> {
        scriptId?.let { graph.scripts.delete(it) }
        graph.settings.setCaregiverLock(lockBefore)
    }

    /** One tap runs the conversation she wrote; back is the editor, not Today. */
    @Test fun theSavedDialogueRunsAtOnceAndBackReturnsToTheEditor() {
        openTheDialogue()

        compose.onNodeWithText(PLAY_IT).assertIsEnabled().performClick()

        // The module names the dialogue it is running: this is the one she was editing.
        compose.waitUntil(TIMEOUT_MS) { shown(TITLE) }
        compose.onNodeWithText(TITLE).assertIsDisplayed()

        compose.onNodeWithContentDescription(BACK).performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(EDITOR_TITLE) }
        compose.onNodeWithText(PLAY_IT).assertIsEnabled()
    }

    /** A dialogue that has never been saved has nothing for the module to open. */
    @Test fun anUnsavedDraftHasNothingToRunYet() {
        openTheDialogueList()
        compose.onNodeWithText(NEW_DIALOGUE).performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(PLAY_IT) }
        compose.onNodeWithText(PLAY_IT).assertIsNotEnabled()
    }

    /** Hold the name, say yes, open the dialogues — the way a caregiver gets here. */
    private fun openTheDialogueList() {
        compose.onNodeWithTag("title").performTouchInput {
            down(center)
            advanceEventTime(CAREGIVER_HOLD_MS + 200)
            up()
        }
        compose.onNodeWithText("Ναι").performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(DIALOGUES_ENTRY) }
        compose.onNodeWithText(DIALOGUES_ENTRY).performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(NEW_DIALOGUE) }
    }

    private fun openTheDialogue() {
        openTheDialogueList()
        compose.waitUntil(TIMEOUT_MS) { shown(TITLE) }
        compose.onNodeWithText(TITLE).performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(EDITOR_TITLE) }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        /** Ours, and none of the six shipped dialogues, so nothing here can match his. */
        const val TITLE = "Διάλογος δοκιμής επεξεργασίας"
        const val OTHERS_LINE = "Καλημέρα, τι θα πάρεις;"
        const val HIS_LINE = "Έναν καφέ, παρακαλώ."

        const val PLAY_IT = "Παίξ' το"
        const val NEW_DIALOGUE = "Νέος διάλογος"
        const val DIALOGUES_ENTRY = "Διάλογοι"
        const val EDITOR_TITLE = "Επεξεργασία"
        const val BACK = "Πίσω"

        const val TIMEOUT_MS = 20_000L
    }
}
