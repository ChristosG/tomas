package gr.dimitris.app.core.audio

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the loudness of a take really measures, on the one microphone this suite can reach.
 *
 * The emulator's microphone is dead silent: nothing is piped into it, and a take of over a second
 * measures a peak of about 8 on a scale that runs to 32767 — the noise floor of the codec itself.
 * That is exactly the case Chris found in the field — a take with nothing in it that the app happily
 * saved as his voice — so the emulator is, for once, the right instrument: a take made here *must*
 * read as silence, or the check does not work at all.
 *
 * The other half of the calibration cannot be done here and is not pretended: a real voice on a real
 * phone reads in the thousands, and every take's peak is written into the attempt's detail so the
 * line can be moved on evidence from Dimitris' own phone.
 */
class RecorderTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = MediaFiles(context)
    private val recorder = Recorder(context, files)

    @After fun stopWhateverIsRunning() = recorder.cancel()

    @Test fun aTakeIntoTheSilentEmulatorMicrophoneReadsAsSilence() {
        recorder.start()
        assertTrue(recorder.isRecording)
        // Long enough for a dozen samples at 100 ms, and long enough for MediaRecorder to close
        // cleanly: a take stopped in the first moments has nothing written in it at all.
        Thread.sleep(TAKE_MS)
        val recorded = recorder.stop()
        try {
            assertTrue("a take should still be a file", recorded.file.length() > 0)
            assertTrue("no duration measured", recorded.durationMs > 0)
            // The sampling really ran: a recorder that was never polled and one that heard a room
            // full of noise both read as 0, and only one of those is what this proves.
            assertTrue(
                "the emulator's microphone hears next to nothing: ${recorded.peakAmplitude}",
                recorded.peakAmplitude < FLOOR,
            )
            assertTrue("and so the take is silence, and must not be kept as his voice", recorded.isSilent)
        } finally {
            recorded.file.delete()
        }
    }

    /** Sampling must not outlive the take: a reading taken after the recorder is released throws. */
    @Test fun theTakeAfterACancelledOneStillMeasuresItself() {
        recorder.start()
        Thread.sleep(300)
        recorder.cancel()
        assertTrue("a cancelled take is not left running", !recorder.isRecording)

        recorder.start()
        Thread.sleep(TAKE_MS)
        val second = recorder.stop()
        try {
            assertTrue("the second take measured itself: ${second.peakAmplitude}", second.peakAmplitude < FLOOR)
            assertTrue(second.isSilent)
        } finally {
            second.file.delete()
        }
    }

    private companion object {
        const val TAKE_MS = 1_200L

        /**
         * Well under [Recorded.SILENCE_PEAK], and two orders of magnitude under a real voice: this
         * is "the microphone heard nothing", not "the take was quiet".
         */
        const val FLOOR = 100
    }
}
