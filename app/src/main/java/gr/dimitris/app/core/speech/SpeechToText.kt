package gr.dimitris.app.core.speech

import kotlinx.coroutines.flow.StateFlow

data class Transcript(val text: String, val confidence: Float)

/** Hears Greek. Android's recognizer first; a Whisper-class cloud provider can replace it later. */
interface SpeechToText {
    val isAvailable: Boolean

    /**
     * How loud he is right now, 0..1, while a window is open. The listening screen draws it, so that
     * a man who cannot ask "is it hearing me?" can see the answer instead.
     *
     * Zero when nothing is listening. It is a picture of the microphone, never a verdict on him.
     */
    val level: StateFlow<Float>

    /**
     * Listens until he has been quiet for a moment. The implementation bounds itself; there is no
     * timer on him — Chris found in the field that even *he* could not get a word out before the
     * old window closed, and Dimitris needs longer than Chris does.
     */
    suspend fun listen(): Result<Transcript>

    /**
     * «Στοπ»: close the window now. Whatever was heard still comes back through the [listen] that
     * opened it — an empty [Transcript] if there was nothing — so the caller has exactly one place
     * where a result arrives.
     */
    fun stop()
}
