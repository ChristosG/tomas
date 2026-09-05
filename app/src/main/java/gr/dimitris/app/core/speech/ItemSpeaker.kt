package gr.dimitris.app.core.speech

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Recording
import java.io.File

enum class VoiceUsed { RECORDING, TTS }

/**
 * Says an item the best way available: the caregiver's recording if it exists and plays, else Greek TTS.
 *
 * Both ways in are function values so the app can hand over [gr.dimitris.app.core.audio.Voice] — the
 * single owner of sound — instead of reaching past it to the engine. A failure is returned, never
 * swallowed: the talk board has to be able to tell Dimitris that nothing was heard.
 */
class ItemSpeaker(
    private val speak: suspend (String, Float) -> Result<Unit>,
    private val recordingFor: suspend (Item) -> Recording?,
    private val play: suspend (File) -> Result<Unit>,
    private val rate: suspend () -> Float,
    /** Paths are stored relative to filesDir since the phase-0 hardening; absolute paths pass through. */
    private val resolve: (String) -> File = { File(it) },
) {
    /** Fails only when neither the recording nor the fallback TTS made a sound. */
    suspend fun speak(item: Item): Result<VoiceUsed> {
        val recording = recordingFor(item)
        if (recording != null) {
            val file = resolve(recording.path)
            if (file.exists() && play(file).isSuccess) return Result.success(VoiceUsed.RECORDING)
        }
        return speak.invoke(item.text, rate()).map { VoiceUsed.TTS }
    }

    /** Blank text is nothing to say, not a failure. */
    suspend fun speakText(text: String): Result<Unit> =
        if (text.isBlank()) Result.success(Unit) else speak.invoke(text, rate())
}
