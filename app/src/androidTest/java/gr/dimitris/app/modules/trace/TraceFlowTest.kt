package gr.dimitris.app.modules.trace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.today.MODULE_GRID_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The things about writing that only a finger on glass can answer: that a trace along the letter is
 * read as that letter, and that one nowhere near it is a nudge and another go rather than a failure
 * or a row in his record.
 *
 * The test traces the same points the app drew, which is the point of [Glyphs] handing out one list
 * for both jobs: if what he is shown and what he is marked on ever came apart, this is where it
 * would show.
 */
class TraceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var levelBefore = 1
    private var since = 0L

    /** Level 1 is one capital, which is the only target a swipe can be aimed at in a test. */
    @Before fun startAtLevelOne() = runBlocking<Unit> {
        levelBefore = graph.settings.traceLevel.first()
        graph.settings.setTraceLevel(1)
        since = System.currentTimeMillis()
    }

    /** His level is his; a test that borrows it puts it back. */
    @After fun restoreLevel() = runBlocking<Unit> { graph.settings.setTraceLevel(levelBefore) }

    @Test fun goingAlongTheLetterIsWritingIt() {
        val letter = openPractice()
        traceAlong(letter)
        compose.onNodeWithText("Έτοιμο").performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val attempt = attempts().single()
        assertEquals("a traced letter is his own answer", Outcome.CORRECT, attempt.outcome)
        // A random capital is not an item anything can look up, so the row is about the level.
        assertEquals("trace:level:1", attempt.itemId)
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }

    @Test fun aTraceNowhereNearTheLetterIsANudgeAndAnotherGo() {
        openPractice()
        val (width, height) = canvasSize()
        // A short line along the top edge: too far from the letter, and far too little of it.
        compose.onNodeWithTag(TRACE_CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.02f, height * 0.02f))
            moveTo(Offset(width * 0.10f, height * 0.02f))
            moveTo(Offset(width * 0.20f, height * 0.02f))
            up()
        }
        compose.onNodeWithText("Έτοιμο").performClick()

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(TraceViewModel.TRY_AGAIN)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(TraceViewModel.TRY_AGAIN).assertIsDisplayed()
        // Never a fail state: nothing is written until a letter is finished or passed on, and the
        // letter is still there to be gone over again.
        assertTrue("a poor trace was recorded as an attempt", attempts().isEmpty())
        compose.onNodeWithText("Έτοιμο").assertIsDisplayed()
    }

    /** Opens free practice and returns the letter it is asking for. */
    private fun openPractice(): String {
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(TITLE))
        compose.onNodeWithText(TITLE).performClick()
        // The level read has to land before there is a letter, and its canvas before there is paper.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(TRACE_CANVAS_TAG).fetchSemanticsNodes().any { it.size.width > 0 && it.size.height > 0 }
        }
        val text = compose.onNodeWithTag(TRACE_TEXT_TAG).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        assertTrue("nothing to write", !text.isNullOrBlank())
        return text!!
    }

    /** The canvas as the app measured it, which is the box the letter was laid out in. */
    private fun canvasSize(): Pair<Float, Float> {
        val size = compose.onNodeWithTag(TRACE_CANVAS_TAG).fetchSemanticsNode().size
        return size.width.toFloat() to size.height.toFloat()
    }

    /** One finger along the whole outline of [letter], as the screen is showing it. */
    private fun traceAlong(letter: String) {
        val (width, height) = canvasSize()
        val outline = Glyphs.template(letter, width, height).first
        assertTrue("«$letter» has no outline in a ${width}x$height box", outline.size > 20)
        // Every other point: the gaps are a third of a letter stroke, and half as many events.
        val path = outline.filterIndexed { i, _ -> i % 2 == 0 }
        compose.onNodeWithTag(TRACE_CANVAS_TAG).performTouchInput {
            down(Offset(path.first().x, path.first().y))
            path.forEach { moveTo(Offset(it.x, it.y)) }
            up()
        }
    }

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(since) }.filter { it.module == ModuleId.TRACE }

    private companion object {
        const val TITLE = "Γράψε"
        const val TIMEOUT_MS = 15_000L
    }
}
