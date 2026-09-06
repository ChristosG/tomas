package gr.dimitris.app.core.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidSpeechToText(private val context: Context) : SpeechToText {
    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    /** The window that is open right now, so «Στοπ» has something to close. Main thread only. */
    private var current: SpeechRecognizer? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * No stopwatch on him. Aphasia means long pauses before a word arrives, so the window stays open
     * for at least [RecognizerIntents.MINIMUM_LENGTH_MS] whatever the silence, and ends when he has
     * been quiet for [RecognizerIntents.COMPLETE_SILENCE_MS] — not when a timer runs out. The only
     * hard bound is [RecognizerIntents.BOUND_MS], and that is on the recognition service: if it
     * never calls back, the screen must not sit on «Σε ακούω…» for ever.
     */
    override suspend fun listen(): Result<Transcript> = withContext(Dispatchers.Main) {
        if (!isAvailable) return@withContext Result.failure(IllegalStateException("Δεν υπάρχει αναγνώριση ομιλίας"))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        current = recognizer
        try {
            withTimeoutOrNull(RecognizerIntents.BOUND_MS) { listenOnce(recognizer) }
                ?: Result.failure(IllegalStateException(HEARD_NOTHING))
        } finally {
            current = null
            _level.value = 0f
            recognizer.destroy()
        }
    }

    /**
     * «Στοπ». `stopListening` ends the window early and the service still delivers what it had
     * through `onResults`, so the caller's [listen] returns normally — a stop is not a cancel and
     * must never throw away a word he had already got out.
     *
     * Posted to the main looper because the recognizer belongs to the thread that made it, and the
     * button that calls this is on that thread anyway.
     */
    override fun stop() {
        val recognizer = current ?: return
        main.post { runCatching { recognizer.stopListening() } }
    }

    private suspend fun listenOnce(recognizer: SpeechRecognizer): Result<Transcript> =
        suspendCancellableCoroutine { cont ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val best = texts.firstOrNull()
                    if (cont.isActive) cont.resume(
                        if (best == null) Result.failure(IllegalStateException(HEARD_NOTHING))
                        else Result.success(Transcript(best, scores?.firstOrNull() ?: 0f))
                    )
                }
                override fun onError(error: Int) { if (cont.isActive) cont.resume(Result.failure(IllegalStateException("Σφάλμα αναγνώρισης $error"))) }
                override fun onReadyForSpeech(params: Bundle?) { _level.value = 0f }
                override fun onBeginningOfSpeech() {}
                /** The one thing the screen can show him: that the phone can hear a voice at all. */
                override fun onRmsChanged(rmsdB: Float) { _level.value = levelOf(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { _level.value = 0f }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            RecognizerIntents.build().forEach { (key, value) ->
                when (value) {
                    is Long -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    else -> intent.putExtra(key, value.toString())
                }
            }
            recognizer.startListening(intent)
            cont.invokeOnCancellation { recognizer.cancel() }
        }

    companion object {
        const val HEARD_NOTHING = "Δεν άκουσα τίποτα"

        /**
         * `onRmsChanged` is documented as roughly -2 dB (quiet) to 10 dB (loud), and is not
         * calibrated on any device. It is drawn as a bar and nothing else depends on it, so a rough
         * mapping onto 0..1 is exactly as much precision as it is worth.
         */
        const val RMS_FLOOR = -2f
        const val RMS_CEILING = 10f

        fun levelOf(rmsdB: Float): Float = ((rmsdB - RMS_FLOOR) / (RMS_CEILING - RMS_FLOOR)).coerceIn(0f, 1f)
    }
}
