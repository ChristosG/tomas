package gr.dimitris.app.core.speech

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import gr.dimitris.app.core.audio.Recorded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Greek recognition on this phone, on-device first.
 *
 * [newTakeFile] is asked for a WAV in the recordings folder whenever the on-device path is taken: the
 * same window then produces the transcript *and* the file he plays back. See [PcmTake] for why those
 * are the same bytes, and [OnDeviceSupport] for which engine answers and why.
 */
class AndroidSpeechToText(
    private val context: Context,
    private val newTakeFile: () -> File,
) : SpeechToText {
    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    /** The session that is open right now, so «Στοπ» has something to close. Main thread only. */
    private var current: SpeechRecognizer? = null

    /**
     * The take the open window is filling, so «Στοπ» can tell the engine that no more audio is
     * coming. It is not the microphone's off switch — [listen] closes that when the wait ends, so
     * the file holds everything up to the moment he said he had finished — it is how the *pipe* gets
     * an end-of-stream while the session is still open.
     */
    @Volatile private var take: PcmTake? = null

    /** One wait at a time. Main thread only, and every caller of [listen] is on it. */
    private var waiting = false

    /** Set by [stop] and cleared at the head of [listen]: his «Στοπ» outlives the session it closed. */
    @Volatile private var stopped = false

    private val main = Handler(Looper.getMainLooper())

    /** Callbacks come back here. A plain post to the looper rather than `Context.getMainExecutor`, which is API 28. */
    private val mainExecutor = Executor { main.post(it) }

    /**
     * What the on-device engine last said about its languages, and when.
     *
     * Cached because asking is a service bind plus a round trip, and [listen] asks before every
     * window: a pause between «Μίλα» and the microphone opening is a pause a man waiting to speak
     * would feel. The lists only change when a model is downloaded, and the one thing that downloads
     * one — [downloadGreek] — clears this itself, so the staleness is a few seconds of a fact that
     * changes once a year.
     */
    @Volatile private var languages: Languages? = null
    @Volatile private var languagesAt = 0L

    /** The two lists [OnDeviceSupport.decide] weighs, plus what is being fetched right now. */
    private class Languages(val installed: List<String>, val supported: List<String>, val pending: List<String>)

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
     *
     * One [PcmTake] spans the whole wait on the on-device path, however many sessions the service
     * throws away: his «Μίλα» is one breath and has to come back as one file. Each session gets its
     * own pipe from it, because a pipe can only be read once.
     */
    override suspend fun listen(): Result<Transcript> {
        // The package-manager query is a binder call: off the thread that draws the word, exactly as
        // the three view models already do it before they ever show «Μίλα».
        if (!withContext(Dispatchers.Default) { isAvailable }) {
            return Result.failure(SpeechFailure.NotWorking(NO_RECOGNIZER))
        }
        val engine = engine()
        // There is a recognition service, and it still cannot hear him: the only way
        // [OnDeviceSupport.decide] says this is a cloud-only engine with no connection, which is
        // Chris' code 2 — and it is said as «Χρειάζεται σύνδεση» rather than as «δεν λειτούργησε».
        if (engine == OnDeviceSupport.Engine.NONE) {
            return Result.failure(SpeechFailure.NotWorking(SpeechRecognizer.ERROR_NETWORK))
        }
        val onDevice = engine == OnDeviceSupport.Engine.ON_DEVICE
        // The microphone is the app's own on this path, and opening one is not something the screen
        // may wait on: an `AudioRecord` and a file, off the drawing thread, before the loop below
        // ever touches the main looper. A take that cannot be started is not a failure — the session
        // simply runs with the engine's own microphone, as it always did.
        var pcm = if (onDevice) withContext(Dispatchers.IO) { startTake() } else null
        return withContext(Dispatchers.Main) {
            if (waiting) {
                pcm?.let { withContext(NonCancellable + Dispatchers.IO) { it.cancel() } }
                return@withContext Result.failure(SpeechFailure.NotWorking(ALREADY_LISTENING))
            }
            waiting = true
            stopped = false
            take = pcm
            val startedAt = SystemClock.elapsedRealtime()
            try {
                var outcome: Result<Transcript>
                var restarts = 0
                while (true) {
                    val sessionStartedAt = SystemClock.elapsedRealtime()
                    // One pipe per session, because a session is the only thing that can read one.
                    // A pipe that cannot be made leaves no honest way to run this window with the
                    // take: the app is holding the microphone, so letting the engine open it as well
                    // would be two captures on one device, which usually silences one of them. The
                    // take goes and the rest of the wait is a plain recognition session.
                    val pipe = pcm?.newPipe()
                    if (pcm != null && pipe == null) {
                        val abandoned = pcm
                        pcm = null
                        take = null
                        withContext(NonCancellable + Dispatchers.IO) { abandoned.cancel() }
                    }
                    outcome = oneSession(onDevice, pipe)
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
                // Off the main thread and beyond cancellation: closing the take joins the microphone
                // thread and patches the header, and his file must be finished even if the screen is
                // going away underneath this very line.
                val closing = pcm
                val kept = closing?.let { withContext(NonCancellable + Dispatchers.IO) { finishTake(it) } }
                outcome.withTake(kept)
            } finally {
                waiting = false
                take = null
                _level.value = 0f
                // A wait that was cancelled — he pressed back, the session moved on — never reached
                // the line above, and a microphone left open would outlive the word it belonged to.
                // The take goes with it: it is a recording of a word he has left behind. Off the
                // main thread, because cancelling joins a reader and rewrites a header, and the
                // screen he is leaving must not stutter for it.
                pcm?.let { open -> if (open.isRecording) withContext(NonCancellable + Dispatchers.IO) { open.cancel() } }
            }
        }
    }

    /**
     * One recognition session, created and destroyed here so no path can leak one.
     *
     * `destroy()` in the `finally` is also what frees a pipe writer blocked against a service that
     * stopped reading: it closes the service's copy of the read end, and only then does a pending
     * write into that pipe fail. Nothing in [PcmTake] waits for that, which is why a wedged engine
     * costs the recogniser audio and costs his take nothing.
     */
    private suspend fun oneSession(onDevice: Boolean, pipe: ParcelFileDescriptor?): Result<Transcript> {
        val recognizer = newRecognizer(onDevice) ?: return Result.failure(SpeechFailure.NotWorking(NO_RECOGNIZER))
        current = recognizer
        return try {
            // A session with no pipe is one the engine opens its own microphone for, so the bar comes
            // from `onRmsChanged` again. Which *engine* answers is a separate question and is not
            // changed by a pipe that could not be made: on-device Greek is free and offline, and
            // losing the take is no reason to go back to the cloud for the words.
            withTimeoutOrNull(RecognizerIntents.BOUND_MS) { listenOnce(recognizer, pipe, ownMicrophone = pipe != null) }
                ?: Result.failure(SpeechFailure.HeardNothing())
        } finally {
            current = null
            recognizer.destroy()
        }
    }

    /**
     * The on-device recogniser where there is one, and Google's cloud one otherwise. Creating either
     * can throw on a phone whose recognition service has gone away between the check and the call,
     * and a throw here would crash him out of the module.
     */
    private fun newRecognizer(onDevice: Boolean): SpeechRecognizer? = runCatching {
        if (onDevice && Build.VERSION.SDK_INT >= OnDeviceSupport.ON_DEVICE_SDK) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }.getOrNull()

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
     *
     * The pipe is closed here as well, and that is the other half of «Στοπ» on the on-device path.
     * An engine fed from a descriptor may be waiting for end-of-stream before it decides what it
     * heard, and `stopListening()` says nothing about the descriptor — so without this the session
     * would sit there to its twenty-second bound and the words he did get out would be thrown away
     * while the indicator stayed on the screen. Everything still queued goes down the pipe first, so
     * the engine hears the end of his word rather than a cut one.
     *
     * The microphone and the file are deliberately *not* stopped here: [listen] closes those when the
     * wait ends, so his take is finished exactly once and holds everything up to the moment he said
     * he had finished.
     */
    override fun stop() {
        stopped = true
        take?.closePipe()
        val recognizer = current ?: return
        main.post { if (current === recognizer) runCatching { recognizer.stopListening() } }
    }

    private suspend fun listenOnce(
        recognizer: SpeechRecognizer,
        pipe: ParcelFileDescriptor?,
        ownMicrophone: Boolean,
    ): Result<Transcript> =
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
                override fun onReadyForSpeech(params: Bundle?) { if (!ownMicrophone) _level.value = 0f }
                override fun onBeginningOfSpeech() {}
                /**
                 * The one thing the screen can show him: that the phone can hear a voice at all.
                 * Only on the path where the engine owns the microphone — when the app owns it, the
                 * bar comes from the app's own samples and the engine never calls this at all.
                 */
                override fun onRmsChanged(rmsdB: Float) { if (!ownMicrophone) _level.value = levelOf(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (!ownMicrophone) _level.value = 0f }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = greekIntent()
            // The app's own microphone, handed over as a pipe: the engine is told the format through
            // the three extras, and reads the same samples that are going into his WAV.
            if (pipe != null) {
                RecognizerIntents.audioSource().forEach { (key, value) -> intent.putExtra(key, value as Int) }
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe)
            }
            recognizer.startListening(intent)
            // Our own copy of the read end goes now that the binder transaction has duplicated it
            // into the service: holding it open would keep the pipe alive after the engine closed its
            // end, so the pipe writer would never see the `EPIPE` that tells it this session is over.
            if (pipe != null) runCatching { pipe.close() }
            // Whichever thread cancels: `cancel()` throws off the main one, and a cancelled wait is
            // already on its way out — it must not take the coroutine down with it.
            cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
        }

    /** The Greek window's extras, as an intent. Shared by listening, the support check and the download. */
    private fun greekIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        RecognizerIntents.build().forEach { (key, value) ->
            when (value) {
                is Long -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
        return intent
    }

    // ---- the take ----------------------------------------------------------------------------

    /** Null rather than a throw: a microphone that will not open costs the take, never the window. */
    private fun startTake(): PcmTake? = runCatching {
        PcmTake.start(newTakeFile()) { level -> _level.value = level }
    }.getOrNull()

    /**
     * Closes the microphone and hands back his file — unless nobody spoke into it, in which case it
     * is deleted here and the window simply has no take.
     *
     * The silence line travels with the take ([Recorded.silenceFloor]); on this path it is
     * [Recorded.SILENCE_PEAK_PCM], which is lower than the recorder's because raw PCM off
     * `VOICE_RECOGNITION` is not the same signal the AAC encoder was calibrated against. Chris found
     * that a take checked nothing, so a silent one was kept and played back to him as his own voice;
     * here the peak is known exactly, so that cannot happen — and erring low is deliberate, because
     * deleting a quiet word he really said would be the worse of the two mistakes.
     */
    private fun finishTake(pcm: PcmTake): Recorded? {
        val recorded = runCatching { pcm.stop() }.getOrNull() ?: return null
        if (recorded.isSilent) {
            runCatching { recorded.file.delete() }
            return null
        }
        return recorded
    }

    /** The same take on every branch of the window: see [take]. */
    private fun Result<Transcript>.withTake(recorded: Recorded?): Result<Transcript> {
        if (recorded == null) return this
        val failure = exceptionOrNull()
        return when (failure) {
            null -> Result.success(getOrThrow().copy(take = recorded))
            is SpeechFailure.HeardNothing -> Result.failure(SpeechFailure.HeardNothing(recorded))
            is SpeechFailure.NotWorking -> Result.failure(SpeechFailure.NotWorking(failure.code, recorded))
            else -> this
        }
    }

    // ---- which engine, and the Greek model --------------------------------------------------

    /**
     * The five facts [OnDeviceSupport.decide] weighs, gathered off the thread that draws the word:
     * `isRecognitionAvailable` and `isOnDeviceRecognitionAvailable` are package-manager queries
     * across a binder, and the language lists are a round trip to the engine.
     */
    override suspend fun engine(fresh: Boolean): OnDeviceSupport.Engine {
        if (!withContext(Dispatchers.Default) { isAvailable }) return OnDeviceSupport.Engine.NONE
        val onDevice = Build.VERSION.SDK_INT >= OnDeviceSupport.ON_DEVICE_SDK &&
            withContext(Dispatchers.Default) { runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false) }
        val known = if (onDevice) languages(fresh) else null
        return OnDeviceSupport.decide(
            sdk = Build.VERSION.SDK_INT,
            onDeviceAvailable = onDevice,
            installedLanguages = known?.installed.orEmpty(),
            supportedLanguages = known?.supported.orEmpty(),
            online = online(),
        )
    }

    /**
     * Whether the engine is already fetching Greek on its own account.
     *
     * It matters because a caregiver who taps «Λήψη ελληνικών» and then leaves the screen would come
     * back to a row offering her the same button for a download that is already running. Read from
     * whatever the last support check said, so it costs nothing: the row calls [engine] first.
     */
    override suspend fun greekPending(): Boolean =
        OnDeviceSupport.speaksGreek(languages?.pending.orEmpty())

    private suspend fun languages(fresh: Boolean): Languages? {
        val cached = languages
        val age = SystemClock.elapsedRealtime() - languagesAt
        if (!fresh && cached != null && age < LANGUAGES_FRESH_MS) return cached
        if (Build.VERSION.SDK_INT < OnDeviceSupport.ON_DEVICE_SDK) return null
        val asked = askSupport()
        // A check that failed leaves the last answer standing rather than claiming the engine knows
        // no languages at all: a momentary `ERROR_CANNOT_CHECK_SUPPORT` must not take «Μίλα» away.
        if (asked != null) {
            languages = asked
            languagesAt = SystemClock.elapsedRealtime()
        }
        return asked ?: cached
    }

    /**
     * `checkRecognitionSupport` — what the on-device engine has downloaded, what it could download,
     * and what it is downloading now.
     *
     * The recognizer belongs to the thread that made it, so the whole of this happens on the main
     * looper; the callback is posted back to the same one. Bounded, because an engine that never
     * answers must not be able to hold up the settings screen or the word he is about to say.
     */
    @RequiresApi(OnDeviceSupport.ON_DEVICE_SDK)
    private suspend fun askSupport(): Languages? = withContext(Dispatchers.Main) {
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?: return@withContext null
        try {
            withTimeoutOrNull(SUPPORT_MS) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            if (cont.isActive) cont.resume(
                                Languages(
                                    installed = recognitionSupport.installedOnDeviceLanguages,
                                    supported = recognitionSupport.supportedOnDeviceLanguages,
                                    pending = recognitionSupport.pendingOnDeviceLanguages,
                                )
                            )
                        }
                        override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                    }
                    runCatching { recognizer.checkRecognitionSupport(greekIntent(), mainExecutor, callback) }
                        .onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    /**
     * The one tap that answers Chris' code 12.
     *
     * On Android 14 and up the engine reports back, so this suspends until it says the model has
     * landed, has been scheduled for later, or has failed — and the settings row says «λήψη…» for
     * exactly that long. On Android 13 there is no listener at all, so the ask returns at once and
     * the row polls instead.
     *
     * The cached language lists are cleared either way: whatever happened, what this phone can do has
     * possibly changed, and the next question about it must be asked of the engine and not of a
     * memory of it.
     */
    override suspend fun downloadGreek(): Boolean {
        if (Build.VERSION.SDK_INT < OnDeviceSupport.ON_DEVICE_SDK) return false
        val onDevice = withContext(Dispatchers.Default) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        }
        if (!onDevice) return false
        return withContext(Dispatchers.Main) {
            val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
                ?: return@withContext false
            try {
                if (Build.VERSION.SDK_INT >= DOWNLOAD_LISTENER_SDK) awaitDownload(recognizer)
                else runCatching { recognizer.triggerModelDownload(greekIntent()) }.isSuccess
            } finally {
                languages = null
                languagesAt = 0L
                runCatching { recognizer.destroy() }
            }
        }
    }

    @RequiresApi(DOWNLOAD_LISTENER_SDK)
    private suspend fun awaitDownload(recognizer: SpeechRecognizer): Boolean =
        withTimeoutOrNull(DOWNLOAD_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : ModelDownloadListener {
                    /** Nothing is drawn from it: the row says «λήψη…» and that is the whole promise. */
                    override fun onProgress(completedPercent: Int) {}
                    override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                    /** The system will fetch it later — on a charger, on wifi. Still a yes. */
                    override fun onScheduled() { if (cont.isActive) cont.resume(true) }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(false) }
                }
                runCatching { recognizer.triggerModelDownload(greekIntent(), mainExecutor, listener) }
                    .onFailure { if (cont.isActive) cont.resume(false) }
            }
        } ?: false

    /**
     * Whether this phone has a connection that could reach a cloud recogniser. Advisory and cheap:
     * it is only ever used to tell Chris' code 2 — «Χρειάζεται σύνδεση» — from a phone that has no
     * recognition service at all, and getting it wrong costs one wrong sentence and nothing else.
     */
    private suspend fun online(): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
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

        /** `ModelDownloadListener` arrived in Android 14; 13 can only ask and be polled. */
        const val DOWNLOAD_LISTENER_SDK = 34

        /** How long the engine is given to say what languages it has. Long enough for a cold bind. */
        const val SUPPORT_MS = 5_000L

        /**
         * How long the cached answer stands. A model download is the only thing that changes it, and
         * [downloadGreek] clears the cache itself; this is only so that a «Μίλα» two seconds after
         * the last one does not bind the service again before the microphone opens.
         */
        const val LANGUAGES_FRESH_MS = 30_000L

        /**
         * The bound on one download. Generous — it is a language model over whatever connection the
         * phone has — and a download still running when it expires is not lost: it goes on in the
         * system, and the settings row's next poll sees it land.
         */
        const val DOWNLOAD_MS = 10 * 60_000L
    }
}
