package gr.dimitris.app.core.speech

import gr.dimitris.app.core.audio.Recorded
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
 *
 * [engine] is which recogniser the modules are told they have. [OnDeviceSupport.Engine.ON_DEVICE] is
 * the one speech control, and then [willHear] can hand back a take alongside the words — the emulator
 * has no engine to read a pipe, so a file the test wrote itself is the only way to exercise the rule
 * that one window produces both the transcript and the recording.
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

    /** Which engine the modules believe they are talking to. The network one unless a test says so. */
    var engine: OnDeviceSupport.Engine = OnDeviceSupport.Engine.NETWORK

    /** Whether the engine claims to be fetching Greek right now. */
    var pending = false

    /** How many times the settings row asked for a download, and what it was told. */
    var downloads = 0
        private set
    var downloadAnswer = false

    fun willHear(text: String, take: Recorded? = null) =
        apply { answers += Result.success(Transcript(text, 1f, take)) }

    /** A window that came back with nothing: silence, or a sound that matched no word. His. */
    fun willHearNothing(take: Recorded? = null) =
        apply { answers += Result.failure(SpeechFailure.HeardNothing(take)) }

    /** A window the phone could not open at all: no network, a wedged service. Never his. */
    fun willFail(code: Int, take: Recorded? = null) =
        apply { answers += Result.failure(SpeechFailure.NotWorking(code, take)) }

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

    override suspend fun engine(fresh: Boolean): OnDeviceSupport.Engine = engine

    override suspend fun greekPending(): Boolean = pending

    override suspend fun downloadGreek(): Boolean {
        downloads++
        return downloadAnswer
    }
}
