package gr.dimitris.app.modules.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.speech.OnDeviceSupport
import gr.dimitris.app.core.speech.Recognition
import gr.dimitris.app.core.speech.RecognizerIntents
import gr.dimitris.app.core.speech.SpeechFailure
import gr.dimitris.app.core.speech.take
import gr.dimitris.app.modules.wordcoach.CueLadder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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

/**
 * One finished turn of a dialogue, as the row will carry it.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than
 * on a phone: see `TelemetryTest`. The first keys are the ones phase 5 already wrote, in the order
 * they always had — [scriptId] included, which is the one id in any detail and is here because the
 * row would otherwise not say which dialogue the turn belonged to. Everything after [peak] is new
 * and is what `docs/ADAPTATION.md` would tune the cue ladder and the recognition window from.
 */
internal fun scriptsDetail(
    scriptId: String,
    position: Int,
    listened: Int,
    sttOn: Boolean,
    heard: String?,
    matched: Boolean,
    sttTries: Int,
    peak: Int?,
    ms: Long,
    hintMsFirst: Long?,
    takeMs: Long?,
    /** How hard the dialogue said it was ([gr.dimitris.app.core.data.ScriptLine.tier]). */
    tier: Int = ScriptLine.DEFAULT_TIER,
    /** What a good answer to this turn had to convey, as the caregiver or the seed wrote it. */
    intent: String? = null,
    /** What the judge decided, in [gr.dimitris.app.core.judge.Verdict.detail]'s shape. Empty when it was not asked. */
    judge: Map<String, Any?> = emptyMap(),
): String = Adapt.detail {
    kept("scriptId", scriptId)
    put("position", position)
    put("listened", listened)
    if (sttOn) {
        put("sttHeard", heard)
        put("sttMatched", matched)
        put("sttTries", sttTries)
    }
    put("peak", peak)
    put("ms", ms)
    put("hintMsFirst", hintMsFirst)
    put("takeMs", takeMs)
    put("sttOn", sttOn)
    if (sttOn) put("sttWaitMs", RecognizerIntents.COMPLETE_SILENCE_MS)
    // Phase 12, and last, so every row written before it stays byte-identical.
    put("tier", tier)
    put("intent", intent)
    put("judge", judge)
}

enum class ScriptPhase { LOADING, OTHER_SPEAKING, WAITING_FOR_DIMITRIS, FINISHED }

data class ScriptsState(
    val title: String = "",
    val lines: List<Pair<ScriptLine, Item>> = emptyList(),
    val index: Int = 0,
    val phase: ScriptPhase = ScriptPhase.LOADING,
    val level: Int = 0,
    val cueText: String? = null,
    val showsWord: Boolean = false,
    val canHint: Boolean = false,
    /** His turns that were passed over, so the conversation still shows what happened in them. */
    val skippedLines: Set<Int> = emptySet(),
    /** The microphone is open on this turn: «Ηχογράφηση» has become «Στοπ». */
    val isRecording: Boolean = false,
    /** The model line is sounding right now: «Άκου» is off for exactly as long as that lasts. */
    val modelPlaying: Boolean = false,
    /** His own take of this turn, once he has made one: what «Άκου» plays after the model. */
    val selfRecordingPath: String? = null,
    /** Recognition is on and this device has it: his turns are checked, gently. */
    val sttOn: Boolean = false,
    /**
     * The recogniser keeps his take itself, so «Μίλα» is the only microphone on this turn and
     * «Ηχογράφηση» is gone from the card. False on the fallback path, where it stays as it was.
     */
    val oneControl: Boolean = false,
    /**
     * False until the settings read has landed. Until then the green primary is drawn but greyed:
     * a button that changes what it does under the thumb of a man with a right hemiparesis is worse
     * than a button he has to wait a beat for.
     */
    val sttResolved: Boolean = false,
    val listening: Boolean = false,
    /** How loud he is, 0..1, while the window is open. Drawn by the listening indicator. */
    val listenLevel: Float = 0f,
    /**
     * The window has closed and the judge has not answered yet. Nothing is asked of him — the card
     * and the three buttons stay exactly where they are — but «Μίλα» is greyed for the moment it
     * takes, or a second tap would open a window over a turn that is about to be confirmed.
     */
    val thinking: Boolean = false,
    val heard: String? = null,
    val heardMatched: Boolean = false,
    /** How many windows he has used on this turn. */
    val sttTries: Int = 0,
    /** «Δοκίμασε ξανά» is on the screen: one miss, and nothing else has changed. */
    val nudge: Boolean = false,
    /**
     * The full grammatical form of his answer, when the judge sent one back for him to repeat. Shown
     * in the turn card and said out loud once — the bottom row stays «Μίλα»/«Άκου»/«Παράλειψη».
     */
    val expanded: String? = null,
    /** The judge's one warm Greek line about this turn, or nothing. Never a verdict on him. */
    val feedback: String? = null,
    /**
     * Whether «Το είπα!» is his to press. Always true with recognition off; with it on, once the
     * phone has agreed with him or has asked him twice.
     */
    val canConfirm: Boolean = true,
    val done: Boolean = false,
    /** The dialogue was gone by the time the screen opened: there is nothing to run. */
    val missing: Boolean = false,
    val error: String? = null,
)

