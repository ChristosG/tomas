package gr.dimitris.app.caregiver.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When «Δοκίμασέ το» may be pressed.
 *
 * The recording guard is the one that matters and the one that was missing. Leaving this screen
 * with the microphone open has the practice screen cancel the take on its way out, while this form
 * still believes it is recording: «Στοπ» is then on the screen for a take that no longer exists,
 * and pressing it lands in the recorder's failure branch. The dialogue editor has always refused
 * to leave with the microphone open; this is the same refusal, said here.
 */
class ItemEditStateTest {
    private val saved = ItemEditState(id = "item-1", text = "νερό")

    @Test fun `a saved word can be run`() = assertTrue(saved.canTry)

    /**
     * A draft that has never been saved can be run too: the save happens under the button, without
     * closing the editor. A dead button here was the two-tap detour Chris was left with.
     */
    @Test fun `a word she has only just typed can be run`() =
        assertTrue(ItemEditState(text = "παγωτό", dirty = true).canTry)

    /** Even an empty one: the tap is what produces «Γράψε τη λέξη πρώτα», and a dead button says nothing. */
    @Test fun `an empty new form can still be tapped, so that it can answer`() =
        assertTrue(ItemEditState().canTry)

    @Test fun `not while the model voice is being recorded`() =
        assertFalse(saved.copy(isRecording = true).canTry)

    @Test fun `not while the sung take is being recorded`() =
        assertFalse(saved.copy(isRecordingSung = true).canTry)

    /** A save in flight owns the row: running it now would open whatever half of it had landed. */
    @Test fun `not while a save is in flight`() = assertFalse(saved.copy(saving = true).canTry)

    /** A refusal she has read does not take the button away — she may fix it and try again. */
    @Test fun `an error on the form does not stop it`() =
        assertTrue(saved.copy(error = "Η τιμή θέλει μορφή 3,50").canTry)
}
