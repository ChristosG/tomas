package gr.dimitris.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.ui.components.BOUNDED_BY_CAREGIVER
import gr.dimitris.app.ui.components.DIFFICULTY_DOT_TAG
import gr.dimitris.app.ui.components.DIFFICULTY_LABEL
import gr.dimitris.app.ui.components.DifficultyRow
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.Feedback
import gr.dimitris.app.ui.theme.LocalFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The one control Dimitris has over how hard his own therapy is (spec §13), measured on a device:
 * five targets his thumb can land on, a tap that means what it says, and a refusal that explains
 * itself instead of doing nothing.
 */
class DifficultyRowTest {
    @get:Rule val compose = createComposeRule()

    private var picked: Int? = null

    private fun show(value: Int = 2, floor: Int = 1, ceiling: Int = 5) {
        compose.setContent {
            CompositionLocalProvider(LocalFeedback provides Feedback(ApplicationProvider.getApplicationContext())) {
                DimitrisTheme {
                    DifficultyRow(value = value, floor = floor, ceiling = ceiling, onChange = { picked = it })
                }
            }
        }
    }

    private fun dots() = compose.onAllNodesWithTag(DIFFICULTY_DOT_TAG)

    @Test fun fiveDotsEachAtLeast72dpTall() {
        show()
        assertEquals("five dots, one per level", 5, dots().fetchSemanticsNodes().size)
        // 72 dp is the app's touch floor. Five 72 dp-*wide* circles plus the label is wider than any
        // phone this runs on, so the height is what is promised and the cells share the width.
        repeat(5) { i -> dots()[i].assertHeightIsAtLeast(72.dp) }
        compose.onNodeWithText(DIFFICULTY_LABEL).assertIsDisplayed()
    }

    @Test fun oneTapInsideTheBoundsSetsTheValue() {
        show(value = 2)
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 4").performClick()
        assertEquals(4, picked)
        // And the other way, because "harder" is not the only direction he is allowed to go.
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 1").performClick()
        assertEquals(1, picked)
    }

    /**
     * Tapping the dot he is already on is not a change, and must not be reported as one.
     *
     * In four of the seven screens the callback rebuilds the sitting from scratch. In «Δεξί χέρι» the
     * row is on screen for the whole of the first round, so brushing the dot he is already on
     * mid-round ended the round with the hits lost; in «Γράψε» it wiped the strokes on the first
     * letter. He re-reads this row more than once.
     */
    @Test fun tappingTheDotHeIsAlreadyOnDoesNothing() {
        show(value = 3)
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 3").performClick()
        assertNull("a re-read of the row must not rebuild the sitting", picked)
        // …and the row still works for a dot that is a real change.
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 5").performClick()
        assertEquals(5, picked)
    }

    /** Which dot is set, and which are not his today, said out loud as well as drawn. */
    @Test fun theDotsSayWhichIsSetAndWhichAreFenced() {
        show(value = 3, floor = 2, ceiling = 4)
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 3").assertIsSelected()
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 2").assertIsNotSelected()
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 5").assertIsNotEnabled()
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 4").assertIsEnabled()
    }

    /**
     * A dot the caregiver has fenced off does not change the value, and does not silently do nothing
     * either: it says who decided. A tap that produces no response at all teaches him the row is
     * broken rather than that someone has set a limit.
     */
    @Test fun aTapOutsideTheBoundsIsRefusedAndSaysWhy() {
        show(value = 2, floor = 1, ceiling = 3)
        compose.onNodeWithText(BOUNDED_BY_CAREGIVER).assertDoesNotExist()

        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 5").performClick()
        assertNull("a fenced dot must not set the value", picked)
        compose.onNodeWithText(BOUNDED_BY_CAREGIVER).assertIsDisplayed()

        // Answering him clears the line: it is no longer about anything on the screen.
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 3").performClick()
        assertEquals(3, picked)
        compose.onNodeWithText(BOUNDED_BY_CAREGIVER).assertDoesNotExist()
    }

    /** A floor fences the easy end too: a bound is a window, not a ceiling. */
    @Test fun theFloorFencesTheEasyEnd() {
        show(value = 4, floor = 3, ceiling = 5)
        compose.onNodeWithContentDescription("$DIFFICULTY_LABEL 1").performClick()
        assertNull(picked)
        compose.onNodeWithText(BOUNDED_BY_CAREGIVER).assertIsDisplayed()
    }
}
