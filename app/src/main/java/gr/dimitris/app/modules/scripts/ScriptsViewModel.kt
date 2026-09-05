package gr.dimitris.app.modules.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.data.now
import gr.dimitris.app.modules.wordcoach.CueLadder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** His own take of this turn, once he has made one: what «Άκου» plays after the model. */
    val selfRecordingPath: String? = null,
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

    /** A hint, a repeat, or a bubble tapped again. Never advances anything. */
    private var cueJob: Job? = null

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

    init { load() }

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
        val (line, item) = lines[i]
        if (line.speaker == Speaker.OTHER) {
            ladder = null
            _state.update {
                it.copy(
                    index = i, phase = ScriptPhase.OTHER_SPEAKING, level = 0, cueText = null, showsWord = false,
                    canHint = false, isRecording = false, selfRecordingPath = null,
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
                    isRecording = false, selfRecordingPath = null,
                )
            }
        }
    }

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
        if (_state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS || !l.canHint) return
        l.hint()
        publishLadder(l)
        speakCue()
    }

    fun repeatCue() {
        if (_state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS) return
        speakCue()
    }

    /** A line already said, tapped again: what did they ask me? A dialogue he cannot re-hear is a trap. */
    fun replay(index: Int) {
        if (_state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS) return
        val item = lines.getOrNull(index)?.second ?: return
        cue { report(graph.speaker.speak(item)) }
    }

    private fun speakCue() {
        val l = ladder ?: return
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
     * Runs one sound that is not the dialogue itself. It replaces whatever the last such tap
     * started — `quiet()` as well as cancelling, because a cancel only lands at the next suspension
     * point and the old voice would be heard under the new one.
     */
    private fun cue(block: suspend () -> Unit) {
        cueJob?.cancel()
        graph.voice.quiet()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { block() }
        cueJob = job
        job.start()
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
                    val itemId = lines[_state.value.index].second.id
                    _state.update { it.copy(isRecording = false, selfRecordingPath = graph.files.relativize(rec.file)) }
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
                }
                .onFailure { e ->
                    graph.errors.record("scripts record stop", e)
                    _state.update { it.copy(isRecording = false, error = TOO_SHORT) }
                }
        } else {
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

    /** The model line, then his own take, back to back. */
    fun playComparison() {
        if (_state.value.phase != ScriptPhase.WAITING_FOR_DIMITRIS) return
        val path = _state.value.selfRecordingPath ?: return
        val item = lines.getOrNull(_state.value.index)?.second ?: return
        cue {
            report(graph.speaker.speak(item))
            report(graph.voice.play(graph.files.resolve(path)))
        }
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
        cueJob?.cancel()
        graph.voice.quiet()
        val i = _state.value.index
        val (line, item) = lines[i]
        // Read eagerly: the ladder and the clock belong to the turn being left behind.
        val level = l.level
        val outcome = l.outcomeFor(confirmed)
        val began = startedAt
        val position = line.position
        val save = recordingSave
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
                        detail = """{"scriptId":${jsonString(scriptId)},"position":$position}""",
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
        cueJob?.cancel()
        endJob?.cancel()
        graph.voice.quiet()
    }

    override fun onCleared() {
        loadJob?.cancel()
        stopEverything()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        const val MIC_DENIED = "Χωρίς άδεια μικροφώνου"
        const val TOO_SHORT = "Πολύ σύντομη ηχογράφηση"
        const val NO_RECORDING = "Δεν ξεκίνησε η ηχογράφηση"

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
