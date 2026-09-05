package gr.dimitris.app.modules.talkboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import gr.dimitris.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TalkBoardScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensFromTodayAndShowsFavouritesTab() {
        compose.onNodeWithText("Μίλα").performClick()
        compose.onNodeWithText("Αγαπημένα").assertIsDisplayed()
        compose.onNodeWithText("Πες το").assertIsDisplayed()
    }

    /** The whole loop a caregiver would try first: pick a word, hear the sentence, take it back. */
    @Test fun tapFillsStripSpeaksAndBackspaceEmpties() {
        compose.onNodeWithText("Μίλα").performClick()
        // The seed import runs on the app's own scope; the food tab appears once it lands.
        compose.waitUntil(SEED_TIMEOUT_MS) { compose.onAllNodesWithText(FOOD_TAB).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(FOOD_TAB).performClick()

        compose.onNodeWithTag(BOARD_GRID_TAG).performScrollToNode(hasText(WORD))
        assertEquals("only the card should say it before the tap", 1, compose.onAllNodesWithText(WORD).fetchSemanticsNodes().size)
        compose.onAllNodesWithText(WORD)[0].performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(WORD).fetchSemanticsNodes().size == 2 }

        compose.onNodeWithText("Πες το").performClick()

        compose.onNodeWithContentDescription("Σβήσε το τελευταίο").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(WORD).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Πάτα εικόνες για να φτιάξεις πρόταση.").assertIsDisplayed()
    }

    private companion object {
        const val FOOD_TAB = "Φαγητό & ποτό"
        const val WORD = "καφές"
        const val SEED_TIMEOUT_MS = 30_000L
        const val UI_TIMEOUT_MS = 5_000L
    }
}