/**
 * One dialogue, start to finish. The other person's lines are spoken — the caregiver's take if she
 * recorded one, else Greek TTS — and the turn moves on when the *utterance* ends, never on a timer:
 * a conversation that hurried him would be worse than no rehearsal at all. His own lines wait, with
 * the word coach's cue ladder under them.
 *
 * One Attempt per turn of his; one schedule row per dialogue, written when the last line is done.
 */
class ScriptsViewModel(
    private val graph: AppGraph,
    /** Any item of the dialogue: the script is resolved from the line it belongs to. */
    private val seedItemId: String,
    private val sessionId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(ScriptsState())
    val state: StateFlow<ScriptsState> = _state.asStateFlow()

    private var scriptId: String = ""
    private var lines: List<Pair<ScriptLine, Item>> = emptyList()

    /** The ladder for the turn he is on, or null while somebody else is talking. */
    private var ladder: CueLadder? = null
    private var startedAt = now()

    /** The highest cue level any of his turns needed: what the dialogue's Leitner box is scored on. */
    private var worstCue = 0
    private var skipped = false

    /**
     * True from a finished turn until the screen has actually *shown* the next one — cleared by
     * [turnReady], not by [startAt].
     *
     * Two of his turns in a row (a caregiver may write them) used to put both halves of the guard
     * back to their open state inside one call stack, so a second tap of «Το είπα!» — an easy slip
     * one-handed — wrote a phantom attempt for a turn he never saw and skipped it in the
     * conversation. Nothing may be confirmed until it has been on screen.
     */
    private var finishing = false

    /** The schedule row is the dialogue's, and is written exactly once. */
    private var recorded = false

    private var loadJob: Job? = null

    /** The other person's line. It is the only job that advances the dialogue. */
    private var speakJob: Job? = null

    /** A hint, a listen, or a bubble tapped again. Never advances anything. */
    private var cueJob: Job? = null

    /** How many times he asked to hear the line of the turn he is on. It goes into the attempt. */
    private var listens = 0

    /**
     * Bumped by every sound [cue] starts and by every turn change. A cancelled job's `finally` can
     * land after the next one has started, and it must not put «Άκου» back — or the record button —
     * for a model that is still playing.
     */
    private var cueToken = 0

    /** The closing line and the "done" that follows it. */
    private var endJob: Job? = null

    /**
     * The write of his take for the turn he is on, as a value: the attempt awaits the id instead of
     * reading a field the next turn has already cleared. Exactly the word coach's arrangement, for
     * exactly its reason.
     */
    private var recordingSave: Deferred<String?>? = null

    /**
     * The attempt and schedule writes, chained. They run on the app scope so pressing back cannot
     * lose the turn he just took, and whoever joins the last one is joining every one: the app scope
     * is a pool with no ordering of its own.
     */
    private var lastWrite: Job? = null

    /**
     * False from the moment the screen leaves composition — he tapped «Μίλα» — until it comes back.
     * A dialogue that kept speaking would be talking over his talk board, and one that kept
     * advancing would hand him a turn he never heard.
     */
    private var onScreen = true

    /**
     * The check for the turn he is on: what the phone made of his answer, how many times it has
     * asked, and whether «Το είπα!» is his to press yet. One per turn of his.
     */
    private var check = newCheck()

    /**
     * Whether the judge will really be asked — «Έλεγχος με Claude» on and a key saved — read once
     * when the screen opens. False until it lands, which is the phase-11 behaviour, so the first
     * turn of a run can never wait on a keystore read.
     */
    private var judgeReady = false

    /** His own dot row for this module, read once. It says how much grammar the judge insists on. */
    private var dots = Difficulty.DEFAULT

    /** What the judge decided about the turn he is on, for the attempt row. Cleared with the turn. */
    private var judgeDetail: Map<String, Any?> = emptyMap()

    /**
     * The loudest sample of his last take. It rides along in the attempt's detail so that
     * [gr.dimitris.app.core.audio.Recorded.SILENCE_PEAK] can be moved on evidence from his own phone.
     */
    private var lastPeak: Int? = null

    /** How long his last take ran. See [scriptsDetail]. */
    private var lastTakeMs: Long? = null

    /**
     * When «Βοήθεια» was first pressed on this turn, as a stopwatch from the turn arriving. How long
     * he is willing to stay with a line before asking is the number any rule about *when* to offer
     * the prop would have to be built on. See `docs/ADAPTATION.md`.
     */
    private var firstHintAt: Long? = null

    /** The open recognition window, so leaving or moving on can close it. */
    private var listenJob: Job? = null

    /**
     * True once a window failed because the phone could not listen. It only ever opens the confirm,
     * never closes it: a recogniser that broke once must not be able to take «Το είπα!» away again
     * on the next go.
     */
    private var recogniserBroke = false

    /**
     * Which honest error classes have already reached the caregiver's «Σφάλματα» in this run. One row
     * per kind of trouble rather than one per tap — and both, when a phone has two things wrong.
     */
    private val reported = mutableSetOf<Recognition.ErrorClass>()

    init {
        load()
        // A new screen is a new run: the judge writes one row per failure class per run, and a
        // dialogue run on an offline phone must not fill «Σφάλματα» with one row per turn.
        graph.judge.newRun()
        viewModelScope.launch {
            // isAvailable asks the package manager across a binder: not on the thread drawing the turn.
            val on = graph.settings.sttEnabled.first() && withContext(Dispatchers.Default) { graph.stt.isAvailable }
            // Which engine will answer decides how many microphone buttons the turn has: one when the
            // recogniser hands his own audio back, the old pair when it does not.
            val one = on && graph.stt.engine() == OnDeviceSupport.Engine.ON_DEVICE
            // The two reads the judge needs, in the same breath and before the button is live:
            // `available()` opens the encrypted key file, and a dialogue is four of his turns — the
            // first of them must be weighed the same way as the last, not left to phase 11's
            // comparison because a keystore read had not landed yet. It is one file read long, and
            // the screen already waits for the settings before offering him anything.
            dots = runCatching { graph.settings.difficulty(ModuleId.SCRIPTS).first() }
                .onFailure { graph.errors.record("scripts difficulty", it) }
                .getOrDefault(Difficulty.DEFAULT)
            judgeReady = runCatching { graph.judge.available() }
                .onFailure { graph.errors.record(TurnCheck.WHERE, it) }
                .getOrDefault(false)
            // With recognition off nothing about this screen changes, «Το είπα!» included.
            _state.update { it.copy(sttOn = on, oneControl = one, sttResolved = true, canConfirm = !on || check.canConfirm) }
        }
        // Only while a window is open: the bar belongs to the microphone, and nothing else draws it.
        viewModelScope.launch {
            graph.stt.level.collect { l -> _state.update { if (it.listening) it.copy(listenLevel = l) else it } }
        }
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            val found = try {
                graph.scripts.scriptOf(seedItemId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                graph.errors.record("scripts load", e)
                null
            }
            if (found == null || found.lines.isEmpty()) {
                // Deleted between the plan and the tap. Nothing to run and nothing to write: say so
                // and let him out, rather than hold him on a screen that will never do anything.
                _state.update { it.copy(phase = ScriptPhase.FINISHED, missing = true, done = true) }
                return@launch
            }
            scriptId = found.script.id
            lines = found.lines
            _state.update { it.copy(title = found.script.title, lines = found.lines) }
            startAt(0)
        }
    }

    private fun startAt(i: Int) {
        // A take belongs to the turn it was made for, and so does its write.
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        recordingSave = null
        // The listens belong to the turn being left, and so does any sound still in flight: the new
        // turn starts with its own count, in silence, and with «Άκου» live from its first frame.
        listens = 0
        lastPeak = null
        lastTakeMs = null
        firstHintAt = null
        check = newCheck()
        judgeDetail = emptyMap()
        stopCue()
        stopRecogniser()
        val (line, item) = lines[i]
        if (line.speaker == Speaker.OTHER) {
            ladder = null
            _state.update {
                it.copy(
                    index = i, phase = ScriptPhase.OTHER_SPEAKING, level = 0, cueText = null, showsWord = false,
                    canHint = false, isRecording = false, modelPlaying = false, selfRecordingPath = null,
                    heard = null, heardMatched = false, sttTries = 0, nudge = false,
                    thinking = false, expanded = null, feedback = null,
                    // A recogniser that broke stays broken: the confirm it opened is not taken back.
                    canConfirm = !it.sttOn || recogniserBroke,
                )
            }
            if (onScreen) speakOther(i)
        } else {
            val next = CueLadder(item)
            ladder = next
            startedAt = now()
            _state.update {
                it.copy(
                    index = i, phase = ScriptPhase.WAITING_FOR_DIMITRIS,
                    level = next.level, cueText = next.cueText(), showsWord = next.showsWord, canHint = next.canHint,
                    isRecording = false, modelPlaying = false, selfRecordingPath = null,
                    heard = null, heardMatched = false, sttTries = 0, nudge = false,
                    thinking = false, expanded = null, feedback = null,
                    // A recogniser that broke stays broken: the confirm it opened is not taken back.
                    canConfirm = !it.sttOn || recogniserBroke,
                )
            }
        }
    }

    /**
     * The check for one turn of his. The two reads it needs are taken as they stand when the window
     * closes rather than when the turn opened: the Greek pack, the key and the toggle can all land
     * mid-dialogue, and the turn after that should be judged.
     */
    private fun newCheck() = TurnCheck(
        judged = { judgeReady },
        difficulty = { dots },
        askJudge = { ask -> graph.judge.judge(ask) },
        record = { where, e -> graph.errors.record(where, e) },
    )

    /**
     * The screen has drawn the turn at the current index, so it may now be answered. This is what
     * clears [finishing]: a turn that was never on screen cannot be confirmed by a stray second tap.
     */
    fun turnReady() { finishing = false }

    /**
     * The other person's turn. The wait *is* the utterance — no timer decides when they have
     * finished — and a voice that failed still moves the conversation on: a phone with no Greek
     * voice must not be able to strand him on somebody else's line.
     */
    private fun speakOther(i: Int) {
        // Started only once the field holds it. viewModelScope is Main.immediate, so a body that
        // finished without suspending would otherwise leave `speakJob` pointing at a dead job while
        // the live one — the one [stopEverything] has to be able to cancel — went unreferenced.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            report(graph.speaker.speak(lines[i].second))
            advanceFrom(i)
        }
        speakJob = job
        job.start()
    }

    /**
     * «Συνέχεια»: he has heard enough of the other person's line. The utterance is cut and the
     * conversation moves on — his own choice, not a timer, and the one enabled control on a screen
     * that would otherwise have none while the phone talks.
     */
    fun continueNow() {
        if (finishing || _state.value.phase != ScriptPhase.OTHER_SPEAKING) return
        finishing = true
        speakJob?.cancel()
        graph.voice.quiet()
        advanceFrom(_state.value.index)
    }

    private fun publishLadder(l: CueLadder) = _state.update {
        it.copy(level = l.level, cueText = l.cueText(), showsWord = l.showsWord, canHint = l.canHint)
    }

    /** One more prop. Levels 1–2 are spoken by TTS; 3–4 use the model voice, exactly as the word coach. */
    fun hint() {
        val l = ladder ?: return
        // A level 3-4 cue says the whole line out loud, so this is the same door «Άκου» is: never
        // into an open recogniser. «Βοήθεια» is not composed while listening either, but the guard
        // belongs here — the view model is the layer that cannot be got round.
        if (_state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS || !l.canHint || _state.value.listening) return
        if (firstHintAt == null) firstHintAt = now()
        l.hint()
        publishLadder(l)
        speakCue()
    }

    /**
     * «Άκου». His own line, said to him — the caregiver's recording of it if she made one, else
     * Greek TTS — from the moment the turn appears and at every rung of the ladder. Chris found the
     * old «Άκου ξανά» dead until «Βοήθεια» had been pressed; a man who cannot retrieve a word is
     * not helped by being made to fail for it first (spec §12).
     *
     * Once there is a take of his own it plays that straight after the model, back to back: the old
     * «Σύγκριση», folded into the button it was always next to. One «Άκου», and what it does grows
     * with what there is to hear.
     *
     * The help is written down instead of being refused: [CueLadder.listened] scores the turn at 3
     * without moving the hint sequence, so «Βοήθεια» carries on from where it was and the
     * conversation gives nothing away that it had not already.
     */
    fun listenModel() {
        val l = ladder ?: return
        val s = _state.value
        // [s.listening] is the recogniser holding the microphone open. Speaking the line into it
        // would have the phone hear its own model, match it, and congratulate him for a turn he
        // never took — the same dishonesty this button exists to remove, pointing the other way.
        if (s.phase != ScriptPhase.WAITING_FOR_DIMITRIS || s.isRecording || s.modelPlaying || s.listening) return
        val item = lines.getOrNull(s.index)?.second ?: return
        listens++
        l.listened()
        val take = s.selfRecordingPath
        cue {
            report(graph.speaker.speak(item))
            if (take != null) report(graph.voice.play(graph.files.resolve(take)))
        }
    }

    /** A line already said, tapped again: what did they ask me? A dialogue he cannot re-hear is a trap. */
    fun replay(index: Int) {
        // Never into a live recogniser: the other person's line is a line, and the phone would
        // happily hear itself say it.
        if (_state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS || _state.value.listening) return
        val item = lines.getOrNull(index)?.second ?: return
        cue { report(graph.speaker.speak(item)) }
    }

    private fun speakCue() {
        val l = ladder ?: return
        if (_state.value.listening) return
        val item = lines.getOrNull(_state.value.index)?.second ?: return
        val level = l.level
        val text = l.cueText()
        cue {
            when (level) {
                1, 2 -> text?.let { report(graph.speaker.speakText(it)) }
                3, 4 -> report(graph.speaker.speak(item))
                else -> Unit
            }
        }
    }

    /**
     * Runs one sound that is not the dialogue itself — a hint, «Άκου», a bubble tapped again, a
     * comparison. It replaces whatever the last such tap started: `quiet()` as well as cancelling,
     * because a cancel only lands at the next suspension point and the old voice would be heard
     * under the new one.
     *
     * The flag is raised here rather than at the «Άκου» call site, so *every* model this screen
     * plays holds it. A level-3 «Βοήθεια» says the whole line; leaving «Άκου» and «Ηχογράφηση» live
     * under it was the same open microphone, and the cancelled listen's `finally` used to clear the
     * flag out from under the hint that had just replaced it.
     */
    private fun cue(block: suspend () -> Unit) {
        cueJob?.cancel()
        graph.voice.quiet()
        val token = ++cueToken
        _state.update { it.copy(modelPlaying = true) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (cueToken == token) _state.update { it.copy(modelPlaying = false) }
            }
        }
        cueJob = job
        job.start()
    }

    /**
     * Stops whatever extra sound this screen was making and takes «Άκου» and the record button out
     * of their playing state. The token is bumped so a cancelled job's `finally` cannot re-open
     * them for a model that has already been replaced.
     */
    private fun stopCue() {
        cueJob?.cancel()
        cueToken++
        graph.voice.quiet()
        _state.update { it.copy(modelPlaying = false) }
    }

    /**
     * His own voice on his own turn, exactly as the word coach offers it: record, then hear the
     * model and himself back to back. On a rehearsed dialogue the model is his caregiver's own
     * recording of the line, which is the closest thing to the conversation he is practising for.
     */
    fun toggleRecording() {
        if (_state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS) return
        if (_state.value.isRecording) {
            runCatching { graph.voice.stopRecording() }
                .onSuccess { rec ->
                    // A take nobody spoke into is not a take: it is deleted, he is asked again, the
                    // turn stays open and nothing is written. Silence used to pass as his voice.
                    if (!keep(rec)) {
                        _state.update { it.copy(isRecording = false, error = Recorded.SILENT_TAKE) }
                        return@onSuccess
                    }
                    _state.update { it.copy(isRecording = false, error = null) }
                }
                .onFailure { e ->
                    graph.errors.record("scripts record stop", e)
                    _state.update { it.copy(isRecording = false, error = TOO_SHORT) }
                }
        } else {
            // Silence first, and not only through Voice.startRecording's own quiet(): the utterance
            // is a coroutine of ours, and one still running would leave «Άκου» greyed over an open
            // microphone. He now presses «Άκου» and reaches straight for the mic — that is the
            // behaviour this turn invites — so his take must never contain the phone's own model.
            stopCue()
            runCatching { graph.voice.startRecording() }
                .onSuccess { _state.update { it.copy(isRecording = true, error = null) } }
                .onFailure { e ->
                    graph.errors.record("scripts record start", e)
                    _state.update { it.copy(error = NO_RECORDING) }
                }
        }
    }

    /** The microphone was refused: say so instead of a button that does nothing. */
    fun micDenied() {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.update { it.copy(isRecording = false, error = MIC_DENIED) }
    }

    /**
     * One finished take, from whichever microphone made it: [toggleRecording]'s, or the one «Μίλα»
     * opened on the on-device path. False when nobody spoke into it, in which case it is already
     * deleted and nothing was written.
     *
     * Both paths end here so that a take is a take: the same silence line, the same row with
     * [Who.DIMITRIS] on it, the same peak and duration in the attempt's detail.
     */
    private fun keep(rec: Recorded): Boolean {
        lastPeak = rec.peakAmplitude
        lastTakeMs = rec.durationMs
        if (rec.isSilent) {
            rec.file.delete()
            return false
        }
        // No turn to attach it to — the dialogue moved under it. The take goes with the turn it was
        // made for rather than staying on disk with nothing pointing at it: `false` means "deleted,
        // nothing written" everywhere this is called from, and it has to mean that here too.
        val itemId = lines.getOrNull(_state.value.index)?.second?.id ?: run {
            rec.file.delete()
            return false
        }
        _state.update { it.copy(selfRecordingPath = graph.files.relativize(rec.file)) }
        // The app scope, not this screen's: the take is on disk, its row must land too.
        recordingSave = graph.scope.async {
            try {
                graph.items.addRecording(itemId, rec.file, rec.durationMs, Who.DIMITRIS).id
            } catch (ce: CancellationException) {
                // A cancelled write is not a failed one, and must not be logged as one.
                throw ce
            } catch (e: Exception) {
                graph.errors.record("scripts save recording", e)
                null
            }
        }
        return true
    }

    /**
     * «Μίλα». The recognition window opens on his turn and waits for him — no stopwatch, and no
     * plain take that checks nothing. What comes back goes to [judge].
     *
     * On the on-device path the same window also hands back his own voice as a file, because the app
     * held the microphone and the engine was fed from it. That take is kept whatever the phone made
     * of the words.
     */
    fun listen() {
        val s = _state.value
        if (!s.sttOn || s.listening || s.thinking || s.isRecording || finishing) return
        if (s.phase != ScriptPhase.WAITING_FOR_DIMITRIS) return
        // The microphone is about to open: whatever was being said stops here, or the recogniser
        // hears the model line and answers «Μπράβο!» to the phone's own voice.
        stopCue()
        _state.update { it.copy(listening = true, listenLevel = 0f, heard = null, heardMatched = false, nudge = false, error = null) }
        listenJob = viewModelScope.launch {
            val heard = graph.stt.listen()
            heard.take?.let { keep(it) }
            heard.fold(
                onSuccess = { t -> weigh(t.text.takeIf { it.isNotBlank() }) },
                onFailure = { e -> recogniserFailed(e) },
            )
            // Which engine answered, read *after* the window rather than before it. Asking first
            // put a service bind — up to [AndroidSpeechToText.SUPPORT_MS] on a cold engine — between
            // the indicator going up and the wait being claimed, and a «Στοπ» landing in that gap
            // was cleared by the claim and lost: «Σε ακούω…» on the screen and a button that did
            // nothing, on the first word of a module, which is the one he is most impatient with.
            // The answer is asked for anyway inside `listen()`, so this is a cached read.
            // Kept fresh rather than remembered from the first turn of the run: the Greek pack can
            // land mid-dialogue, and a card still showing «Ηχογράφηση» beside a «Μίλα» that has
            // started keeping his takes would be two microphones.
            val one = graph.stt.engine() == OnDeviceSupport.Engine.ON_DEVICE
            _state.update { it.copy(oneControl = one) }
        }
    }

    /** «Στοπ»: the window closes now, and what it had heard still comes back through [listen]. */
    fun stopListening() {
        if (_state.value.listening) graph.stt.stop()
    }

    /**
     * What the phone made of his answer, and what the turn does about it — spec §13's open dialogue.
     *
     * The rules are [TurnCheck]'s, so that they can be argued with off a phone; this is what the
     * screen does with them. An answer that counts confirms the turn for him at the cue level he was
     * on — being made to press a button to agree with the phone is one step too many for a man who
     * has just done the hard part. One that did not buys the judge's warm line and, when there is a
     * full form to hand him, that form: shown in the card and said once, so his next «Μίλα» has
     * something to repeat rather than a wall. After two goes «Το είπα!» comes back and confirms
     * exactly as it did before any of this existed.
     *
     * A window that heard nothing costs him no try and still counts as one of his two goes: a dead
     * recogniser must not be able to lock him out of a turn he really took.
     */
    private suspend fun weigh(text: String?) {
        val i = _state.value.index
        val target = lines.getOrNull(i)?.second?.text.orEmpty()
        // The question he is answering: the other person's last line before this turn. Null when his
        // turn opens the dialogue, which is a conversation he is starting rather than answering.
        val prompt = lines.take(i).lastOrNull { it.first.speaker == Speaker.OTHER }?.second?.text
        // The window has closed; the judge may take a moment. Nothing moves on the screen but the
        // green button, which greys rather than opening a second window over a decided turn.
        val asking = judgeReady && text != null
        if (asking) _state.update { it.copy(listening = false, listenLevel = 0f, thinking = true) }
        val weighed = check.weigh(text, prompt, target)
        judgeDetail = weighed.judge
        _state.update {
            it.copy(
                listening = false, listenLevel = 0f, thinking = false,
                heard = weighed.heard, heardMatched = weighed.accepted,
                sttTries = weighed.tries, nudge = weighed.nudging,
                canConfirm = weighed.canConfirm || recogniserBroke,
                feedback = weighed.feedback,
                // The form he was given stays on the card until the turn moves on, so a second miss
                // does not take away the sentence he was about to repeat.
                expanded = weighed.expanded ?: it.expanded,
                error = if (weighed.heard == null) HEARD_NOTHING else null,
            )
        }
        if (weighed.accepted) {
            confirm()
            return
        }
        graph.feedback.nudge()
        weighed.expanded?.let { sayExpanded(it) }
    }

    /**
     * The full form, said to him once so he can repeat it.
     *
     * It goes through [CueLadder.listened] for the same reason «Άκου» does: the phone has just said a
     * whole line of his out loud, which is level 3's worth of help, and a row that did not carry that
     * would claim he found the sentence himself. The hint sequence does not move, so «Βοήθεια»
     * carries on from exactly where it was.
     */
    private fun sayExpanded(whole: String) {
        ladder?.listened()
        cue { report(graph.speaker.speakText("$SAY_IT_LIKE $whole")) }
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
    private suspend fun recogniserFailed(e: Throwable) {
        if (e !is SpeechFailure.NotWorking) {
            weigh(null)
            return
        }
        // The line names the real trouble — no connection, no Greek, busy — rather than saying only
        // that something went wrong: Chris' phone gave codes 12 and 2 on two different days.
        val klass = Recognition.classOf(e.code)
        // Once per class per run, not once per window: «Μίλα» stays on the screen after the latch,
        // and an offline phone would otherwise fill the caregiver's Σφάλματα with the same row every
        // tap — while a phone with two things wrong with it must still report both.
        if (reported.add(klass)) graph.errors.record("scripts listen", e)
        recogniserBroke = true
        _state.update {
            it.copy(
                listening = false, listenLevel = 0f, thinking = false, heard = null, heardMatched = false,
                nudge = false, canConfirm = true, error = klass.line,
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
        // The job that was cancelled may have been waiting on the judge: nothing is coming back, so
        // the button he is looking at may not stay greyed for a verdict that will never arrive.
        _state.update { it.copy(listening = false, listenLevel = 0f, thinking = false) }
    }

    fun confirm() {
        if (finishing || _state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS) return
        finishLine(confirmed = true)
    }

    fun skip() {
        if (finishing || _state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS) return
        finishLine(confirmed = false)
    }

    private fun finishLine(confirmed: Boolean) {
        val l = ladder ?: return
        finishing = true
        // A take still running belongs to this turn: it is closed and kept, not thrown away.
        if (_state.value.isRecording) toggleRecording()
        stopCue()
        stopRecogniser()
        val s = _state.value
        val i = _state.value.index
        val (line, item) = lines[i]
        // Read eagerly: the ladder and the clock belong to the turn being left behind. The recorded
        // level, not the rung on screen: a line he asked to hear was a line said to him.
        val level = l.recordedLevel
        val outcome = l.outcomeFor(confirmed)
        val began = startedAt
        val position = line.position
        val heard = listens
        val save = recordingSave
        // What the phone made of him, and how loud his take was. The first is only meaningful while
        // recognition is on; the second is the calibration data for [Recorded.SILENCE_PEAK].
        val detail = scriptsDetail(
            scriptId = scriptId,
            position = position,
            listened = heard,
            sttOn = s.sttOn,
            heard = s.heard,
            matched = s.heardMatched,
            sttTries = s.sttTries,
            peak = lastPeak,
            ms = now() - began,
            hintMsFirst = firstHintAt?.let { it - began },
            takeMs = lastTakeMs,
            // What the turn asked of him, and what decided it. Held to 1..5 for the same reason
            // [ScriptsModule.tierOf] holds it: a line synced from a phone that predates tiers is a 0.
            tier = Difficulty.clamp(line.tier),
            intent = line.intent,
            judge = judgeDetail,
        )
        worstCue = maxOf(worstCue, level)
        if (!confirmed) skipped = true
        val prev = lastWrite
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                // The recording row carries the id the attempt points at, so let its write land
                // first — and inside the guard, because the app scope has no exception handler and
                // a throw from the await would be an uncaught crash rather than a logged failure.
                val recordingId = save?.await()
                graph.db.attempts().insert(
                    Attempt(
                        itemId = item.id, module = ModuleId.SCRIPTS, sessionId = sessionId, startedAt = began,
                        durationMs = now() - began, outcome = outcome, cueLevel = level, selfRecordingId = recordingId,
                        detail = detail,
                    )
                )
            }.onFailure { graph.errors.record("scripts line", it) }
        }
        if (confirmed) {
            graph.feedback.success()
        } else {
            graph.feedback.nudge()
            _state.update { it.copy(skippedLines = it.skippedLines + i) }
        }
        advanceFrom(i)
    }

    private fun advanceFrom(i: Int) {
        val next = i + 1
        if (next >= lines.size) finishScript() else startAt(next)
    }

    /**
     * The dialogue is over. One schedule row for the whole of it: CORRECT only if he got through
     * without passing on a turn and without needing the line said to him, ASSISTED when it was said,
     * SKIPPED when a turn went by.
     */
    private fun finishScript() {
        finishing = true
        _state.update { it.copy(phase = ScriptPhase.FINISHED, isRecording = false, selfRecordingPath = null) }
        if (!recorded) {
            recorded = true
            val outcome = outcomeOf(skipped, worstCue)
            val cue = worstCue
            val prev = lastWrite
            lastWrite = graph.scope.launch {
                prev?.join()
                runCatching { graph.scheduler.record(scriptId, ModuleId.SCRIPTS, outcome, cue) }
                    .onFailure { graph.errors.record("scripts finish", it) }
            }
        }
        announceEnd()
    }

    /**
     * The closing line, and only then "done": the session moves on the moment it is told, so saying
     * it first would cut Μπράβο off half-said. The last write is joined too — the session counts
     * rows, and a row still in flight is not one.
     */
    private fun announceEnd() {
        if (!onScreen) return
        val write = lastWrite
        endJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            graph.feedback.success()
            report(graph.speaker.speakText(THE_END))
            write?.join()
            _state.update { it.copy(done = true) }
        }
        endJob = job
        job.start()
    }

    /**
     * Records what one sound did. Silence is the one failure Dimitris cannot diagnose himself, so it
     * is said on the screen and cleared by the next sound that does come out.
     */
    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("scripts speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    /**
     * He pressed back. Everything stops here, and [then] waits for the last write: the session counts
     * rows the moment it is told, so leaving before the row lands would lose the turn he just took.
     */
    fun leave(then: () -> Unit) {
        // The load too: a dialogue that arrived after he asked to leave would start speaking over
        // the screen he is on his way to.
        loadJob?.cancel()
        stopEverything()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.update { it.copy(isRecording = false) }
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    /**
     * The screen has gone but the ViewModel has not — he tapped «Μίλα» and the module is still on the
     * back stack. The dialogue pauses where it stands rather than playing on over his talk board.
     */
    fun screenGone() {
        onScreen = false
        stopEverything()
        // The take belongs to the turn he was on, so it is dropped rather than left open over his
        // talk board; and the state has to stop claiming it is recording, or the button he finds on
        // the way back is a «Στοπ» for a microphone that is no longer running.
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.update { it.copy(isRecording = false) }
    }

    /** He is back. The turn he left is said again from its start: half a question is not a question. */
    fun screenHere() {
        if (onScreen) return
        onScreen = true
        when (_state.value.phase) {
            ScriptPhase.OTHER_SPEAKING -> speakOther(_state.value.index)
            ScriptPhase.FINISHED -> if (!_state.value.done && !_state.value.missing) announceEnd()
            else -> Unit
        }
    }

    private fun stopEverything() {
        speakJob?.cancel()
        endJob?.cancel()
        stopCue()
        stopRecogniser()
    }

    override fun onCleared() {
        loadJob?.cancel()
        stopEverything()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
    }

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        const val MIC_DENIED = "Χωρίς άδεια μικροφώνου"
        const val TOO_SHORT = "Πολύ σύντομη ηχογράφηση"
        const val NO_RECORDING = "Δεν ξεκίνησε η ηχογράφηση"

        /** Recognition came back with nothing. Never a verdict on him: the invitation stays open. */
        const val HEARD_NOTHING = "Δεν άκουσα τίποτα. Δοκίμασε ξανά αν θέλεις."

        /**
         * Over the full form the judge sent back, on the card and out loud. An offer of the words,
         * not a correction of his: he answered the question, and this is the whole sentence for it.
         */
        const val SAY_IT_LIKE = "Πες το έτσι:"

        /** Spoken at the end of the dialogue, before the module hands back. */
        const val THE_END = "Μπράβο! Τέλος διαλόγου."

        /** From cue level 3 the line was said to him: real work, done with help still under it. */
        const val ASSISTED_FROM = 3

        /**
         * The whole dialogue's Leitner outcome, from how the run went: a turn passed over makes it
         * SKIPPED whatever the rest was, otherwise the highest cue any turn needed decides. Lifted
         * out of the run so a plain test can drive the rule the box depends on.
         */
        fun outcomeOf(skipped: Boolean, worstCue: Int): Outcome = when {
            skipped -> Outcome.SKIPPED
            worstCue >= ASSISTED_FROM -> Outcome.ASSISTED
            else -> Outcome.CORRECT
        }
    }
}
