package gr.dimitris.app.caregiver.scripts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.today.CAREGIVER_HOLD_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    /** The dialogues and the setting were ours, not his — the one the draft case wrote included. */
    @After fun removeIt() = runBlocking<Unit> {
        scriptId?.let { graph.scripts.delete(it) }
        drafts().forEach { graph.scripts.delete(it.id) }
        graph.settings.setCaregiverLock(lockBefore)
    }

    /** Whatever the new-draft case left behind, found by its title: the id is never handed back. */
    private suspend fun drafts(): List<Script> =
        graph.scripts.observeAll().first().filter { it.title == DRAFT_TITLE }

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

    /**
     * A conversation she has only just typed. The save happens under the button and the editor
     * stays open — the header is still «Νέος διάλογος» — the dialogue plays, and back lands on the
     * same form with every turn still in it.
     */
    @Test fun aDialogueSheHasOnlyJustTypedPlaysWithoutLeavingTheForm() {
        openTheDialogueList()
        compose.onNodeWithText(NEW_DIALOGUE).performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(PLAY_IT) }
        // Title, then the other person's turn, then his: the three fields a blank form starts with.
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(DRAFT_TITLE)
        fields[1].performTextInput(DRAFT_OTHERS_LINE)
        fields[2].performScrollTo().performTextInput(DRAFT_HIS_LINE)

        compose.onNodeWithText(PLAY_IT).assertIsEnabled().performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(DRAFT_OTHERS_LINE) }
        compose.onNodeWithText(DRAFT_TITLE).assertIsDisplayed()

        compose.onNodeWithContentDescription(BACK).performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(PLAY_IT) }
        compose.onNodeWithText(DRAFT_TITLE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(NEW_DIALOGUE).assertIsDisplayed()   // the header did not change
        // Exactly one dialogue, however many times it was saved on the way.
        assertEquals(1, runBlocking { drafts() }.size)
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

        /** Typed into a brand-new form by the draft case, and taken back out afterwards. */
        const val DRAFT_TITLE = "Διάλογος δοκιμής προχείρου"
        const val DRAFT_OTHERS_LINE = "Τι ώρα φεύγει το λεωφορείο;"
        const val DRAFT_HIS_LINE = "Στις οκτώ."

        const val PLAY_IT = "Παίξ' το"
        const val NEW_DIALOGUE = "Νέος διάλογος"
        const val DIALOGUES_ENTRY = "Διάλογοι"
        const val EDITOR_TITLE = "Επεξεργασία"
        const val BACK = "Πίσω"

        const val TIMEOUT_MS = 20_000L
    }
}
