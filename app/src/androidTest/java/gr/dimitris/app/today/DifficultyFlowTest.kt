package gr.dimitris.app.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.ui.components.DIFFICULTY_DOT_TAG
import gr.dimitris.app.ui.components.DIFFICULTY_LABEL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Where the five dots are, and where they are not (spec §13).
 *
 * On the first screen of a module he opened himself, they are the answer to "this is too easy" that
 * he has no words for. Three exercises into a mixed session they would be a second decision in the
 * middle of the one he is already making, and the UX rule for this phase is one row and nothing else
 * added to any screen — so the session run has no row at all and uses what he set here.
 */
class DifficultyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var seededId: String? = null
    private var difficultyBefore = Difficulty.DEFAULT

    @Before fun seedOneDueWord() = runBlocking<Unit> {
        difficultyBefore = graph.settings.difficulty(ModuleId.WORDCOACH).first()
        val item = graph.items.save(Item(text = "νερό", category = Category.FOOD))
        seededId = item.id
        // Due, not new: a device that already met today's new-item quota would plan nothing and the
        // session would say «Τίποτα για σήμερα» before any module screen was drawn.
        graph.db.schedules().upsert(Schedule(itemId = item.id, module = ModuleId.WORDCOACH, nextDueAt = 0))
    }

    /** The word and the difficulty were ours, not his: put both back. */
    @After fun removeSeed() = runBlocking<Unit> {
        seededId?.let { graph.items.delete(it) }
        seededId = null
        graph.settings.setDifficulty(ModuleId.WORDCOACH, difficultyBefore)
    }

    @Test fun theRowIsOnTheFirstScreenOfPracticeAndOneTapSetsIt() {
        openPractice()
        compose.onNodeWithText(DIFFICULTY_LABEL).assertIsDisplayed()
        assertEquals("five dots, one per level", 5, compose.onAllNodesWithTag(DIFFICULTY_DOT_TAG).fetchSemanticsNodes().size)

        // Dot 4 rather than dot 1: it moves the stored value without narrowing the vocabulary, so
        // what this measures is the tap reaching the store and nothing about the word coach's tiers.
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 4").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.settings.difficulty(ModuleId.WORDCOACH).first() } == 4
        }
    }

    /** Not inside a session run: the sitting uses what he set, and asks him nothing. */
    @Test fun theRowIsNotInASessionRun() {
        compose.onNodeWithText("Ξεκίνα").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            "the dots are on the first screen of a module, never in the middle of a sitting",
            0, compose.onAllNodesWithTag(DIFFICULTY_DOT_TAG).fetchSemanticsNodes().size,
        )
    }

    private fun openPractice() {
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(TITLE))
        compose.onNodeWithText(TITLE).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TITLE = "Λέξεις"
        const val TIMEOUT_MS = 15_000L
    }
}
