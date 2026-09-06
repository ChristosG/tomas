package gr.dimitris.app.caregiver.progress

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.scheduler.startOfDay
import gr.dimitris.app.today.CAREGIVER_HOLD_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val TIMEOUT_MS = 15_000L
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The dashboard on a device, from three days of history seeded through the daos. Only the things a
 * device can answer: that the caregiver area really reaches it, that the rows and one of the Greek
 * insight lines are on the screen, and that a stepper moves the level the modules will read.
 */
class ProgressScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var seeded: String? = null
    private var lockBefore = false
    private var numbersBefore = 1

    /**
     * Two attempts a day for three days in a row, so the streak is exactly three and its line is a
     * sentence a test can name. Yesterday and the day before are seeded at midday; today is seeded a
     * minute ago, because midday may not have happened yet.
     */
    @Before fun seedThreeDays() = runBlocking<Unit> {
        lockBefore = graph.settings.caregiverLock.first()
        numbersBefore = graph.settings.numbersLevel.first()
        graph.settings.setCaregiverLock(false)
        graph.settings.setNumbersLevel(2)

        val item = graph.items.save(Item(text = "καφές", category = Category.FOOD))
        seeded = item.id
        val midnight = startOfDay(now())
        listOf(now() - 60_000, midnight - DAY_MS + 12 * 3_600_000, midnight - 2 * DAY_MS + 12 * 3_600_000).forEach { at ->
            repeat(2) {
                graph.db.attempts().insert(
                    Attempt(itemId = item.id, module = ModuleId.WORDCOACH, startedAt = at, durationMs = 4_000,
                        outcome = Outcome.CORRECT, cueLevel = 1)
                )
            }
        }
        graph.db.sessions().upsert(
            Session(startedAt = now() - 600_000, endedAt = now() - 300_000, plannedModules = "WORDCOACH", plannedItemCount = 2, completedItemCount = 2)
        )
    }

    /** The word and the settings were ours, not his. */
    @After fun putItBack() = runBlocking<Unit> {
        seeded?.let { graph.items.delete(it) }
        seeded = null
        graph.settings.setCaregiverLock(lockBefore)
        graph.settings.setNumbersLevel(numbersBefore)
    }

    @Test fun theDashboardShowsTheModuleRowsAndWhatItMakesOfThem() {
        openProgress()
        compose.onNodeWithText("Λέξεις").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Σερί 3 ημερών. Συνέχισε έτσι!").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Σερί: 3 μέρες").performScrollTo().assertIsDisplayed()
    }

    @Test fun theStepperMovesTheLevelTheNumbersModuleWillRead() {
        openProgress()
        compose.onNodeWithTag("plus-numbers").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { runBlocking { graph.settings.numbersLevel.first() } == 3 }
        assertEquals(3, runBlocking { graph.settings.numbersLevel.first() })
        compose.onNodeWithTag("level-numbers").assertIsDisplayed()
    }

    /** Hold the name, say yes, tap the first entry — the way a caregiver gets here. */
    private fun openProgress() {
        compose.onNodeWithTag("title").performTouchInput {
            down(center)
            advanceEventTime(CAREGIVER_HOLD_MS + 200)
            up()
        }
        compose.onNodeWithText("Ναι").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Πρόοδος")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Πρόοδος").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Ανά άσκηση")).fetchSemanticsNodes().isNotEmpty() }
    }
}
