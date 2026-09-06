package gr.dimitris.app.modules.wordcoach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.core.speech.Recognition
import gr.dimitris.app.core.speech.SpeechFailure
import gr.dimitris.app.core.speech.SpeechMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WordCoachState(
    val index: Int = 0,
    val total: Int,
    val item: Item,
    val level: Int = 0,
    val cueText: String? = null,
    val showsWord: Boolean = false,
    val canHint: Boolean = true,
    val isRecording: Boolean = false,
    /** The model is sounding right now: «Άκου» is off for exactly as long as that lasts. */
    val modelPlaying: Boolean = false,
    val selfRecordingPath: String? = null,
    val sttOn: Boolean = false,
    /**
     * False until the settings read has landed. Until then the green primary is drawn but greyed:
     * a button that changes what it does under the thumb of a man with a right hemiparesis is worse
     * than a button he has to wait a beat for.
     */
    val sttResolved: Boolean = false,
    val listening: Boolean = false,
    /** How loud he is, 0..1, while the window is open. Drawn by the listening indicator. */
    val listenLevel: Float = 0f,
    val heard: String? = null,
    val heardMatched: Boolean = false,
    /** How many windows he has used on this word. */
    val sttTries: Int = 0,
    /** «Δοκίμασε ξανά» is on the screen: one miss, and nothing else has changed. */
    val nudge: Boolean = false,
    /**
     * Whether «Το είπα!» is his to press. Always true with recognition off; with it on, once the
     * phone has agreed with him or has asked him twice.
     */
    val canConfirm: Boolean = true,
    /** True after "Το είπα!": success mark shown, only "Επόμενο" remains. */
    val confirmed: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
)

class WordCoachViewModel(private val graph: AppGraph, private val items: List<Item>, private val sessionId: String?) : ViewModel() {
    private var ladder = CueLadder(items.first())
    private var startedAt = now()

    /** How many times he asked to hear this word. It goes into the attempt's detail as it stands. */
    private var listens = 0

    /** Whatever this screen is saying: the cue, the model, or the model and his own take. */
    private var speakJob: Job? = null

    /**
     * Bumped by every new utterance. A cancelled job's `finally` can land after the next one has
     * started, and it must not put «Άκου» back for a model that is still playing.
     */
    private var speakToken = 0

    /**
     * The write of the last take, as a value: the attempt awaits the id instead of reading a field
     * that the next word has already cleared. A finish landing in the same breath as the save used
     * to read the field either before it was written or after [next] had nulled it, and the take
     * quietly stopped belonging to the word it was made for.
     */
    private var recordingSave: Deferred<String?>? = null

    /**
     * The attempt + schedule write of the word just finished. It runs on the app scope, so [next]
     * joins it before saying "done": the session counts rows, and a row still in flight is not one.
     */
    private var lastWrite: Job? = null

    /**
     * True from "Το είπα!"/"Παράλειψη" until the next word starts. It carries both guards: one
     * attempt per word, and one advance per finished word.
     */
    private var finishing = false

    /**
     * The gentle check for the word he is on: what the phone heard, how many times it has asked, and
     * whether «Το είπα!» is his to press yet. One per word.
     */
    private var check = GentleCheck()

    /**
     * The loudest sample of his last take. It rides along in the attempt's detail so that
     * [gr.dimitris.app.core.audio.Recorded.SILENCE_PEAK] can be moved on evidence from his own phone.
     */
    private var lastPeak: Int? = null

    /** The open recognition window, so leaving or moving on can close it. */
    private var listenJob: Job? = null

    /**
     * True once a window failed because the phone could not listen. It only ever opens the confirm,
     * never closes it: a recogniser that broke once must not be able to take «Το είπα!» away again
     * on the next go.
     */
    private var recogniserBroke = false

