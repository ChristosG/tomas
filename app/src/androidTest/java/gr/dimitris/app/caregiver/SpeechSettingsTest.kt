package gr.dimitris.app.caregiver

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.speech.FakeSpeechToText
import gr.dimitris.app.core.speech.OnDeviceSupport
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.LocalFeedback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The caregiver's recognition row, on the four things it can say about Greek without a connection.
 *
 * The row is the answer to the second half of Chris' report. His phone said «Η αναγνώριση δεν
 * λειτούργησε. Δες τις ρυθμίσεις.» and the settings it sent him to said nothing at all about the
 * Greek model and offered nothing to do about it. Now the screen answers: installed, missing and one
 * button away, being fetched, or not something this phone can do.
 *
 * The first case is the honest bottom of the ladder and is the one the **real** emulator gives: it
 * has no recognition service at all, so «δεν υποστηρίζεται» and no button. The other three are driven
 * through [FakeSpeechToText], because an emulator with no engine cannot be made to have Greek — only
 * Chris' Samsung can say whether the download button really downloads anything.
 */
class SpeechSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private val fake = FakeSpeechToText()
    private lateinit var realStt: SpeechToText

    @Before fun remember() { realStt = graph.stt }

    @After fun putItBack() { graph.stt = realStt }

    /**
     * The real recogniser on this emulator, untouched. There is no speech engine here, so
     * [OnDeviceSupport.decide] is never even reached — `isRecognitionAvailable` is false and the
     * engine is [OnDeviceSupport.Engine.NONE] — and the row says the one true thing and offers
     * nothing. A download button on a phone that cannot use it would be a button that lies.
     */
    @Test fun anEngineLessPhoneSaysGreekIsNotSupportedAndOffersNoDownload() {
        show()

        compose.waitUntil(TIMEOUT_MS) { shown(UNSUPPORTED) }
        // The settings screen is one long scroll and the recognition section sits well down it: the
        // row has to be scrolled to before "is it on the screen" is a question with an answer.
        compose.onNodeWithTag(LISTEN_STATE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(UNSUPPORTED).assertIsDisplayed()
        compose.onNodeWithText(OnDeviceSupport.DOWNLOAD).assertDoesNotExist()
    }

    /** Greek on the phone: free, offline, and the one speech control. Nothing left to ask for. */
    @Test fun greekInstalledIsSaidAndNothingIsAskedFor() {
        fake.engine = OnDeviceSupport.Engine.ON_DEVICE
        graph.stt = fake
        show()

        compose.waitUntil(TIMEOUT_MS) { shown(INSTALLED) }
        compose.onNodeWithText(OnDeviceSupport.DOWNLOAD).assertDoesNotExist()
    }

    /**
     * The case that was Chris' code 12. The engine knows Greek and has not fetched it, so the row
     * says so and puts one 72dp button under the line — the whole fix, in a caregiver's thumb.
     */
    @Test fun aMissingGreekModelIsOneButtonAway() {
        fake.engine = OnDeviceSupport.Engine.NEEDS_DOWNLOAD
        graph.stt = fake
        show()

        compose.waitUntil(TIMEOUT_MS) { shown(MISSING) }
        compose.onNodeWithText(OnDeviceSupport.DOWNLOAD).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(OnDeviceSupport.DOWNLOAD).performClick()
        compose.waitUntil(TIMEOUT_MS) { fake.downloads == 1 }
    }

    /** A download the engine was already running when the screen opened: «λήψη…», and no button. */
    @Test fun aDownloadAlreadyRunningIsSaidRatherThanOfferedAgain() {
        fake.engine = OnDeviceSupport.Engine.NEEDS_DOWNLOAD
        fake.pending = true
        graph.stt = fake
        show()

        compose.waitUntil(TIMEOUT_MS) { shown(DOWNLOADING) }
        compose.onNodeWithText(OnDeviceSupport.DOWNLOAD).assertDoesNotExist()
        assertEquals("nothing was asked for: it is already happening", 0, fake.downloads)
    }

    private fun show() {
        compose.runOnUiThread {
            compose.activity.setContent {
                DimitrisTheme {
                    CompositionLocalProvider(LocalAppGraph provides graph, LocalFeedback provides graph.feedback) {
                        SettingsScreen(onBack = {})
                    }
                }
            }
        }
    }

    private fun shown(text: String) =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        val INSTALLED = OnDeviceSupport.lineFor(OnDeviceSupport.Greek.INSTALLED)
        val MISSING = OnDeviceSupport.lineFor(OnDeviceSupport.Greek.MISSING)
        val DOWNLOADING = OnDeviceSupport.lineFor(OnDeviceSupport.Greek.DOWNLOADING)
        val UNSUPPORTED = OnDeviceSupport.lineFor(OnDeviceSupport.Greek.UNSUPPORTED)
        const val TIMEOUT_MS = 20_000L
    }
}
