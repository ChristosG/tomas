package gr.dimitris.app.modules.numbers

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The two things about the numbers module that only a device can answer: that a wrong tap leads
 * somewhere, and that the number line is a line.
 */
class NumbersFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var levelBefore = 1

    @Before fun rememberLevel() = runBlocking<Unit> { levelBefore = graph.settings.numbersLevel.first() }

    /** His level is his; a test that borrows it puts it back. */
    @After fun restoreLevel() = runBlocking<Unit> { graph.settings.setNumbersLevel(levelBefore) }

    @Test fun twoWrongTapsShowTheAnswerAndOfferNext() {
        openPracticeAt(1)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(COMPARE_PROMPT).fetchSemanticsNodes().isNotEmpty() }
        val wrong = numericOptions().min()

        compose.onNodeWithText(wrong.toString()).performClick()
        // One miss is a nudge and another go, not a dead end and not the answer.
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Ξανά.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("no way forward after one miss", 0, compose.onAllNodesWithText("Επόμενο").fetchSemanticsNodes().size)

        compose.onNodeWithText(wrong.toString()).performClick()
        // Two misses: the answer is shown and said, and the only thing left to do is move on.
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Επόμενο").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Να το σωστό.").assertIsDisplayed()

        // And it is recorded as helped, which is what lets the progression step him back down.
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.db.attempts().since(0).any { it.itemId == "numbers:level:1" && it.outcome == Outcome.ASSISTED } }
        }
    }

    @Test fun theNumberLineIsALineWithThreePlacesToTap() {
        openPracticeAt(2)
        compose.waitUntil(TIMEOUT_MS) { places().fetchSemanticsNodes().isNotEmpty() }
        assertEquals("three places, not eleven buttons", ExerciseGenerator.SMALL_LINE_CANDIDATES, places().fetchSemanticsNodes().size)
        // The ends and the middle are labelled; nothing else is, or he would be matching glyphs.
        listOf("0", "5", "10").forEach { assertTrue("no $it on the line", compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()) }

        places().onFirst().performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Ξανά.").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Επόμενο").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openPracticeAt(level: Int) {
        runBlocking { graph.settings.setNumbersLevel(level) }
        compose.onNodeWithText("Αριθμοί").performClick()
    }

    /** The answer buttons: the only clickable things on an exercise whose whole label is a number. */
    private fun numericOptions(): List<Int> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.trim()?.toIntOrNull() }

    private fun places() = compose.onAllNodes(
        SemanticsMatcher("a place on the number line") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(PLACE_PREFIX) } == true
        },
    )

    private companion object {
        const val COMPARE_PROMPT = "Ποιο είναι περισσότερο;"
        const val PLACE_PREFIX = "Θέση"
        const val TIMEOUT_MS = 15_000L
    }
}