    private val _state = MutableStateFlow(WordCoachState(total = items.size, item = items.first()))
    val state: StateFlow<WordCoachState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // isAvailable asks the package manager across a binder: not on the thread drawing the word.
            val on = graph.settings.sttEnabled.first() && withContext(Dispatchers.Default) { graph.stt.isAvailable }
            // With recognition off nothing about this screen changes, «Το είπα!» included.
            _state.update { it.copy(sttOn = on, sttResolved = true, canConfirm = !on || check.canConfirm) }
        }
        // Only while a window is open: the bar belongs to the microphone, and nothing else draws it.
        viewModelScope.launch {
            graph.stt.level.collect { l -> _state.update { if (it.listening) it.copy(listenLevel = l) else it } }
        }
    }

    private fun publishLadder() = _state.update {
        it.copy(level = ladder.level, cueText = ladder.cueText(), showsWord = ladder.showsWord, canHint = ladder.canHint)
    }

    /** One more hint. Levels 1–2 are spoken by TTS; 3–4 use the model voice. */
    fun hint() {
        // A level 3-4 cue says the whole word out loud, so this is the same door «Άκου» is: never
        // into an open recogniser. «Βοήθεια» is not composed while listening either, but the guard
        // belongs here — the view model is the layer that cannot be got round.
        if (!ladder.canHint || _state.value.listening) return
        ladder.hint()
        publishLadder()
        speakCue()
    }

    /**
     * «Άκου», and the picture, which is the same button with a picture on it. The model is said —
     * the caregiver's recording if she made one, else Greek TTS — whatever rung the ladder is on,
     * level 0 included: this is the one thing the app never makes him earn.
     *
     * It costs him the cue level, not the word: [CueLadder.listened] scores the attempt at 3
     * without moving the hint sequence, so the screen shows no more than it did and the caregiver's
     * numbers still say the word needed help.
     */
    fun listenModel() {
        val s = _state.value
        // [s.listening] is the recogniser holding the microphone open. Speaking into it would have
        // the phone hear its own model, match it, and congratulate him for a word he never said —
        // the same dishonesty this button exists to remove, pointing the other way.
        if (s.isRecording || s.listening || s.modelPlaying) return
        // Reachable after «Το είπα!» too, and through the picture at any time: the model is never
        // taken away. Those listens land after [finish] has read the ladder and the count, so they
        // change nothing — the listen that decides the row is always one he made before answering.
        listens++
        ladder.listened()
        speaking { report(graph.speaker.speak(s.item)) }
    }

    private fun speakCue() {
        val s = _state.value
        if (s.listening) return
        speaking {
            when (s.level) {
                1, 2 -> ladder.cueText()?.let { report(graph.speaker.speakText(it)) }
                3, 4 -> report(graph.speaker.speak(s.item))
                else -> Unit
            }
        }
    }

    /**
     * Runs one utterance of this screen, and only one: a new tap replaces whatever was sounding.
     * `quiet()` as well as cancelling, because a cancel only lands at the next suspension point and
     * the old voice would be heard under the new one.
     */
    private fun speaking(block: suspend () -> Unit) {
        speakJob?.cancel()
        graph.voice.quiet()
        val token = ++speakToken
        _state.update { it.copy(modelPlaying = true) }
        speakJob = viewModelScope.launch {
            try {
                block()
            } finally {
                if (speakToken == token) _state.update { it.copy(modelPlaying = false) }
            }
        }
    }

    /**
     * Records the outcome of one speak or play attempt. Silence is the one failure Dimitris cannot
     * diagnose himself, so it is said on the screen and cleared by the next sound that comes out.
     */
    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("wordcoach speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    fun toggleRecording() {
        if (_state.value.isRecording) {
            runCatching { graph.voice.stopRecording() }
                .onSuccess { rec ->
                    val itemId = _state.value.item.id
                    lastPeak = rec.peakAmplitude
                    // A take nobody spoke into is not a take. Chris found that recording never
                    // checked anything, so silence "passed" and was saved as his voice — and then
                    // played back to him by «Σύγκριση» as his. It is deleted and he is asked again;
                    // the word stays open and nothing is written.
                    if (rec.isSilent) {
                        rec.file.delete()
                        _state.update { it.copy(isRecording = false, error = Recorded.SILENT_TAKE) }
                        return@onSuccess
                    }
                    _state.update { it.copy(isRecording = false, selfRecordingPath = graph.files.relativize(rec.file), error = null) }
                    // The app scope, not this screen's: the take is on disk, its row must land too.
                    recordingSave = graph.scope.async {
                        try {
                            graph.items.addRecording(itemId, rec.file, rec.durationMs, Who.DIMITRIS).id
                        } catch (ce: CancellationException) {
                            // A cancelled write is not a failed one, and must not be logged as one.
                            throw ce
                        } catch (e: Exception) {
                            graph.errors.record("wordcoach save recording", e)
                            null
                        }
                    }
                }
                .onFailure { e -> graph.errors.record("wordcoach record stop", e); _state.update { it.copy(isRecording = false, error = "Πολύ σύντομη ηχογράφηση") } }
        } else {
            // Silence first, and not only through Voice.startRecording's own quiet(): the utterance
            // is a coroutine of ours, and one still running would leave «Άκου» greyed over an open
            // microphone. He now presses «Άκου» and reaches straight for the mic — that is the
            // behaviour this screen invites — so his take must never contain the phone's own model.
            silence()
            runCatching { graph.voice.startRecording() }
                .onSuccess { _state.update { it.copy(isRecording = true, error = null) } }
                .onFailure { e -> graph.errors.record("wordcoach record start", e); _state.update { it.copy(error = "Δεν ξεκίνησε η ηχογράφηση") } }
        }
    }

    /**
     * Stops whatever this screen was saying and takes «Άκου» out of its playing state. The token is
     * bumped so the cancelled job's `finally` cannot re-open a button for a model that has already
     * been replaced by something else.
     */
    private fun silence() {
        speakJob?.cancel()
        speakToken++
        graph.voice.quiet()
        _state.update { it.copy(modelPlaying = false) }
    }

    /** The microphone was refused: say so instead of a button that does nothing. */
    fun micDenied() = _state.update { it.copy(error = MIC_DENIED) }

    /** Model voice, then his own recording. */
    fun playComparison() {
        // Never into a live recogniser: the phone would hear its own model, match it, and
        // congratulate him for a word he never said.
        if (_state.value.listening) return
        val path = _state.value.selfRecordingPath ?: return
        speaking {
            report(graph.speaker.speak(_state.value.item))
            report(graph.voice.play(graph.files.resolve(path)))
        }
    }

    /**
     * «Μίλα». The recognition window opens and waits for him — no stopwatch, and no plain take that
     * checks nothing. What comes back goes to [judge].
     */
    fun listen() {
        val s = _state.value
        if (!s.sttOn || s.listening || s.isRecording || s.confirmed || finishing) return
        // The microphone is about to open: whatever the speaker was saying stops here, or the
        // recogniser hears the model and answers «Μπράβο!» to the phone's own voice.
        silence()
        _state.update { it.copy(listening = true, listenLevel = 0f, heard = null, heardMatched = false, nudge = false, error = null) }
        listenJob = viewModelScope.launch {
            graph.stt.listen().fold(
                onSuccess = { t -> judge(t.text.takeIf { it.isNotBlank() }) },
                onFailure = { e -> recogniserFailed(e) },
            )
        }
    }

    /** «Στοπ»: the window closes now, and what it had heard still comes back through [listen]. */
    fun stopListening() {
        if (_state.value.listening) graph.stt.stop()
    }

    /**
     * What the phone heard, weighed against the word — the gentle check of spec §12.
     *
     * A match confirms the word for him at the cue level he was on: he said it, and being made to
     * press a button to agree with the phone is one step too many for a man who has just done the
     * hard part. A miss buys one «Δοκίμασε ξανά» with the cue untouched and «Άκου» still there;
     * after the second, «Το είπα!» comes back and confirms exactly as it did before. A window that
     * heard nothing counts as one of the two — a dead recogniser must not be able to lock him out
     * of confirming work he really did.
     */
    private fun judge(text: String?) {
        val matched = text != null && SpeechMatch.phraseMatches(text, _state.value.item.text)
        val verdict = check.record(text, matched)
        _state.update {
            it.copy(
                listening = false, listenLevel = 0f, heard = text, heardMatched = matched,
                sttTries = check.tries, nudge = check.nudging, canConfirm = check.canConfirm || recogniserBroke,
                error = if (text == null) HEARD_NOTHING else null,
            )
        }
        if (verdict == GentleCheck.Verdict.MATCHED) confirm() else graph.feedback.nudge()
    }

    /**
     * A window that came back with no words at all.
     *
     * Silence is his, and is answered gently: the line and another go. But a phone that could not
     * listen — no network, a wedged service, the microphone taken by something else — is the
     * *phone's* failure, and Chris' whole report was about the app putting its own trouble on him.
     * So it costs him nothing: no try is spent, the confirm opens at once and stays open, and the
     * caregiver gets one line that points at the settings rather than a red word about his voice.
     */
    private fun recogniserFailed(e: Throwable) {
        if (e !is SpeechFailure.NotWorking) {
            judge(null)
            return
        }
        // Once per run, not once per window: «Μίλα» stays on the screen after the latch, and an
        // offline phone would otherwise fill the caregiver's Σφάλματα with the same row every tap.
        if (!recogniserBroke) graph.errors.record("wordcoach listen", e)
        recogniserBroke = true
        _state.update {
            it.copy(
                listening = false, listenLevel = 0f, heard = null, heardMatched = false,
                nudge = false, canConfirm = true, error = Recognition.NOT_WORKING,
            )
        }
    }

    /** Closes any open window and stops claiming to be listening. */
    private fun stopRecogniser() {
        listenJob?.cancel()
        listenJob = null
        // Only a window that is actually open is closed: «Στοπ» is his word, and a stop sent for a
        // window nobody opened would be one more thing happening that he never asked for.
        if (_state.value.listening) graph.stt.stop()
        _state.update { it.copy(listening = false, listenLevel = 0f) }
    }

    fun confirm() {
        if (_state.value.confirmed || finishing) return
        finish(confirmed = true)
    }

    fun skip() {
        if (_state.value.confirmed || finishing) return
        finish(confirmed = false)
    }

    private fun finish(confirmed: Boolean) {
        finishing = true
        if (_state.value.isRecording) toggleRecording()
        stopRecogniser()
        val s = _state.value
        val outcome = ladder.outcomeFor(confirmed)
        // Read eagerly: the ladder is replaced the moment the next word starts. The recorded level,
        // not the rung on screen: a word he asked to hear was a word said to him.
        val level = ladder.recordedLevel
        val save = recordingSave
        // Every row says how many times he asked for the model, so a caregiver reading a run of
        // assisted words can see whether it was the ladder or the listening that made them assisted.
        // What the phone made of him, and how loud his take was. The first is only meaningful while
        // recognition is on; the second is the calibration data for [Recorded.SILENCE_PEAK].
        val said = s.heard?.let { ""","sttHeard":${jsonString(it)}""" }.orEmpty()
        val stt = if (s.sttOn) """$said,"sttMatched":${s.heardMatched},"sttTries":${s.sttTries}""" else ""
        val peak = lastPeak?.let { ""","peak":$it""" }.orEmpty()
        val detail = """{"listened":$listens$stt$peak}"""
        // The app scope, not this screen's: pressing back must not lose the word he just said.
        lastWrite = graph.scope.launch {
            runCatching {
                // The recording row carries the id the attempt points at, so let its write land
                // first — and inside the guard, because the app scope has no exception handler and
                // a throw from the await would be an uncaught crash rather than a logged failure.
                val recordingId = save?.await()
                graph.db.attempts().insert(
                    Attempt(itemId = s.item.id, module = ModuleId.WORDCOACH, sessionId = sessionId, startedAt = startedAt,
                        durationMs = now() - startedAt, outcome = outcome, cueLevel = level, selfRecordingId = recordingId, detail = detail)
                )
                graph.scheduler.record(s.item.id, ModuleId.WORDCOACH, outcome, level)
            }.onFailure { graph.errors.record("wordcoach finish", it) }
        }
        if (confirmed) {
            graph.feedback.success()
            _state.update { it.copy(confirmed = true) }
        } else {
            graph.feedback.nudge()
            next()
        }
    }

    fun next() {
        // Only a word that was finished moves on. A second tap on «Επόμενο» — the button is still
        // there for a frame after the first — would otherwise skip the word that just arrived.
        if (!finishing) return
        finishing = false
        // A take still running belongs to the word being left behind, and so does whatever this
        // screen was saying: a model still speaking over the next picture is the previous word's.
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        silence()
        stopRecogniser()
        val i = _state.value.index + 1
        if (i >= items.size) {
            // The session counts attempt rows as soon as it is told the module is done, so the last
            // word's write has to be in the database before "done" ever reaches the screen.
            val write = lastWrite
            viewModelScope.launch { write?.join(); _state.update { it.copy(done = true) } }
            return
        }
        ladder = CueLadder(items[i])
        startedAt = now()
        listens = 0
        recordingSave = null
        lastPeak = null
        check = GentleCheck()
        val on = _state.value.sttOn
        // Resolved once per run, not once per word: only the first word can ever wait for it.
        _state.value = WordCoachState(
            index = i, total = items.size, item = items[i],
            sttOn = on, sttResolved = _state.value.sttResolved, canConfirm = !on || recogniserBroke,
        )
    }

    /**
     * The user pressed back. Whatever the microphone or the speaker was doing stops here, and [then]
     * waits for the last word's write: the session counts rows the moment it is told, so leaving
     * before the row lands would lose the word he had just said.
     */
    fun leave(then: () -> Unit) {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        silence()
        stopRecogniser()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    /**
     * The screen has gone but the ViewModel has not — he tapped «Μίλα» and the module is still on
     * the back stack. The take belongs to the word he was on, so it is dropped rather than left
     * open over his talk board; and the state has to stop claiming it is recording, or the button
     * he finds on the way back is a «Στοπ» for a microphone that is no longer running.
     */
    fun screenGone() {
        silence()
        stopRecogniser()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.update { it.copy(isRecording = false, listening = false, listenLevel = 0f) }
    }

    override fun onCleared() {
        speakJob?.cancel()
        stopRecogniser()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."
        const val MIC_DENIED = "Χωρίς άδεια μικροφώνου"

        /** Recognition came back with nothing. Never a verdict on him: the invitation stays open. */
        const val HEARD_NOTHING = "Δεν άκουσα τίποτα. Δοκίμασε ξανά αν θέλεις."
    }
}
