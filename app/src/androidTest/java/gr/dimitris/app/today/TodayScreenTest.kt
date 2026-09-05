package gr.dimitris.app.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import gr.dimitris.app.MainActivity
import org.junit.Rule
import org.junit.Test

class TodayScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun showsTitleAndStart() {
        compose.onNodeWithText("Δημήτρης").assertIsDisplayed()
        compose.onNodeWithText("Ξεκίνα").assertIsDisplayed()
    }

    @Test fun twoSecondHoldOnTitleAsksForCaregiverMode() {
        compose.onNodeWithTag("title").performTouchInput {
            down(center)
            advanceEventTime(CAREGIVER_HOLD_MS + 200)
            up()
        }
        compose.onNodeWithText("Λειτουργία φροντιστή;").assertIsDisplayed()
    }
}
