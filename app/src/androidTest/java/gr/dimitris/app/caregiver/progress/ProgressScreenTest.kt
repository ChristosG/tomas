package gr.dimitris.app.caregiver.progress

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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

/** Ours, and not words the seed vocabulary contains, so the assertions cannot match his. */
private const val SEEDED_WORD = "καφές δοκιμής προόδου"
private const val BOARD_WORD = "νερό δοκιμής πίνακα"
private const val WINDOW_CAPTION = "Τελευταίες 4 εβδομάδες"

private fun hasTextStartingWith(prefix: String) =
    SemanticsMatcher("text starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) }
    }

/**
 * The dashboard on a device, from three days of history seeded through the daos. Only the things a
 * device can answer: that the caregiver area really reaches it, that the rows and one of the Greek
 * insight lines are on the screen, and that a stepper moves the level the modules will read.
 */
class ProgressScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var seeded: String? = null

    /** Ours, so the teardown finds exactly the sitting it inserted and no other. */
    private val sessionId = "progress-screen-test-session"
    private var lockBefore = false
    private var numbersBefore = 1

    /**
     * Two attempts a day for three days in a row, so there is a streak and a module row to look at.
     *
     * Today's pair is seeded an hour after midnight, or now if that hour has not happened yet: a run
     * that starts within a minute of midnight would otherwise put "today" on yesterday.
     *
     * Nothing here assumes the database is empty. The test asserts on the word it inserted and on
     * the fact that there is a streak line at all, never on a number an earlier test's leftover
     * attempts could move — a suite that goes red for reasons that have nothing to do with the code
     * is worse than no suite.
     */
    @Before fun seedThreeDays() = runBlocking<Unit> {
        lockBefore = graph.settings.caregiverLock.first()
        numbersBefore = graph.settings.numbersLevel.first()
        graph.settings.setCaregiverLock(false)
        graph.settings.setNumbersLevel(2)

        val item = graph.items.save(Item(text = SEEDED_WORD, category = Category.FOOD))
        seeded = item.id
        val midnight = startOfDay(now())
        val today = minOf(midnight + 3_600_000, now())
        listOf(today, midnight - DAY_MS + 12 * 3_600_000, midnight - 2 * DAY_MS + 12 * 3_600_000).forEach { at ->
            repeat(2) {
                graph.db.attempts().insert(
                    Attempt(itemId = item.id, module = ModuleId.WORDCOACH, startedAt = at, durationMs = 4_000,
                        outcome = Outcome.CORRECT, cueLevel = 1)
                )
            }
        }
        graph.db.sessions().upsert(
            Session(id = sessionId, startedAt = now() - 600_000, endedAt = now() - 300_000,
                plannedModules = "WORDCOACH", plannedItemCount = 2, completedItemCount = 2)
        )
    }

    /** The word, the sitting and the settings were ours, not his. */
    @After fun putItBack() = runBlocking<Unit> {
        seeded?.let { graph.items.delete(it) }
        seeded = null
        graph.db.sessions().get(sessionId)?.let { graph.db.sessions().upsert(it.copy(deleted = true)) }
        graph.settings.setCaregiverLock(lockBefore)
        graph.settings.setNumbersLevel(numbersBefore)
    }

    @Test fun theDashboardShowsTheModuleRowsAndWhatItMakesOfThem() {
        openProgress()
        compose.onNodeWithText("Λέξεις").performScrollTo().assertIsDisplayed()
        // A streak line, not a particular number of days: the three seeded days are the floor, and
        // an earlier test's leftover attempts can only make the streak longer.
        compose.onNode(hasTextStartingWith("Σερί: ")).performScrollTo().assertIsDisplayed()
        compose.onNode(hasTextStartingWith("Σερί ")).performScrollTo().assertIsDisplayed()
        // And the period the numbers under it cover, which the screen used not to say at all.
        compose.onAllNodesWithText(WINDOW_CAPTION).onFirst().performScrollTo().assertIsDisplayed()
    }

    /** «Μίλα» is a count of taps: it must never wear a percentage next to «Λέξεις 62% σωστά». */
    @Test fun theTalkBoardIsCountedButNotScored() {
        val boardWord = runBlocking { graph.items.save(Item(text = BOARD_WORD, category = Category.FOOD)) }
        try {
            runBlocking {
                repeat(3) {
                    graph.db.attempts().insert(
                        Attempt(itemId = boardWord.id, module = ModuleId.TALKBOARD, startedAt = now() - 30_000,
                            durationMs = 1_000, outcome = Outcome.CORRECT)
                    )
                }
            }
            openProgress()
            compose.onNodeWithText("Μίλα").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("πίνακας").performScrollTo().assertIsDisplayed()
        } finally {
            runBlocking { graph.items.delete(boardWord.id) }
        }
    }

    @Test fun theStepperMovesTheLevelTheNumbersModuleWillRead() {
        openProgress()
        compose.onNodeWithTag("plus-numbers").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { runBlocking { graph.settings.numbersLevel.first() } == 3 }
        assertEquals(3, runBlocking { graph.settings.numbersLevel.first() })
        compose.onNodeWithTag("level-numbers").assertIsDisplayed()
    }

    /**
     * Two taps as fast as the machine can send them. The screen computes the next level from the
     * state it is showing, so before the optimistic update the second tap was computed from the old
     * number, wrote the same level twice and was silently lost — and this is the control that
     * decides which exercises Dimitris is handed.
     */
    @Test fun twoFastTapsMoveTheLevelTwice() {
        openProgress()
        val plus = compose.onNodeWithTag("plus-numbers")
        plus.performScrollTo().performClick()
        plus.performClick()

        compose.waitUntil(TIMEOUT_MS) { runBlocking { graph.settings.numbersLevel.first() } == 4 }
        assertEquals(4, runBlocking { graph.settings.numbersLevel.first() })
        compose.onNodeWithTag("level-numbers").assertTextEquals("4")
    }

    /**
     * «Εξαγωγή αναφοράς» shares the same text the advice screen sends — which means it also carries
     * the other caregiver's notes about the dentist, the sleep and the supermarket, and this screen
     * has no «Τι θα σταλεί» section to read them in first. So it says so, and waits to be told yes.
     */
    @Test fun sharingTheReportSaysWhatItIsAboutToHandOver() {
        openProgress()
        compose.onNodeWithTag("share-report").performClick()

        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(SHARE_WARNING)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(SHARE_WARNING).assertIsDisplayed()
        compose.onNodeWithText("Μοιράσου").assertIsDisplayed()

        // «Άκυρο» hands nothing over: the chooser never opens and the dialog goes away.
        compose.onNodeWithText("Άκυρο").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(SHARE_WARNING)).fetchSemanticsNodes().isEmpty()
        }
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
