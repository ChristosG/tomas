package gr.dimitris.app.core.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A recogniser that hears exactly what a test tells it to.
 *
 * The emulator has no recognition service at all — `isRecognitionAvailable` is false there — so this
 * is the only way to drive the gentle check of spec §12 anywhere but on Chris' own phone. It is put
 * in front of the modules through [gr.dimitris.app.AppGraph.stt] and taken out again afterwards.
 *
 * [holdsOpen] makes a window wait, so a test can look at «Σε ακούω…» while it is on the screen;
 * [stop] closes it and the answer arrives, which is what «Στοπ» does on a real phone.
 */
class FakeSpeechToText : SpeechToText {
    override val isAvailable: Boolean = true

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private val answers = ArrayDeque<Result<Transcript>>()
    private var open: CompletableDeferred<Unit>? = null

    /** How many windows have been opened, and how many times «Στοπ» closed one. */
    var windows = 0
        private set
    var stops = 0
        private set

    /** When true the next window waits for [stop] instead of answering at once. */
    var holdsOpen = false

    fun willHear(text: String) = apply { answers += Result.success(Transcript(text, 1f)) }

    /** A window that came back with nothing: silence, or a sound that matched no word. His. */
    fun willHearNothing() = apply { answers += Result.failure(SpeechFailure.HeardNothing()) }

    /** A window the phone could not open at all: no network, a wedged service. Never his. */
    fun willFail(code: Int) = apply { answers += Result.failure(SpeechFailure.NotWorking(code)) }

    /** Moves the bar, the way a voice would. */
    fun loudness(value: Float) { _level.value = value }

    override suspend fun listen(): Result<Transcript> {
        windows++
        if (holdsOpen) {
            val gate = CompletableDeferred<Unit>()
            open = gate
            gate.await()
            open = null
        }
        return answers.removeFirstOrNull() ?: Result.failure(SpeechFailure.HeardNothing())
    }

    override fun stop() {
        stops++
        open?.complete(Unit)
    }
}
