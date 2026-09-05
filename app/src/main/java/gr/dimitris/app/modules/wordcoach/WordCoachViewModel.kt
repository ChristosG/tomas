package gr.dimitris.app.modules.wordcoach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.data.now
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
    val selfRecordingPath: String? = null,
    val sttOn: Boolean = false,
    val listening: Boolean = false,
    val heard: String? = null,
    val heardMatched: Boolean = false,
    /** True after "Το είπα!": success mark shown, only "Επόμενο" remains. */
    val confirmed: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
)

class WordCoachViewModel(private val graph: AppGraph, private val items: List<Item>, private val sessionId: String?) : ViewModel() {
    private var ladder = CueLadder(items.first())
    private var startedAt = now()

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

    private val _state = MutableStateFlow(WordCoachState(total = items.size, item = items.first()))
    val state: StateFlow<WordCoachState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // isAvailable asks the package manager across a binder: not on the thread drawing the word.
            val on = graph.settings.sttEnabled.first() && withContext(Dispatchers.Default) { graph.stt.isAvailable }
            _state.update { it.copy(sttOn = on) }
        }
    }

    private fun publishLadder() = _state.update {
        it.copy(level = ladder.level, cueText = ladder.cueText(), showsWord = ladder.showsWord, canHint = ladder.canHint)
    }

    /** One more hint. Levels 1–2 are spoken by TTS; 3–4 use the model voice. */
    fun hint() {
        if (!ladder.canHint) return
        ladder.hint()
        publishLadder()
        speakCue()
    }

    /**
     * The picture is a listen button too, and it has no disabled state to show: while a take is
     * running it simply says nothing, instead of answering with «Δεν ακούγεται η φωνή» — the
     * refusal the one-sound-at-a-time rule owes the microphone, not a fault he can do anything about.
     */
    fun repeatCue() { if (!_state.value.isRecording) speakCue() }

    private fun speakCue() {
        val s = _state.value
        viewModelScope.launch {
            when (s.level) {
                1, 2 -> ladder.cueText()?.let { report(graph.speaker.speakText(it)) }
                3, 4 -> report(graph.speaker.speak(s.item))
                else -> Unit
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
                    _state.update { it.copy(isRecording = false, selfRecordingPath = graph.files.relativize(rec.file)) }
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
            runCatching { graph.voice.startRecording() }
                .onSuccess { _state.update { it.copy(isRecording = true, error = null) } }
                .onFailure { e -> graph.errors.record("wordcoach record start", e); _state.update { it.copy(error = "Δεν ξεκίνησε η ηχογράφηση") } }
        }
    }

    /** The microphone was refused: say so instead of a button that does nothing. */
    fun micDenied() = _state.update { it.copy(error = MIC_DENIED) }

    /** Model voice, then his own recording. */
    fun playComparison() {
        val path = _state.value.selfRecordingPath ?: return
        viewModelScope.launch {
            report(graph.speaker.speak(_state.value.item))
            report(graph.voice.play(graph.files.resolve(path)))
        }
    }

    /**
     * Optional soft recognition: encouragement only, never a gate. A failed recognition is the
     * phone's problem, not his, and it is said plainly instead of leaving "Ακούω..." hanging.
     */
    fun listen() {
        if (!_state.value.sttOn || _state.value.listening) return
        _state.update { it.copy(listening = true, heard = null, heardMatched = false, error = null) }
        viewModelScope.launch {
            graph.stt.listen().fold(
                onSuccess = { t ->
                    val matched = SpeechMatch.matches(t.text, _state.value.item.text)
                    if (matched) graph.feedback.success()
                    _state.update { it.copy(listening = false, heard = t.text, heardMatched = matched) }
                },
                onFailure = { e ->
                    graph.errors.record("wordcoach listen", e)
                    _state.update { it.copy(listening = false, heard = null, heardMatched = false, error = HEARD_NOTHING) }
                },
            )
        }
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
        val s = _state.value
        val outcome = ladder.outcomeFor(confirmed)
        // Read eagerly: the ladder is replaced the moment the next word starts.
        val level = ladder.level
        val save = recordingSave
        val detail = s.heard?.let { """{"heard":${jsonString(it)},"matched":${s.heardMatched}}""" } ?: "{}"
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
        // A take still running belongs to the word being left behind.
        if (graph.voice.isRecording) graph.voice.cancelRecording()
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
        recordingSave = null
        _state.value = WordCoachState(index = i, total = items.size, item = items[i], sttOn = _state.value.sttOn)
    }

    /**
     * The user pressed back. Whatever the microphone or the speaker was doing stops here, and [then]
     * waits for the last word's write: the session counts rows the moment it is told, so leaving
     * before the row lands would lose the word he had just said.
     */
    fun leave(then: () -> Unit) {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        graph.voice.quiet()
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
        graph.voice.quiet()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.update { it.copy(isRecording = false, listening = false) }
    }

    override fun onCleared() {
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
