package gr.dimitris.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.Feedback
import gr.dimitris.app.ui.theme.LocalFeedback
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BigButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun isAtLeast72dpTallAndClicks() {
        var clicked = false
        compose.setContent {
            CompositionLocalProvider(LocalFeedback provides Feedback(ApplicationProvider.getApplicationContext())) {
                DimitrisTheme { BigButton(text = "Ξεκίνα", onClick = { clicked = true }) }
            }
        }
        compose.onNodeWithText("Ξεκίνα").assertHeightIsAtLeast(72.dp).performClick()
        assertTrue(clicked)
    }
}
