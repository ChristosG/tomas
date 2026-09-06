package gr.dimitris.app.core.audio

import gr.dimitris.app.core.audio.Recorded.Companion.SILENCE_PEAK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chris' second finding from the field: recording a take never checked anything, so a silent take
 * "passed" and was saved as his voice. The line between silence and a voice is one number, and this
 * is where it is pinned.
 */
class RecordedTest {
    private fun take(peak: Int) = Recorded(File("δοκιμή.m4a"), durationMs = 1_000, peakAmplitude = peak)

    /** The emulator's dead microphone, and the state a take is in before anything measured it. */
    @Test fun `a take with no sound in it at all is silence`() = assertTrue(take(0).isSilent)

    @Test fun `room noise is silence`() = assertTrue(take(400).isSilent)

    /** A quiet voice at arm's length on a real phone reads in the low thousands. */
    @Test fun `a quiet voice is not silence`() = assertFalse(take(3_000).isSilent)

    @Test fun `a voice close to the phone is not silence`() = assertFalse(take(20_000).isSilent)

    @Test fun `the line itself counts as a voice`() = assertFalse(take(SILENCE_PEAK).isSilent)

    @Test fun `one below the line does not`() = assertTrue(take(SILENCE_PEAK - 1).isSilent)

    /**
     * A [Recorded] built without a measurement reads as silence rather than quietly passing a check
     * it was never measured for.
     */
    @Test fun `an unmeasured take is silence`() {
        val old = Recorded(File("παλιά.m4a"), durationMs = 1_000)
        assertEquals(0, old.peakAmplitude)
        assertTrue(old.isSilent)
    }

    @Test fun `the calibrated line and the words said about it`() {
        assertEquals(1_500, SILENCE_PEAK)
        assertEquals("Δεν σε άκουσα. Πες το πιο δυνατά.", Recorded.SILENT_TAKE)
    }
}
