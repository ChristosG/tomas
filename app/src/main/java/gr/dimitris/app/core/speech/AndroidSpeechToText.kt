package gr.dimitris.app.core.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidSpeechToText(private val context: Context) : SpeechToText {
    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * [maxSeconds] is not a stopwatch on him. Aphasia means long pauses before a word arrives, so
     * the take ends when he has been quiet for [SILENCE_MS], not when a timer runs out. The only
     * hard bound is [TIMEOUT_MS], and that is on the recognition service: if it never calls back,
     * the screen must not sit on "Ακούω..." forever.
     */
    override suspend fun listen(maxSeconds: Int): Result<Transcript> = withContext(Dispatchers.Main) {
        if (!isAvailable) return@withContext Result.failure(IllegalStateException("Δεν υπάρχει αναγνώριση ομιλίας"))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        try {
            withTimeoutOrNull(TIMEOUT_MS) { listenOnce(recognizer) }
                ?: Result.failure(IllegalStateException(HEARD_NOTHING))
        } finally {
            recognizer.destroy()
        }
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
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "el-GR")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            recognizer.startListening(intent)
            cont.invokeOnCancellation { recognizer.cancel() }
        }

    companion object {
        /** How long he may be quiet before the recognizer decides he has finished. */
        const val SILENCE_MS = 1500L

        /** Background safety bound on the recognition service, not a limit shown to him. */
        const val TIMEOUT_MS = 15_000L

        const val HEARD_NOTHING = "Δεν άκουσα τίποτα"
    }
}
