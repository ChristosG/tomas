package gr.dimitris.app.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Schedule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var seededId: String? = null

    // runBlocking<Unit>: JUnit needs a void @Before, and ItemRepository.save returns the saved Item.
    @Before fun seedOneWord() = runBlocking<Unit> {
        val item = graph.items.save(Item(text = "νερό", category = Category.FOOD))
        seededId = item.id
        // Due, not new: a device that already met today's new-item quota (a real session earlier
        // the same day) would otherwise plan nothing and the session would say "Τίποτα για σήμερα".
        graph.db.schedules().upsert(Schedule(itemId = item.id, module = ModuleId.WORDCOACH, nextDueAt = 0))
    }

    /** The word was ours, not his: take it back out. Its schedule row dies with it (plans skip deleted items). */
    @After fun removeSeed() = runBlocking<Unit> {
        seededId?.let { graph.items.delete(it) }
        seededId = null
    }

    @Test fun startRunsWordCoachAndConfirmAdvances() {
        compose.onNodeWithText("Ξεκίνα").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Βοήθεια").assertIsDisplayed()
        compose.onNodeWithText("Το είπα!").performClick()
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }

    /**
     * A sitting nobody did anything in writes no row about itself.
     *
     * Opening Today and backing straight out is one plausible tap with a right hemiparesis, and it
     * reaches the end of the session as soon as a module screen has loaded. A `session:summary` row
     * for it reads exactly like a sitting he struggled through and abandoned — `completed=0`,
     * `leftEarly=true` — and every rule in `docs/ADAPTATION.md` that shortens his sitting when he
     * keeps leaving early would be moved by his stray taps.
     */
    @Test fun aSittingWithNoAttemptsWritesNoSummaryRow() {
        val before = runBlocking { graph.db.attempts().since(0) }.count { it.itemId == SessionViewModel.SESSION_SUMMARY }
        compose.onNodeWithText("Ξεκίνα").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Πίσω").performClick()
        // The summary screen is only shown once the row has been written or not written: the
        // session finalises before it publishes the state the screen draws.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(SessionWording.NOTHING_DONE)).fetchSemanticsNodes().isNotEmpty()
        }
        val after = runBlocking { graph.db.attempts().since(0) }.count { it.itemId == SessionViewModel.SESSION_SUMMARY }
        assertEquals("a sitting with nothing in it wrote a row about itself", before, after)
    }

    /**
     * Back is not "next module": the session ends there, and it ends with what he actually did —
     * one exercise, said in the singular, with the module he did it in.
     */
    @Test fun backArrowEndsTheSessionWithAnHonestCount() {
        compose.onNodeWithText("Ξεκίνα").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }
        val before = runBlocking { graph.db.attempts().since(0).size }
        compose.onNodeWithText("Το είπα!").performClick()
        // The attempt is written off the screen's scope; the count is only honest once it has landed.
        //
        // *An* attempt, not the seeded word's. Which word the word coach hands him first is the
        // session builder's business and it has changed twice — phase 11 puts a live focus and then
        // the caregiver's newest words at the front — and this test is about what «Πίσω» does at the
        // end of a sitting, not about the order of the sitting. `SessionBuilderTest` owns that.
        compose.waitUntil(15_000) { runBlocking { graph.db.attempts().since(0).size } > before }
        compose.onNodeWithContentDescription("Πίσω").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Έκανες 1 άσκηση σήμερα: Λέξεις.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Μπράβο Δημήτρη!").assertIsDisplayed()
    }
}
