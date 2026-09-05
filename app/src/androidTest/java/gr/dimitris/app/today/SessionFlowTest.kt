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
     * Back is not "next module": the session ends there, and it ends with what he actually did —
     * one exercise, said in the singular, with the module he did it in.
     */
    @Test fun backArrowEndsTheSessionWithAnHonestCount() {
        compose.onNodeWithText("Ξεκίνα").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Το είπα!").performClick()
        // The attempt is written off the screen's scope; the count is only honest once it has landed.
        compose.waitUntil(15_000) { runBlocking { graph.db.attempts().since(0).any { it.itemId == seededId } } }
        compose.onNodeWithContentDescription("Πίσω").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Έκανες 1 άσκηση σήμερα: Λέξεις.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Μπράβο Δημήτρη!").assertIsDisplayed()
    }
}
