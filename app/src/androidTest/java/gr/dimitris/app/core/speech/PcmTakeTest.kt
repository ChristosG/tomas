package gr.dimitris.app.core.speech

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.audio.Wav
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real microphone, a real file: the half of the one speech control that a device can prove.
 *
 * What the emulator cannot prove is the other half — there is no speech engine here to read the pipe,
 * so only Chris' Samsung can say whether the on-device recogniser really transcribes what it is fed.
 * Everything on this side of the pipe is provable, and is what would break silently if it were wrong:
 * that the microphone opens at 16 kHz mono PCM16, that the file it leaves behind is a valid WAV with
 * its real length in the header, that the peak it reports is measured from the samples, and that a
 * pipe nobody ever reads — exactly what happens on this emulator — cannot stop the take completing.
 *
 * The emulator's microphone is dead silent, which makes it a good calibration rig for the other
 * direction: the peak of a second of it has to read as silence, well under [Recorded.SILENCE_PEAK],
 * or a take nobody spoke into would be kept and played back to him as his own voice.
 */
@RunWith(AndroidJUnit4::class)
class PcmTakeTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private val made = mutableListOf<File>()

    @After fun clean() { made.forEach { it.delete() } }

    private fun newFile(): File = graph.files.newWavFile().also { made += it }

    /**
     * One second of the emulator's silent microphone, with no pipe at all, and everything that has to
     * be true of what it leaves behind.
     */
    @Test fun aSecondOfSilenceIsAValidWavThatReadsAsSilence() {
        val file = newFile()
        val take = PcmTake.start(file)
        assertTrue("the microphone is open", take.isRecording)
        Thread.sleep(TAKE_MS)
        val recorded = take.stop()

        assertEquals(file, recorded.file)
        assertTrue("the take knows roughly how long it ran: ${recorded.durationMs}", recorded.durationMs >= TAKE_MS)
        assertTrue("nobody spoke into it: peak ${recorded.peakAmplitude}", recorded.peakAmplitude < QUIET_PEAK)
        assertTrue("so the silence check catches it", recorded.isSilent)

        val bytes = file.readBytes()
        assertTrue("the header is there: ${bytes.size} bytes", bytes.size > Wav.HEADER_BYTES)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals("sixteen kilohertz, as the engine was told", Wav.SAMPLE_RATE, int32(bytes, 24))
        assertEquals("one channel", Wav.CHANNELS, int16(bytes, 22))
        assertEquals("sixteen bits a sample", Wav.BITS_PER_SAMPLE, int16(bytes, 34))

        // The length was not known when the header was first written: the real one is patched over it
        // at the end, and a file whose `data` size is short by a chunk plays as a word cut off.
        val audio = bytes.size - Wav.HEADER_BYTES
        assertEquals("the data length is the audio that is really there", audio, int32(bytes, 40))
        assertEquals("and the RIFF length agrees with it", Wav.HEADER_BYTES - 8 + audio, int32(bytes, 4))

        // A second at 16 kHz mono PCM16 is 32 000 bytes. Generous bounds: this is a shared emulator
        // and the point is the order of magnitude, not the scheduler.
        assertTrue("about a second of audio, not a handful of bytes: $audio", audio > Wav.SAMPLE_RATE)
        assertEquals("the peak the file really holds", recorded.peakAmplitude, Wav.peakOf(bytes.copyOfRange(Wav.HEADER_BYTES, bytes.size)))
    }

    /**
     * The pipe and the file are independent, which is the property the whole design rests on.
     *
     * On this emulator nothing will ever read the pipe — there is no engine — so it fills up and the
     * writes into it start failing or blocking. His take must complete anyway: the file is written
     * first and under its own lock, and a recogniser walking away from the pipe is an ordinary
     * outcome rather than an error.
     */
    @Test fun aPipeNobodyReadsCannotStopTheTake() {
        val file = newFile()
        val take = PcmTake.start(file)
        val pipe = take.newPipe()
        assertNotNull("a pipe was made for the session", pipe)
        // The read end is what a recognition service would get. Closing it here is exactly what an
        // engine that has heard enough does, and the writer must take it as an end rather than a fault.
        pipe?.close()
        Thread.sleep(TAKE_MS)
        val recorded = take.stop()

        assertTrue("the file completed regardless", recorded.file.length() > Wav.HEADER_BYTES)
        assertEquals(file.length().toInt() - Wav.HEADER_BYTES, int32(file.readBytes(), 40))
    }

    /**
     * One take, many sessions. Google's service gives up a second or two into a silence and the wait
     * opens another session for him; each of those needs its own pipe, and none of them may interrupt
     * the one microphone or the one file. His «Μίλα» is one breath whatever the service does with it.
     */
    @Test fun aNewPipePerSessionLeavesOneTake() {
        val file = newFile()
        val take = PcmTake.start(file)
        repeat(3) {
            val pipe = take.newPipe()
            assertNotNull("session ${it + 1} got a pipe", pipe)
            pipe?.close()
            Thread.sleep(TAKE_MS / 3)
        }
        val recorded = take.stop()

        assertTrue("three sessions, one file", recorded.file.length() > Wav.HEADER_BYTES)
        assertTrue("and it is as long as the whole wait was: ${recorded.durationMs}", recorded.durationMs >= TAKE_MS)
        // And no pipe survives the take: a closed recorder has nothing to hand a new session.
        assertEquals(null, take.newPipe())
    }

    /** A take for a word he has left behind: closed and gone, not left open over the next screen. */
    @Test fun cancelClosesTheMicrophoneAndDeletesTheFile() {
        val file = newFile()
        val take = PcmTake.start(file)
        Thread.sleep(200)
        take.cancel()

        assertTrue("the microphone is closed", !take.isRecording)
        assertTrue("and nothing of it is left on disk", !file.exists())
    }

    /** The WAV the take wrote is a file `MediaPlayer` can play — which is what «Άκου» does with it. */
    @Test fun theTakeIsPlayable() {
        val file = newFile()
        val take = PcmTake.start(file)
        Thread.sleep(TAKE_MS)
        take.stop()

        val played = runBlocking { graph.voice.play(file) }
        assertTrue("his own take has to play back: ${played.exceptionOrNull()}", played.isSuccess)
    }

    private fun int32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun int16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        /** Long enough for a second of audio and a dozen peak readings. */
        const val TAKE_MS = 1_200L

        /**
         * The silent emulator's own noise floor, with room to spare. Raw PCM from a dead microphone
         * reads close to zero — the old AAC recorder measured about 8 — and the line the app actually
         * uses sits at [Recorded.SILENCE_PEAK], two orders of magnitude above this.
         */
        const val QUIET_PEAK = 100
    }
}
