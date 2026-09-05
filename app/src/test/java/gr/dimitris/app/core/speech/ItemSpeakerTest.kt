package gr.dimitris.app.core.speech

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.Who
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

class ItemSpeakerTest {
    private val tts = FakeTextToSpeech()
    private val played = mutableListOf<File>()
    private val item = Item(text = "καφές")

    private fun speaker(recording: Recording?, playResult: Result<Unit> = Result.success(Unit)) =
        ItemSpeaker(tts, recordingFor = { recording }, play = { played += it; playResult }, rate = { 0.8f })

    @Test fun `plays the caregiver recording when the file exists`() = runTest {
        val file = createTempFile("rec", ".m4a").toFile()
        val voice = speaker(Recording(itemId = item.id, path = file.absolutePath, who = Who.CAREGIVER, durationMs = 500)).speak(item)
        assertEquals(VoiceUsed.RECORDING, voice)
        assertEquals(listOf(file), played)
        assertEquals(emptyList<String>(), tts.spoken)
    }

    @Test fun `falls back to TTS when there is no recording`() = runTest {
        assertEquals(VoiceUsed.TTS, speaker(null).speak(item))
        assertEquals(listOf("καφές"), tts.spoken)
    }

    @Test fun `falls back to TTS when the recording file is missing`() = runTest {
        val voice = speaker(Recording(itemId = item.id, path = "/nowhere/x.m4a", who = Who.CAREGIVER, durationMs = 500)).speak(item)
        assertEquals(VoiceUsed.TTS, voice)
        assertEquals(listOf("καφές"), tts.spoken)
    }

    @Test fun `falls back to TTS when playback fails`() = runTest {
        val file = createTempFile("rec", ".m4a").toFile()
        val voice = speaker(Recording(itemId = item.id, path = file.absolutePath, who = Who.CAREGIVER, durationMs = 500), Result.failure(RuntimeException("x"))).speak(item)
        assertEquals(VoiceUsed.TTS, voice)
    }
}
