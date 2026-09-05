package gr.dimitris.app.core.audio

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.core.speech.AndroidTextToSpeech
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val voice = Voice(context, tts, Player(), Recorder(context, files))

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
