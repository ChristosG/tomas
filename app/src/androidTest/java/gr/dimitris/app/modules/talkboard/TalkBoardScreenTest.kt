package gr.dimitris.app.modules.talkboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import gr.dimitris.app.MainActivity
import org.junit.Rule
import org.junit.Test

class TalkBoardScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensFromTodayAndShowsFavouritesTab() {
        compose.onNodeWithText("Μίλα").performClick()
        compose.onNodeWithText("Αγαπημένα").assertIsDisplayed()
        compose.onNodeWithText("Πες το").assertIsDisplayed()
    }
}
