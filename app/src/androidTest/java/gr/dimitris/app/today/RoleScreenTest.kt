package gr.dimitris.app.today

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.settings.DeviceRole
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.Feedback
import gr.dimitris.app.ui.theme.LocalFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The one question the app ever asks about itself. Both answers are 72dp targets, because a
 * caregiver's thumb and Dimitris' left hand are the only two that will ever tap them.
 */
class RoleScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<DimitrisApp>()

    /** Back to what the runner set up, so the next test opens on Today like every other one. */
    @After fun restore() = runBlocking { app.graph.settings.setDeviceRole(DeviceRole.DIMITRIS) }

    private fun show(onChosen: (DeviceRole) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalAppGraph provides app.graph,
                LocalFeedback provides Feedback(app),
            ) {
                DimitrisTheme { RoleScreen(onChosen = onChosen) }
            }
        }
    }

    @Test fun bothAnswersAreBigEnoughToTap() {
        show { }
        compose.onNodeWithTag("roleTitle").assertIsDisplayed()
        compose.onNodeWithText("Του Δημήτρη").assertHeightIsAtLeast(72.dp)
        compose.onNodeWithText("Φροντιστή").assertHeightIsAtLeast(72.dp)
    }

    @Test fun caregiverIsStoredAndReported() {
        var chosen: DeviceRole? = null
        show { chosen = it }

        compose.onNodeWithText("Φροντιστή").performClick()

        compose.waitUntil(5_000) { chosen != null }
        assertEquals(DeviceRole.CAREGIVER, chosen)
        assertEquals(DeviceRole.CAREGIVER, runBlocking { app.graph.settings.deviceRole.first() })
        assertEquals(true, runBlocking { app.graph.settings.rolePick.first() }.chosen)
    }

    @Test fun hisOwnPhoneIsStoredAndReported() {
        var chosen: DeviceRole? = null
        show { chosen = it }

        compose.onNodeWithText("Του Δημήτρη").performClick()

        compose.waitUntil(5_000) { chosen != null }
        assertEquals(DeviceRole.DIMITRIS, chosen)
        assertEquals(DeviceRole.DIMITRIS, runBlocking { app.graph.settings.deviceRole.first() })
    }
}
