package gr.dimitris.app.core.speech

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Recording
import java.io.File

enum class VoiceUsed { RECORDING, TTS }

/** Says an item the best way available: the caregiver's recording if it exists and plays, else Greek TTS. */
class ItemSpeaker(
    private val tts: TextToSpeech,
    private val recordingFor: suspend (Item) -> Recording?,
    private val play: suspend (File) -> Result<Unit>,
    private val rate: suspend () -> Float,
    /** Paths are stored relative to filesDir since the phase-0 hardening; absolute paths pass through. */
    private val resolve: (String) -> File = { File(it) },
) {
    suspend fun speak(item: Item): VoiceUsed {
        val recording = recordingFor(item)
        if (recording != null) {
            val file = resolve(recording.path)
            if (file.exists() && play(file).isSuccess) return VoiceUsed.RECORDING
        }
        tts.speak(item.text, rate())
        return VoiceUsed.TTS
    }

    suspend fun speakText(text: String) {
        if (text.isNotBlank()) tts.speak(text, rate())
    }
}
