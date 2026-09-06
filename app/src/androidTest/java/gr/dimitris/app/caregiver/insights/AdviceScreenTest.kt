package gr.dimitris.app.caregiver.insights

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.now
import gr.dimitris.app.today.CAREGIVER_HOLD_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val TIMEOUT_MS = 15_000L

/** Ours, and nothing the seed vocabulary or another test could have written. */
private const val NOTE = "Δοκιμή σημείωσης: είπε «καλημέρα» μόνος του."

private fun hasTextStartingWith(prefix: String) =
    SemanticsMatcher("text starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) }
    }

/**
 * The advice screen on a device: the three things only a device can answer.
 *
 * A note really reaches the database and comes back onto the screen; the report really gets built
 * from this phone's own history and the screen says how big it is before anything is sent; and a
 * key that is not a key really goes out and comes back as one Greek sentence — never a stack trace,
 * never an English one, and never the key itself.
 */
class AdviceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private var lockBefore = false
    private var keyBefore: String? = null
    private var noteId: String? = null

    @Before fun openTheDoor() = runBlocking<Unit> {
        lockBefore = graph.settings.caregiverLock.first()
        graph.settings.setCaregiverLock(false)
        keyBefore = graph.secrets.getClaudeKey()
    }

    /** The note and the key were ours, not theirs. */
    @After fun putItBack() = runBlocking<Unit> {
        noteId?.let { graph.db.notes().softDelete(it, now()) }
        noteId = null
        graph.secrets.setClaudeKey(keyBefore)
        graph.settings.setCaregiverLock(lockBefore)
    }

    @Test fun aNoteIsSavedAndComesBackOntoTheScreen() {
        openAdvice()
        compose.onNodeWithTag("note-field").performScrollTo().performTextInput(NOTE)
        compose.onNodeWithTag("save-note").performScrollTo().performClick()

        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.db.notes().recent(50) }.any { it.text == NOTE }
        }
        val saved = runBlocking { graph.db.notes().recent(50) }.first { it.text == NOTE }
        noteId = saved.id
        // The device role is what a note is signed with, so two phones' notes stay told apart.
        assertEquals(runBlocking { graph.settings.deviceRole.first() }.name, saved.author)

        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(NOTE)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(NOTE).performScrollTo().assertIsDisplayed()
    }

    /**
     * The preview's whole contract is "the caregiver sees exactly what is sent". It used to switch
     * to the *sent* copy as soon as an answer arrived, so a note typed after reading the answer
     * («έκλαψε στον οδοντίατρο») would have gone to Anthropic on the next «Ρώτα ξανά» without ever
     * appearing under «Τι θα σταλεί». The count has to move when a note is saved.
     */
    @Test fun theCountFollowsTheReportThatWillActuallyBeSent() {
        openAdvice()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTextStartingWith("Υπολογίζω")).fetchSemanticsNodes().isEmpty()
        }
        val before = reportSize()

        compose.onNodeWithTag("note-field").performScrollTo().performTextInput(NOTE)
        compose.onNodeWithTag("save-note").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.db.notes().recent(50) }.any { it.text == NOTE }
        }
        noteId = runBlocking { graph.db.notes().recent(50) }.first { it.text == NOTE }.id

        compose.waitUntil(TIMEOUT_MS) { reportSize() > before }
        assertTrue("the note has to be in what would be sent now", reportSize() >= before + NOTE.length)
        // And the label never claims to be showing something already sent.
        compose.onNodeWithText("Τι θα σταλεί").performScrollTo().assertIsDisplayed()
    }

    private fun reportSize(): Int {
        val text = compose.onNodeWithTag("report-size").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text
        return text.removeSuffix(" χαρακτήρες").toIntOrNull() ?: -1
    }

    /**
     * The one screen that can send anything anywhere says how much, before it sends it — and the
     * report really is built from this phone's database rather than being a placeholder.
     */
    @Test fun theScreenSaysHowBigTheReportIsBeforeAnythingLeaves() {
        openAdvice()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTextStartingWith("Υπολογίζω")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("report-size").performScrollTo().assertIsDisplayed()

        val size = compose.onNodeWithTag("report-size").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text
        assertTrue(size, size.endsWith(" χαρακτήρες"))
        val characters = size.removeSuffix(" χαρακτήρες").toInt()
        assertTrue("a real report is thousands of characters, not $characters", characters > 500)

        // And it is readable before it is sent, which is the whole point of the section.
        compose.onNodeWithTag("toggle-report").performScrollTo().performClick()
        compose.onNodeWithTag("advice-summary").performScrollTo().assertIsDisplayed()
        val report = compose.onNodeWithTag("advice-summary").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text
        assertTrue(report.take(200), report.startsWith(JourneyReport.PROFILE_HEADING))
        assertTrue("his whole journey, not four weeks of totals", report.contains(JourneyReport.LIFETIME_HEADING))
        // The one guarantee the section exists to make.
        assertTrue("a path must never leave the phone", !report.contains("media://") && !report.contains("/data/"))
    }

    /**
     * A key that is not a key. The request really goes out, and what comes back on the screen is one
     * Greek sentence a caregiver can act on — the SDK's exception, its status line and the key it
     * carried never reach the screen or `error_logs`.
     */
    @Test fun aKeyThatIsNotAKeyFailsInGreek() {
        runBlocking { graph.secrets.setClaudeKey("sk-ant-api03-not-a-real-key-for-tests") }
        openAdvice()
        // The button stays disabled until there is a report to send, so wait for it to be built.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTextStartingWith("Υπολογίζω")).fetchSemanticsNodes().isEmpty()
        }
        // The ask button lives in the fixed bottom slot, which does not scroll.
        compose.onNodeWithTag("ask-claude").performClick()

        // Four minutes is the advisor's own ceiling; a 401 comes back in seconds.
        compose.waitUntil(60_000L) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "advice-error"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val message = compose.onNodeWithTag("advice-error").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text
        // 401 on a machine with a network, "δοκίμασε ξανά" on one without. Both are Greek, both are
        // one sentence, and neither says anything about the request.
        assertTrue(
            message,
            message == ClaudeAdvisor.BAD_KEY || message.startsWith(ClaudeAdvisor.FAILED),
        )
        assertTrue("nothing from the request may reach the screen", !message.contains("sk-ant"))
    }

    /** Hold the name, say yes, Πρόοδος, then «Ρώτα τον Claude» — the way a caregiver gets here. */
    private fun openAdvice() {
        compose.onNodeWithTag("title").performTouchInput {
            down(center)
            advanceEventTime(CAREGIVER_HOLD_MS + 200)
            up()
        }
        compose.onNodeWithText("Ναι").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Πρόοδος")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Πρόοδος").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Ανά άσκηση")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ρώτα τον Claude").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Σημειώσεις")).fetchSemanticsNodes().isNotEmpty() }
    }
}
