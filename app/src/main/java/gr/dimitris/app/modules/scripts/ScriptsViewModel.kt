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
import gr.dimitris.app.core.data.now
import gr.dimitris.app.modules.wordcoach.CueLadder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** True from a finished turn until the next one has started: one attempt, one advance. */
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
        finishing = false
        val (line, item) = lines[i]
        if (line.speaker == Speaker.OTHER) {
            ladder = null
            _state.update {
                it.copy(index = i, phase = ScriptPhase.OTHER_SPEAKING, level = 0, cueText = null, showsWord = false, canHint = false)
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
                )
            }
        }
    }

    /**
     * The other person's turn. The wait *is* the utterance — no timer decides when they have
     * finished — and a voice that failed still moves the conversation on: a phone with no Greek
     * voice must not be able to strand him on somebody else's line.
     */
    private fun speakOther(i: Int) {
        speakJob = viewModelScope.launch {
            report(graph.speaker.speak(lines[i].second))
            advanceFrom(i)
        }
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
        cueJob = viewModelScope.launch { block() }
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
        cueJob?.cancel()
        graph.voice.quiet()
        val i = _state.value.index
        val (line, item) = lines[i]
        // Read eagerly: the ladder and the clock belong to the turn being left behind.
        val level = l.level
        val outcome = l.outcomeFor(confirmed)
        val began = startedAt
        val position = line.position
        worstCue = maxOf(worstCue, level)
        if (!confirmed) skipped = true
        val prev = lastWrite
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                graph.db.attempts().insert(
                    Attempt(
                        itemId = item.id, module = ModuleId.SCRIPTS, sessionId = sessionId, startedAt = began,
                        durationMs = now() - began, outcome = outcome, cueLevel = level,
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
        _state.update { it.copy(phase = ScriptPhase.FINISHED) }
        if (!recorded) {
            recorded = true
            val outcome = when {
                skipped -> Outcome.SKIPPED
                worstCue >= ASSISTED_FROM -> Outcome.ASSISTED
                else -> Outcome.CORRECT
            }
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
        endJob = viewModelScope.launch {
            graph.feedback.success()
            report(graph.speaker.speakText(THE_END))
            write?.join()
            _state.update { it.copy(done = true) }
        }
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
        stopEverything()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
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

        /** Spoken at the end of the dialogue, before the module hands back. */
        const val THE_END = "Μπράβο! Τέλος διαλόγου."

        /** From cue level 3 the line was said to him: real work, done with help still under it. */
        const val ASSISTED_FROM = 3
    }
}
