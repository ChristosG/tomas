package gr.dimitris.app.core.audio

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.core.speech.AndroidTextToSpeech
import gr.dimitris.app.modules.singsay.Pitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class VoiceTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = MediaFiles(context)
    private val tts = AndroidTextToSpeech(context)
    private val synth = ToneSynth()
    private val voice = Voice(context, tts, Player(), Recorder(context, files), synth)

    @After fun tearDown() {
        voice.cancelRecording()
        voice.quiet()
        tts.shutdown()
    }

    @Test fun quietIsSafeWhenNothingIsRunning() {
        voice.quiet()
        voice.quiet()
    }

    @Test fun speaksGreekAndReleasesFocus() = runBlocking {
        assumeTrue("Greek voice not installed on this device", withTimeout(20_000) { tts.isGreekAvailable() })
        val result = withTimeout(20_000) { voice.speak("Γεια", 0.9f) }
        assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess)
    }

    /** A recording made through the facade is a real .m4a the facade can then play back. */
    @Test fun recordsThenPlaysBack() = runBlocking<Unit> {
        val file = voice.startRecording()
        assertTrue(voice.isRecording)
        Thread.sleep(1_000)
        val recorded = voice.stopRecording()
        assertTrue(!voice.isRecording)
        assertEquals(file, recorded.file)
        assertTrue("recording is empty", recorded.file.length() > 0)
        assertTrue("no duration measured", recorded.durationMs > 0)

        val result = withTimeout(20_000) { voice.play(recorded.file) }
        assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess)
        recorded.file.delete()
    }

    @Test fun playingAMissingFileFailsInsteadOfThrowing() = runBlocking {
        val missing = File(files.recordingsDir, "δεν-υπάρχει.m4a")
        val result = withTimeout(20_000) { voice.play(missing) }
        assertTrue("a missing file should not play", result.isFailure)
    }

    /** The microphone is open: anything the app said now would be inside the take. */
    @Test fun speakingWhileRecordingIsRefusedInGreek() = runBlocking {
        voice.startRecording()
        try {
            val result = withTimeout(20_000) { voice.speak("Γεια", 0.9f) }
            assertTrue("output must not talk over the microphone", result.isFailure)
            assertEquals("Ηχογραφεί τώρα", result.exceptionOrNull()?.message)
            assertTrue("the recording must survive the refusal", voice.isRecording)
        } finally {
            voice.cancelRecording()
        }
    }

    /**
     * The whole point of the melody living behind [Voice]: every `onDispose { voice.quiet() }` in
     * the app — the practice host, the session host, the editor — is a promise that leaving a
     * screen leaves it silent, and before this the melody went on playing under the talk board.
     */
    @Test fun quietStopsARunningMelody() = runBlocking {
        val firstNote = CompletableDeferred<Unit>()
        // Twenty notes is twelve seconds of melody: nothing here can pass by waiting it out.
        val playing = async { voice.playMelody(List(20) { Pitch.LOW }, noteMs = 250, gapMs = 0, onNote = { firstNote.complete(Unit) }) }
        withTimeout(10_000) { firstNote.await() }

        val took = measureTimeMillis {
            voice.quiet()
            // await() rethrows: reaching the assertion at all is also the "never throws" half.
            withTimeout(10_000) { playing.await() }
        }
        assertTrue("quiet() left the melody playing for $took ms", took < 2_000)
    }

    /** The microphone is open: a melody now would be inside the take. */
    @Test fun theMelodyIsRefusedWhileRecording() = runBlocking {
        voice.startRecording()
        try {
            val result = withTimeout(20_000) { voice.playMelody(listOf(Pitch.LOW, Pitch.HIGH)) }
            assertTrue("the melody must not play over the microphone", result.isFailure)
            assertEquals("Ηχογραφεί τώρα", result.exceptionOrNull()?.message)
            assertTrue("the recording must survive the refusal", voice.isRecording)
        } finally {
            voice.cancelRecording()
        }
    }

    @Test fun recordingTwiceIsRefusedInGreek() {
        voice.startRecording()
        try {
            val e = assertThrows(IllegalStateException::class.java) { voice.startRecording() }
            assertEquals("Ήδη ηχογραφεί", e.message)
            assertTrue("the first recording must survive the refusal", voice.isRecording)
        } finally {
            voice.cancelRecording()
        }
    }
}
