package gr.dimitris.app.caregiver.scripts

import gr.dimitris.app.core.data.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // --- what a save really writes ----------------------------------------------------------------

    /**
     * The dialogue's tier goes onto every one of its turns — a dialogue is as hard as its hardest
     * line, and the module reads the maximum back out — and the «Σκοπός» goes with the turn it was
     * typed under.
     */
    @Test fun `every turn carries the dialogue's tier and its own purpose`() {
        val his = EditLine(Speaker.DIMITRIS, "Στο σπίτι είμαι.", intent = "λέει πού είναι")
        val draft = his.draft(tier = 4, file = null)
        assertEquals(4, draft.tier)
        assertEquals("λέει πού είναι", draft.intent)
        assertEquals(Speaker.DIMITRIS, draft.speaker)
        assertEquals("Στο σπίτι είμαι.", draft.text)
    }

    /** A «Σκοπός» she never filled in is nothing to say, not an empty demand on his answer. */
    @Test fun `a blank purpose is written as nothing at all`() {
        assertNull(EditLine(Speaker.DIMITRIS, "Ναι.").draft(tier = 1, file = null).intent)
        assertNull(EditLine(Speaker.DIMITRIS, "Ναι.", intent = "   ").draft(tier = 1, file = null).intent)
    }

    /**
     * A line toggled to the other speaker keeps what she typed under it.
     *
     * The field is only shown on his turns and nothing ever reads an intent off a line the other
     * person says, so this value is inert — but it used to survive a save and not a re-open, which
     * is a form quietly dropping her typing. It is kept in both halves now, or in neither.
     */
    @Test fun `a line toggled to the other speaker keeps what she wrote under it`() {
        val toggled = EditLine(Speaker.DIMITRIS, "Ναι, θα έρθω.", intent = "απαντάει αν θα πάει")
            .copy(speaker = Speaker.OTHER)
        assertEquals("απαντάει αν θα πάει", toggled.draft(tier = 2, file = null).intent)
    }

    /** A take she made in this pass is the one the row gets; otherwise the length it already had. */
    @Test fun `the draft carries the take the line has`() {
        val line = EditLine(Speaker.OTHER, "Καλημέρα.", recordingPath = "recordings/a.m4a", recordingMs = 700)
        val draft = line.draft(tier = 1, file = File("/tmp/a.m4a"))
        assertEquals(700, draft.recordingMs)
        assertEquals("/tmp/a.m4a", draft.recordingFile?.path)
    }
}
