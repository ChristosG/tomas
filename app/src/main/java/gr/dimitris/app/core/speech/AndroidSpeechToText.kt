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
import kotlin.coroutines.resume

class AndroidSpeechToText(private val context: Context) : SpeechToText {
    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun listen(maxSeconds: Int): Result<Transcript> = withContext(Dispatchers.Main) {
        if (!isAvailable) return@withContext Result.failure(IllegalStateException("Δεν υπάρχει αναγνώριση ομιλίας"))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val best = texts.firstOrNull()
                        if (cont.isActive) cont.resume(
                            if (best == null) Result.failure(IllegalStateException("Δεν άκουσα τίποτα"))
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
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, maxSeconds * 1000L)
                recognizer.startListening(intent)
                cont.invokeOnCancellation { recognizer.cancel() }
            }
        } finally {
            recognizer.destroy()
        }
    }
}
