package gr.dimitris.app.core.speech

/**
 * Speaks Greek. Android's engine is the day-one implementation; cloud neural voices plug in later
 * behind this same interface, chosen in caregiver settings.
 */
interface TextToSpeech {
    /** Speaks [text] and returns once the utterance has finished (or failed). Interrupts anything still playing. */
    suspend fun speak(text: String, rate: Float = 1f): Result<Unit>
    suspend fun isGreekAvailable(): Boolean
    fun stop()
}

class TtsException(message: String) : Exception(message)
