package gr.dimitris.app.caregiver.content

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * **And never a sung sentence.**
     *
     * «Δοκίμασέ το» runs the item through the word coach — `Routes.practiceItem` names that module for
     * any id and `PracticeViewModel.named` filters nothing — which is the one place a `SINGING` phrase
     * is written to stay out of: a twenty-syllable read-aloud is not a word to name under a picture.
     * The caregiver opens exactly these rows, because the sung take is recorded on this form. The hint
     * under the chips is what stops it being a button with no explanation.
     */
    @Test fun `a sung sentence cannot be run through the word coach`() {
        val sung = saved.copy(
            text = "Μπορείτε να μου πείτε πού είναι το φαρμακείο;",
            kind = ItemKind.PHRASE, category = Category.SINGING,
        )
        assertFalse(sung.canTry)
        assertTrue("and the form knows to say why", sung.isSungSentence)
        // It is the pair that excludes it. A word somebody filed under «Τραγούδι» is still a word, and
        // a phrase in any other category is still the talk board's and the word coach's.
        assertTrue(sung.copy(kind = ItemKind.WORD).canTry)
        assertTrue(sung.copy(category = Category.CUSTOM).canTry)
    }

    // ------------------------------------------- the two gradings the form writes onto the row

    /**
     * A word she has not graded is tier 1 and no gender, which is exactly where every word already
     * on the phone is: in reach from every dot, and read by the ending as it always was.
     */
    @Test fun `a form nobody has graded writes the easiest tier and no gender`() {
        assertEquals(Item.DEFAULT_TIER, ItemEditState().savedTier)
        assertNull(ItemEditState().savedGender)
    }

    /** Whatever a stepper, a backup or a sync put in the form, the row gets 1 to 5. */
    @Test fun `the tier that reaches the row is inside the row of dots`() {
        assertEquals(5, saved.copy(tier = 5).savedTier)
        assertEquals(1, saved.copy(tier = 0).savedTier)
        assertEquals(5, saved.copy(tier = 9).savedTier)
    }

    /**
     * The chips are drawn for a word alone, so a gender left behind by a switch to «Φράση» is a tap
     * she took back by changing her mind — the same rule the sung take has, which is deleted rather
     * than attached when the kind switches under it. A phrase takes no article.
     */
    @Test fun `only a word carries a gender to the database`() {
        assertEquals("F", saved.copy(gender = "F").savedGender)
        assertNull(saved.copy(kind = ItemKind.PHRASE, gender = "F").savedGender)
    }
}
