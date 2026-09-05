package gr.dimitris.app.core.audio

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import gr.dimitris.app.modules.singsay.Pitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real `AudioTrack` on a real (if `-no-audio`) emulator. These tests do not assume the device
 * actually produces sound — on `-no-audio` the track still reports playing — so nothing here
 * depends on which outcome [ToneSynth.play] returns, only that it *is* a [Result] (never a thrown
 * exception, matching [Voice] and [Player]) and that whichever it is carries real information.
 */
class ToneSynthTest {
    private val synth = ToneSynth()

    @Test fun stopIsSafeBeforeAnyPlayAndRepeatedly() {
        synth.stop()
        synth.stop()
    }

    @Test fun playOfTwoNotesReturnsAResultInsteadOfThrowing() = runBlocking {
        val started = mutableListOf<Int>()
        val result = withTimeout(20_000) { synth.play(listOf(Pitch.LOW, Pitch.HIGH), onNote = { started += it }) }

        result.fold(
            onSuccess = { assertEquals("both notes should have started", listOf(0, 1), started) },
            onFailure = { assertEquals(ToneSynth.PLAYBACK_FAILED, it.message) },
        )

        // Safe after a real play, and safe to call twice.
        synth.stop()
        synth.stop()
    }

    /**
     * The one thing "a Result either way" cannot catch: a melody that *always* fails. A device with
     * an audio output has no excuse, and the first version of [ToneSynth] had one — it judged a
     * MODE_STATIC track uninitialised before writing its buffer, which is exactly what such a track
     * reports until it is written, so every note came back [ToneSynth.PLAYBACK_FAILED] on every
     * device. A machine with no output at all (a headless image) is skipped, not failed.
     */
    @Test fun playSucceedsWhereverTheDeviceHasAnAudioOutput() = runBlocking {
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        assumeTrue("no audio output on this device", audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isNotEmpty())

        val result = withTimeout(20_000) { synth.play(listOf(Pitch.LOW), noteMs = 120, gapMs = 0) }
        assertTrue("play should have succeeded: ${result.exceptionOrNull()?.message}", result.isSuccess)
        synth.stop()
    }

    /**
     * Two melodies at once — a second tap inside the first note. Before the gate, both calls raced
     * for one `AudioTrack` field and each released what the other was holding. Neither may throw,
     * both must come back as a [Result], and the newest one is the one that gets to sound.
     */
    @Test fun twoConcurrentPlaysBothReturnAResultAndNeitherThrows() = runBlocking {
        val a = async { synth.play(listOf(Pitch.LOW, Pitch.HIGH), noteMs = 200, gapMs = 0) }
        val b = async { synth.play(listOf(Pitch.HIGH, Pitch.LOW), noteMs = 200, gapMs = 0) }

        // await() rethrows: reaching the assertions at all is the "never throws" half of the test.
        val results = withTimeout(20_000) { listOf(a.await(), b.await()) }
        results.forEach { r -> r.onFailure { assertEquals(ToneSynth.PLAYBACK_FAILED, it.message) } }
        synth.stop()
    }

    /** [ToneSynth.stop] has to end the melody now, not at the end of the phrase he asked to leave. */
    @Test fun stopMidPlayEndsTheMelodyPromptly() = runBlocking {
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        assumeTrue("no audio output on this device", audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isNotEmpty())

        val firstNote = CompletableDeferred<Unit>()
        val playing = async { synth.play(List(20) { Pitch.LOW }, noteMs = 250, gapMs = 0, onNote = { firstNote.complete(Unit) }) }
        withTimeout(10_000) { firstNote.await() }

        val took = measureTimeMillis {
            synth.stop()
            withTimeout(10_000) { playing.await() }
        }
        // Twenty notes would be five seconds; it may only wait out the note it is inside.
        assertTrue("stop should end the melody promptly, took $took ms", took < 2_000)
    }
}
