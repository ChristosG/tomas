package gr.dimitris.app.core.speech

data class Transcript(val text: String, val confidence: Float)

/** Hears Greek. Android's recognizer first; a Whisper-class cloud provider can replace it later. */
interface SpeechToText {
    val isAvailable: Boolean
    suspend fun listen(maxSeconds: Int = 5): Result<Transcript>
}
