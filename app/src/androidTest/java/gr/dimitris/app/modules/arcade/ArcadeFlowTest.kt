package gr.dimitris.app.modules.arcade

import androidx.compose.ui.geometry.Offset
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
 * The things about a hand on glass that only a hand on glass can answer: that pressing the circle is
 * read as pressing it, that the circle gets smaller as he catches them, that passing on a game is
 * written down as passing on it — and that two fingers opening a photo and closing it again is the
 * movement the pinch game is asking for, which is the one gesture in the whole app no `adb input`
 * can make.
 */
class ArcadeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var wasEnabled = false
    private var sizesBefore = emptyMap<ArcadeGame, Float>()
    private var since = 0L

    /** The arcade is off until a physio says otherwise. A test that switches it on puts it back. */
    @Before fun switchTheArcadeOn() = runBlocking<Unit> {
        wasEnabled = ModuleId.ARCADE in graph.settings.enabledModules.first()
        sizesBefore = ArcadeGame.entries.associateWith { graph.settings.arcadeTargetDp(it).first() }
        graph.settings.setModuleEnabled(ModuleId.ARCADE, true)
        since = System.currentTimeMillis()
    }

    @After fun putItBack() = runBlocking<Unit> {
        graph.settings.setModuleEnabled(ModuleId.ARCADE, wasEnabled)
        sizesBefore.forEach { (game, size) -> graph.settings.setArcadeTargetDp(game, size) }
    }

    private fun sizeOf(game: ArcadeGame): Float = runBlocking { graph.settings.arcadeTargetDp(game).first() }

    @Test fun twelveTargetsCaughtIsARoundOfTheTapGame() {
        openPractice()
        repeat(TAP_TARGETS) { tapTheTarget() }

        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.itemId == "arcade:tap" } }
        val row = attempts().first { it.itemId == "arcade:tap" }
        assertEquals("a round he caught every target in was not his own work", Outcome.CORRECT, row.outcome)
        assertEquals(ModuleId.ARCADE, row.module)
        assertEquals("the arcade has no cue ladder", null, row.cueLevel)
        assertTrue("the hits are not on the row: ${row.detail}", row.detail.contains("\"hits\":12"))
        assertTrue("the size he ended at is not on the row: ${row.detail}", row.detail.contains("sizeDp"))
        // The whole point of the module: a hand that keeps catching them gets a smaller target, and
        // the smaller target is still there tomorrow.
        val after = sizeOf(ArcadeGame.TAP)
        assertTrue("the target did not shrink: $after", after < Adaptive.START)
        // And it is the tap game's own difficulty. Pressing a circle is the movement he keeps
        // longest; pinching is the one he loses first, and a good round of tapping must not drag the
        // photo down to the floor with it.
        assertEquals("a round of tapping moved another game's size", sizesBefore[ArcadeGame.PINCH], sizeOf(ArcadeGame.PINCH))
    }

    /** Passing on a game is evidence too. It is never silently nothing. */
    @Test fun skippingAGameIsWrittenDownAsASkip() {
        openPractice()
        compose.onNodeWithText(SKIP).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.itemId == "arcade:tap" } }
        assertEquals(Outcome.SKIPPED, attempts().first { it.itemId == "arcade:tap" }.outcome)
        // Skipped is not failed: the size he plays at is left exactly where it was.
        assertEquals(sizesBefore[ArcadeGame.TAP]!!, sizeOf(ArcadeGame.TAP), 0.001f)
        // And the sitting goes on to the next game rather than ending.
        compose.onNodeWithText(ArcadeGame.TRACE.prompt).assertIsDisplayed()
    }

    /**
     * Two fingers apart and back together. It is the hardest thing in the app for his hand and the
     * only pinch the app allows anywhere, so it is worth proving that the movement is read.
     */
    @Test fun openingAPhotoWithTwoFingersAndClosingItIsTheExercise() {
        openPractice()
        // Straight to the last game: the three before it are not what is being proved here.
        repeat(3) { compose.onNodeWithText(SKIP).performClick() }
        compose.onNodeWithText(ArcadeGame.PINCH.prompt).assertIsDisplayed()

        repeat(PINCH_ROUNDS) { pinchOpenAndClosed() }

        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.itemId == "arcade:pinch" } }
        val row = attempts().first { it.itemId == "arcade:pinch" }
        assertEquals("a photo opened and closed three times was not his own work", Outcome.CORRECT, row.outcome)
        assertTrue("the rounds are not on the row: ${row.detail}", row.detail.contains("\"hits\":3"))
        // Free practice ends on its own screen: it is the only ending there is.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(ArcadeViewModel.FINISHED)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Opens free practice from the Today grid and waits for a board with a target on it. */
    private fun openPractice() {
        // Switching it on above is a store write; the tile appears when the flow reaches the grid.
        compose.waitUntil(TIMEOUT_MS) { runBlocking { ModuleId.ARCADE in graph.settings.enabledModules.first() } }
        compose.waitForIdle()
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(ArcadeModule.titleGreek))
        compose.onNodeWithText(ArcadeModule.titleGreek).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(ARCADE_BOARD_TAG).fetchSemanticsNodes().any { it.size.width > 0 && it.size.height > 0 }
        }
    }

    /** Presses the circle wherever it has moved to. Its own node, so the tap is always on it. */
    private fun tapTheTarget() {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(ARCADE_TARGET_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ARCADE_TARGET_TAG).performClick()
    }

    /** Two fingers opening the photo wide and bringing it back: one round of the pinch game. */
    private fun pinchOpenAndClosed() {
        val board = compose.onNodeWithTag(ARCADE_BOARD_TAG).fetchSemanticsNode().size
        val midX = board.width / 2f
        val midY = board.height / 2f
        val near = board.width * 0.05f
        val far = board.width * 0.35f
        compose.onNodeWithTag(ARCADE_BOARD_TAG).performTouchInput {
            down(0, Offset(midX - near, midY))
            down(1, Offset(midX + near, midY))
            moveTo(0, Offset(midX - far, midY))
            moveTo(1, Offset(midX + far, midY))
            moveTo(0, Offset(midX - near, midY))
            moveTo(1, Offset(midX + near, midY))
            up(0)
            up(1)
        }
    }

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(since) }.filter { it.module == ModuleId.ARCADE }

    private companion object {
        const val SKIP = "Παράλειψη"
        const val TIMEOUT_MS = 20_000L
    }
}
