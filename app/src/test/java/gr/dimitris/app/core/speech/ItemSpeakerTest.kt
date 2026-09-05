package gr.dimitris.app.core.speech

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.Who
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

class ItemSpeakerTest {
    /** Stands in for Voice::speak: records what was asked for, answers with [ttsResult]. */
    private val spoken = mutableListOf<Pair<String, Float>>()
    private var ttsResult: Result<Unit> = Result.success(Unit)
    private val played = mutableListOf<File>()
    private val item = Item(text = "καφές")

    private fun speaker(recording: Recording?, playResult: Result<Unit> = Result.success(Unit)) =
        ItemSpeaker(
            speak = { text, rate -> spoken += text to rate; ttsResult },
            recordingFor = { recording },
            play = { played += it; playResult },
            rate = { 0.8f },
        )

    @Test fun `plays the caregiver recording when the file exists`() = runTest {
        val file = createTempFile("rec", ".m4a").toFile()
        val voice = speaker(Recording(itemId = item.id, path = file.absolutePath, who = Who.CAREGIVER, durationMs = 500)).speak(item)
        assertEquals(Result.success(VoiceUsed.RECORDING), voice)
        assertEquals(listOf(file), played)
        assertEquals(emptyList<Pair<String, Float>>(), spoken)
    }

    @Test fun `falls back to TTS when there is no recording`() = runTest {
        assertEquals(Result.success(VoiceUsed.TTS), speaker(null).speak(item))
        assertEquals(listOf("καφές" to 0.8f), spoken)
    }

    @Test fun `falls back to TTS when the recording file is missing`() = runTest {
        val voice = speaker(Recording(itemId = item.id, path = "/nowhere/x.m4a", who = Who.CAREGIVER, durationMs = 500)).speak(item)
        assertEquals(Result.success(VoiceUsed.TTS), voice)
        assertEquals(listOf("καφές" to 0.8f), spoken)
    }

    @Test fun `falls back to TTS when playback fails`() = runTest {
        val file = createTempFile("rec", ".m4a").toFile()
        val voice = speaker(Recording(itemId = item.id, path = file.absolutePath, who = Who.CAREGIVER, durationMs = 500), Result.failure(RuntimeException("x"))).speak(item)
        assertEquals(Result.success(VoiceUsed.TTS), voice)
    }

    @Test fun `a TTS failure surfaces as a failed result`() = runTest {
        ttsResult = Result.failure(TtsException("Δεν μίλησε"))
        val voice = speaker(null).speak(item)
        assertTrue("nothing was heard, so this must fail", voice.isFailure)
        assertEquals("Δεν μίλησε", voice.exceptionOrNull()?.message)
    }

    @Test fun `blank text is not spoken and is not a failure`() = runTest {
        assertEquals(Result.success(Unit), speaker(null).speakText("   "))
        assertEquals(emptyList<Pair<String, Float>>(), spoken)
    }
}
