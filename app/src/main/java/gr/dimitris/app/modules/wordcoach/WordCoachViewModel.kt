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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private var selfRecordingId: String? = null

    /** The write of the last take. The attempt waits for it, so it can point at the recording. */
    private var recordingSave: Job? = null

    private val _state = MutableStateFlow(WordCoachState(total = items.size, item = items.first()))
    val state: StateFlow<WordCoachState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(sttOn = graph.settings.sttEnabled.first() && graph.stt.isAvailable) } }
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

    fun repeatCue() = speakCue()

    private fun speakCue() {
        val s = _state.value
        viewModelScope.launch {
            when (s.level) {
                1, 2 -> ladder.cueText()?.let { heard(graph.speaker.speakText(it)) }
                3, 4 -> heard(graph.speaker.speak(s.item))
                else -> Unit
            }
        }
    }

    /**
     * Records the outcome of one speak or play attempt. Silence is the one failure Dimitris cannot
     * diagnose himself, so it is said on the screen and cleared by the next sound that comes out.
     */
    private fun heard(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("wordcoach speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    fun toggleRecording() {
        if (_state.value.isRecording) {
            runCatching { graph.voice.stopRecording() }
                .onSuccess { rec ->
                    _state.update { it.copy(isRecording = false, selfRecordingPath = graph.files.relativize(rec.file)) }
                    recordingSave = viewModelScope.launch {
                        runCatching { graph.items.addRecording(_state.value.item.id, rec.file, rec.durationMs, Who.DIMITRIS).id }
                            .fold({ selfRecordingId = it }, { graph.errors.record("wordcoach save recording", it) })
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
            heard(graph.speaker.speak(_state.value.item))
            heard(graph.voice.play(graph.files.resolve(path)))
        }
    }

    /** Optional soft recognition: encouragement only, never a gate. */
    fun listen() {
        if (!_state.value.sttOn || _state.value.listening) return
        _state.update { it.copy(listening = true, heard = null, heardMatched = false) }
        viewModelScope.launch {
            val result = graph.stt.listen(5)
            val text = result.getOrNull()?.text
            val matched = text != null && SpeechMatch.matches(text, _state.value.item.text)
            if (matched) graph.feedback.success()
            _state.update { it.copy(listening = false, heard = text, heardMatched = matched) }
        }
    }

    fun confirm() = finish(confirmed = true)
    fun skip() = finish(confirmed = false)

    private fun finish(confirmed: Boolean) {
        if (_state.value.isRecording) toggleRecording()
        val s = _state.value
        val outcome = ladder.outcomeFor(confirmed)
        // Read eagerly: the ladder is replaced the moment the next word starts.
        val level = ladder.level
        val save = recordingSave
        val detail = s.heard?.let { """{"heard":${jsonString(it)},"matched":${s.heardMatched}}""" } ?: "{}"
        viewModelScope.launch {
            // The recording row carries the id the attempt points at, so let its write land first.
            save?.join()
            val recordingId = selfRecordingId
            runCatching {
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
        // A take still running belongs to the word being left behind.
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        val i = _state.value.index + 1
        if (i >= items.size) { _state.update { it.copy(done = true) }; return }
        ladder = CueLadder(items[i])
        startedAt = now()
        selfRecordingId = null
        recordingSave = null
        _state.value = WordCoachState(index = i, total = items.size, item = items[i], sttOn = _state.value.sttOn)
    }

    /** The user pressed back. Whatever the microphone or the speaker was doing stops here. */
    fun leave() {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        graph.voice.quiet()
    }

    override fun onCleared() {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."
        const val MIC_DENIED = "Χωρίς άδεια μικροφώνου"
    }
}
