package gr.dimitris.app.modules.arcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadePathsTest {
    private val width = 900f
    private val height = 1200f
    private val margin = 80f

    @Test fun `there is one path per round of the game`() {
        assertEquals(TRACE_PATHS, ArcadePaths.all(width, height, margin).size)
    }

    /** A line that runs under the edge of the board is a line he cannot follow to the end. */
    @Test fun `every path stays inside the board`() {
        for (path in ArcadePaths.all(width, height, margin)) {
            for (p in path) {
                assertTrue("x off the board: ${p.x}", p.x >= 0f && p.x <= width)
                assertTrue("y off the board: ${p.y}", p.y >= 0f && p.y <= height)
            }
        }
    }

    /** Evenly spaced, because that is what the scorer measures against and the screen draws. */
    @Test fun `every path is enough points to be a line`() {
        for (path in ArcadePaths.all(width, height, margin)) assertTrue(path.size > 10)
    }

    /** A board measured before layout, or one too small to hold a line, has no game in it yet. */
    @Test fun `an unmeasured board has no paths`() {
        assertTrue(ArcadePaths.all(0f, 0f, margin).isEmpty())
    }

    /** The margin cannot eat the board: a huge margin is cut back, not turned into an empty round. */
    @Test fun `a margin bigger than the board is cut back to something drawable`() {
        assertEquals(TRACE_PATHS, ArcadePaths.all(400f, 400f, 9000f).size)
    }
}
