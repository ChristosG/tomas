package gr.dimitris.app.core.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /** The session that is open right now, so «Στοπ» has something to close. Main thread only. */
    private var current: SpeechRecognizer? = null

    /** One wait at a time. Main thread only, and every caller of [listen] is on it. */
    private var waiting = false

    /** Set by [stop] and cleared at the head of [listen]: his «Στοπ» outlives the session it closed. */
    @Volatile private var stopped = false

    private val main = Handler(Looper.getMainLooper())

    /**
     * No stopwatch on him.
     *
     * One wait is made of as many recognition sessions as it takes. Google's service is documented
     * as free to ignore the timing extras, and Chris found in the field that it does — it gives up
     * a second or two into a silence and answers `ERROR_SPEECH_TIMEOUT` while he is still gathering
     * the word. So a session that heard nothing is started again, silently, for as long as
     * [Recognition.RESTART_WITHIN_MS] allows: the indicator never blinks and the bar keeps moving,
     * so from where he is sitting the phone simply went on listening.
     *
     * Three things end the wait: words, his «Στοπ», or the phone failing outright — and that last
     * one is told apart from silence by [Recognition.heardNothing], because a wedged service must
     * never be able to spend his two tries for him.
     */
    override suspend fun listen(): Result<Transcript> {
        // The package-manager query is a binder call: off the thread that draws the word, exactly as
        // the three view models already do it before they ever show «Μίλα».
        if (!withContext(Dispatchers.Default) { isAvailable }) {
            return Result.failure(SpeechFailure.NotWorking(NO_RECOGNIZER))
        }
        return withContext(Dispatchers.Main) {
            if (waiting) return@withContext Result.failure(SpeechFailure.NotWorking(ALREADY_LISTENING))
            waiting = true
            stopped = false
            val startedAt = SystemClock.elapsedRealtime()
            try {
                var outcome: Result<Transcript>
                var restarts = 0
                while (true) {
                    val sessionStartedAt = SystemClock.elapsedRealtime()
                    outcome = oneSession()
                    val now = SystemClock.elapsedRealtime()
                    if (outcome.exceptionOrNull() !is SpeechFailure.HeardNothing) break
                    val goOn = Recognition.restartsAfterSilence(
                        elapsedMs = now - startedAt,
                        stopped = stopped,
                        restarts = restarts,
                        sessionMs = now - sessionStartedAt,
                    )
                    if (!goOn) break
                    restarts++
                }
                outcome
            } finally {
                waiting = false
                _level.value = 0f
            }
        }
    }

    /** One recognition session, created and destroyed here so no path can leak one. */
    private suspend fun oneSession(): Result<Transcript> {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        current = recognizer
        return try {
            withTimeoutOrNull(RecognizerIntents.BOUND_MS) { listenOnce(recognizer) }
                ?: Result.failure(SpeechFailure.HeardNothing())
        } finally {
            current = null
            recognizer.destroy()
        }
    }

    /**
     * «Στοπ». `stopListening` ends the session early and the service still delivers what it had
     * through `onResults`, so the caller's [listen] returns normally — a stop is not a cancel and
     * must never throw away a word he had already got out.
     *
     * The flag is what makes it final: it is read by [Recognition.restartsAfterSilence], so nothing
     * is ever started again after he has said he is finished, and it is set even when no session
     * happens to be open at this instant (one is a millisecond away during a restart).
     *
     * Posted to the main looper because the recognizer belongs to the thread that made it. The
     * identity check inside the post is for the session having ended in the meantime.
     */
    override fun stop() {
        stopped = true
        val recognizer = current ?: return
        main.post { if (current === recognizer) runCatching { recognizer.stopListening() } }
    }

    private suspend fun listenOnce(recognizer: SpeechRecognizer): Result<Transcript> =
        suspendCancellableCoroutine { cont ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val best = texts.firstOrNull()?.takeIf { it.isNotBlank() }
                    if (cont.isActive) cont.resume(
                        if (best == null) Result.failure(SpeechFailure.HeardNothing())
                        else Result.success(Transcript(best, scores?.firstOrNull() ?: 0f))
                    )
                }
                /**
                 * Silence and a sound that matched nothing are not failures of his.
                 *
                 * Nor is anything at all once he has pressed «Στοπ»: some implementations answer
                 * `stopListening()` with `ERROR_CLIENT` rather than `ERROR_NO_MATCH`, and telling
                 * him the recogniser is broken because he closed the window himself would be the
                 * app blaming him for its own convention. After a stop, whatever comes back is
                 * simply what was heard — nothing.
                 */
                override fun onError(error: Int) {
                    if (!cont.isActive) return
                    cont.resume(
                        when (Recognition.outcomeOfError(error, stopped)) {
                            Recognition.ErrorOutcome.STOPPED -> Result.success(Transcript("", 0f))
                            Recognition.ErrorOutcome.HEARD_NOTHING -> Result.failure(SpeechFailure.HeardNothing())
                            Recognition.ErrorOutcome.NOT_WORKING -> Result.failure(SpeechFailure.NotWorking(error))
                        }
                    )
                }
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
            // Whichever thread cancels: `cancel()` throws off the main one, and a cancelled wait is
            // already on its way out — it must not take the coroutine down with it.
            cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
        }

    companion object {
        /** Not one of Android's codes: this device has no recognition service at all. */
        const val NO_RECOGNIZER = -1

        /** Nor this one: a second wait was asked for while the first was still open. */
        const val ALREADY_LISTENING = -2

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
