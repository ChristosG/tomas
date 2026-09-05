package gr.dimitris.app.core.speech

data class Transcript(val text: String, val confidence: Float)

/** Hears Greek. Android's recognizer first; a Whisper-class cloud provider can replace it later. */
interface SpeechToText {
    val isAvailable: Boolean
    /** Listens until he has been quiet for a moment. The implementation bounds itself; there is no timer on him. */
    suspend fun listen(): Result<Transcript>
}
