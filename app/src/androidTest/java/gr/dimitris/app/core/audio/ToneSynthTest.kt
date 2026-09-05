package gr.dimitris.app.core.audio

import gr.dimitris.app.modules.singsay.Pitch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
}
