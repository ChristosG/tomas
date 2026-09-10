package gr.dimitris.app.core.speech

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.audio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // The measured floor, named in the message so the number in `Recorded.SILENCE_PEAK_PCM` can
        // be argued with rather than believed. On this emulator it is 8 — the same 8 `RecorderTest`
        // reads through the AAC encoder, so the floor gives no reason to move the line; what does is
        // the gain difference between `MIC` and `VOICE_RECOGNITION`, which only his phone can show.
        assertTrue("nobody spoke into it: peak ${recorded.peakAmplitude}", recorded.peakAmplitude < QUIET_PEAK)
        assertEquals("and it is judged against the PCM line, not the encoder's", Recorded.SILENCE_PEAK_PCM, recorded.silenceFloor)
        assertTrue("so the silence check catches it", recorded.isSilent)
        // The line has to sit far above the floor and far below a voice, or it is not a line at all.
        assertTrue(
            "the silence line is not clear of the noise floor: ${recorded.peakAmplitude} vs ${Recorded.SILENCE_PEAK_PCM}",
            Recorded.SILENCE_PEAK_PCM > recorded.peakAmplitude * 10 + 100,
        )
        assertTrue("and it is below the quietest speech", Recorded.SILENCE_PEAK_PCM < QUIET_SPEECH_PEAK)

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
     * An engine that closed its end. `EPIPE` is the ordinary way a session ends — it took what it
     * wanted and let go — and the take must read it as an ending rather than as a fault.
     */
    @Test fun anEngineThatLetsGoOfThePipeIsAnOrdinaryEnding() {
        val file = newFile()
        val take = PcmTake.start(file)
        val pipe = take.newPipe()
        assertNotNull("a pipe was made for the session", pipe)
        // The read end is what a recognition service would get. Closing it here is exactly what an
        // engine that has heard enough does.
        pipe?.close()
        Thread.sleep(TAKE_MS)
        val recorded = take.stop()

        assertTrue("the file completed regardless", recorded.file.length() > Wav.HEADER_BYTES)
        assertEquals(file.length().toInt() - Wav.HEADER_BYTES, int32(file.readBytes(), 40))
    }

    /**
     * **The property the whole design rests on**: a recogniser that holds the read end and never
     * drains it cannot touch his take.
     *
     * This is the failure the first draft of `PcmTake` really had. The pipe write sat on the
     * microphone thread, so once the kernel's 64 KiB pipe buffer filled — two seconds of 16 kHz mono
     * PCM16, at 32 000 B/s — that thread blocked inside `write`, stopped calling `AudioRecord.read`,
     * the recorder's ring overran, the bar froze and the WAV stopped mid-word. On Chris' phone it
     * would have looked exactly like the engine ignoring `EXTRA_AUDIO_SOURCE`, which is the opposite
     * of what it is.
     *
     * So: hold the read end open, read nothing from it, and record for well past the point where
     * both the pipe buffer and the bounded queue behind it are full. The WAV has to hold the whole
     * duration, its header has to be right, and `stop()` has to come back at once — «Στοπ» is under
     * his thumb, and a button that waits on a wedged recogniser is a button that does nothing.
     */
    @Test fun aRecogniserThatNeverReadsCannotStallHisTake() {
        val file = newFile()
        val take = PcmTake.start(file)
        // Held, never read: the far end of a recogniser that has stopped taking audio.
        val pipe = take.newPipe()
        assertNotNull("a pipe was made for the session", pipe)
        try {
            // Long past the ~2 s of pipe buffer plus the ~3 s of queue behind it.
            Thread.sleep(STALLED_MS)
            val before = SystemClock.elapsedRealtime()
            val recorded = take.stop()
            val closing = SystemClock.elapsedRealtime() - before

            assertTrue("«Στοπ» waited on the recogniser: ${closing}ms", closing < STOP_BOUND_MS)
            val bytes = file.readBytes()
            val audio = bytes.size - Wav.HEADER_BYTES
            // Six seconds at 16 kHz mono PCM16 is 192 000 bytes. The bound below is deliberately
            // loose — this is a shared emulator — but it is far above the ~96 000 a take truncated
            // at the pipe buffer would hold.
            assertTrue("the take was truncated at the pipe: $audio bytes", audio > Wav.SAMPLE_RATE * 2 * STALLED_SECONDS_KEPT)
            assertEquals("and its header still says how much is there", audio, int32(bytes, 40))
            assertEquals(Wav.HEADER_BYTES - 8 + audio, int32(bytes, 4))
            assertTrue("the take knows how long it ran: ${recorded.durationMs}", recorded.durationMs >= STALLED_MS)
        } finally {
            runCatching { pipe?.close() }
        }
    }

    /**
     * «Στοπ» tells the engine there is no more audio coming, without touching the microphone or the
     * file. An engine fed from a descriptor may be waiting for exactly that before it says what it
     * heard, and the session's own bound is twenty seconds.
     */
    @Test fun closingThePipeLeavesTheMicrophoneAndTheFileAlone() {
        val file = newFile()
        val take = PcmTake.start(file)
        take.newPipe()?.close()
        Thread.sleep(400)

        take.closePipe()
        assertTrue("the microphone is still his", take.isRecording)
        Thread.sleep(400)
        val recorded = take.stop()

        assertTrue("the take carried on past the pipe: ${recorded.durationMs}", recorded.durationMs >= 800)
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

    /**
     * «Στοπ» reaches a recogniser that has stopped reading, and reaches it *now*.
     *
     * An engine fed from a descriptor may be waiting for end-of-stream before it says what it heard,
     * and one that has stopped draining will never see anything the pipe thread is trying to write —
     * so the end-of-stream is a flag, and a wedged writer is freed by closing the descriptor from
     * under it. Without that, «Στοπ» would stand there while the session ran to its twenty-second
     * bound: a button that does nothing, which is the one thing this app must never be.
     *
     * Proved from the reader's side, which is where a real engine sits: after «Στοπ», draining the
     * read end reaches end-of-file. If the pipe had not been closed the drain would empty the buffer
     * and then block for ever behind a writer that had more to give.
     */
    @Test fun closingThePipeReachesARecogniserThatStoppedReading() {
        val file = newFile()
        val take = PcmTake.start(file)
        val pipe = take.newPipe()
        assertNotNull("a pipe was made for the session", pipe)
        // Long past the point where the pipe buffer and the queue behind it are both full, so the
        // pipe thread is certainly inside a write that will never return on its own.
        Thread.sleep(STALLED_MS)

        take.closePipe()

        val ended = java.util.concurrent.CountDownLatch(1)
        val drained = Thread {
            val input = java.io.FileInputStream(pipe!!.fileDescriptor)
            val buffer = ByteArray(8 * 1024)
            while (input.read(buffer) >= 0) Unit
            ended.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(
            "the recogniser never reached the end of his voice",
            ended.await(EOF_BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        drained.join(EOF_BOUND_MS)
        runCatching { pipe?.close() }
        take.stop()
    }

    /**
     * A cancelled start must still hand back the take, or the microphone is orphaned for ever.
     *
     * This is the shape of the one thing that would be invisible on his phone: the reader thread
     * loops on a flag only `stop()` clears, so a `PcmTake` nobody holds keeps an `AudioRecord` open
     * on `VOICE_RECOGNITION` and writes a WAV at 32 kB/s for the life of the process — about
     * 115 MB an hour, with the app looking idle. «Μίλα» followed inside a few tens of milliseconds
     * by «Επόμενο» or a back press is all it would take, because all four of those cancel the
     * listening job.
     *
     * `AndroidSpeechToText` starts the take under `NonCancellable` and holds the handle before its
     * first suspension point, so the cancellation lands with something that can be closed. That
     * wiring only runs on an engine this emulator does not have; what is proved here is the property
     * underneath it — a take built inside a job that is cancelled is still closable, and once closed
     * it stops recording and leaves nothing behind.
     */
    @Test fun aTakeBuiltInACancelledJobIsStillClosable() = runBlocking {
        val file = newFile()
        lateinit var take: PcmTake
        val started = java.util.concurrent.CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            take = withContext(NonCancellable) { PcmTake.start(file) }
            started.countDown()
            try {
                delay(60_000)
            } finally {
                withContext(NonCancellable) { take.cancel() }
            }
        }
        assertTrue(started.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
        // Long enough for the file to have grown, so "stopped growing" below means something.
        delay(TAKE_MS)
        assertTrue("the microphone is open", take.isRecording)
        job.cancelAndJoin()

        assertFalse("the microphone was released", take.isRecording)
        assertFalse("and the take of a word he left behind is gone", file.exists())
        // Nothing is still writing: a file that is gone cannot come back.
        delay(500)
        assertFalse(file.exists())
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
         * Long past the ~2 s of kernel pipe buffer plus the ~3 s of [PcmTake.QUEUE_BLOCKS] behind it,
         * so a take that could be stalled by a silent recogniser certainly would be.
         */
        const val STALLED_MS = 6_000L

        /** How much of those six seconds the file must hold, in whole seconds. Loose on purpose. */
        const val STALLED_SECONDS_KEPT = 4

        /** «Στοπ» is under his thumb: [PcmTake.stop] answers inside this whatever the engine is doing. */
        const val STOP_BOUND_MS = 200L

        /** And the engine has to see the end of the stream inside this, wedged or not. */
        const val EOF_BOUND_MS = 3_000L

        /** For the cancellation case: long enough for a `PcmTake.start` and a first block. */
        const val TIMEOUT_MS = 5_000L

        /**
         * The quietest a real voice at normal distance is expected to read on the 16-bit scale. The
         * silence line has to sit under it, or a word he really said is deleted.
         */
        const val QUIET_SPEECH_PEAK = 1_500

        /**
         * The silent emulator's own noise floor, with room to spare: it measures 8, the same as the
         * AAC recorder does. The line the app uses on *this* path is [Recorded.SILENCE_PEAK_PCM],
         * which sits nearly two orders of magnitude above it.
         */
        const val QUIET_PEAK = 100
    }
}
