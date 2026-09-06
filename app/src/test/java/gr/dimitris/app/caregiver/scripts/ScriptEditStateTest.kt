package gr.dimitris.app.caregiver.scripts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When «Παίξ' το» may be pressed. The same rule the word editor's «Δοκίμασέ το» follows. */
class ScriptEditStateTest {
    private val saved = ScriptEditState(id = "script-1", title = "Στην καφετέρια")

    @Test fun `a saved dialogue can be played`() = assertTrue(saved.canTry)

    /** A dialogue she has only just typed too: the save happens under the button. */
    @Test fun `a dialogue she has only just typed can be played`() =
        assertTrue(ScriptEditState(title = "Στο περίπτερο", dirty = true).canTry)

    /** Even an empty one, so the tap can answer «Γράψε πρώτα έναν τίτλο». */
    @Test fun `an empty new form can still be tapped, so that it can answer`() =
        assertTrue(ScriptEditState().canTry)

    @Test fun `not while a line is being recorded`() =
        assertFalse(saved.copy(recordingIndex = 1).canTry)

    @Test fun `not while a save is in flight`() = assertFalse(saved.copy(saving = true).canTry)

    @Test fun `not while the dialogue is still being read`() =
        assertFalse(ScriptEditState(loading = true).canTry)

    /** A dialogue that is not there any more has nothing to play, and nothing to save either. */
    @Test fun `not for a dialogue that is gone`() =
        assertFalse(saved.copy(notFound = true).canTry)
}
