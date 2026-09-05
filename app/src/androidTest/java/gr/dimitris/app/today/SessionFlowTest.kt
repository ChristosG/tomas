package gr.dimitris.app.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // runBlocking<Unit>: JUnit needs a void @Before, and ItemRepository.save returns the saved Item.
    @Before fun seedOneWord() = runBlocking<Unit> {
        val graph = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
        val item = graph.items.save(Item(text = "νερό", category = Category.FOOD))
        // Due, not new: a device that already met today's new-item quota (a real session earlier
        // the same day) would otherwise plan nothing and the session would say "Τίποτα για σήμερα".
        graph.db.schedules().upsert(Schedule(itemId = item.id, module = ModuleId.WORDCOACH, nextDueAt = 0))
    }

    @Test fun startRunsWordCoachAndConfirmAdvances() {
        compose.onNodeWithText("Ξεκίνα").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Βοήθεια").assertIsDisplayed()
        compose.onNodeWithText("Το είπα!").performClick()
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }
}
