package gr.dimitris.app.modules.singsay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.data.now
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SingSayState(
    val index: Int = 0,
    val total: Int,
    val item: Item,
    val notes: List<Note>,
    val stage: Int = SingStage.LISTEN,
    val repetition: Int = 0,
    /** Index of the syllable currently lit, or -1. */
    val lit: Int = -1,
    val playing: Boolean = false,
    /** True until this phrase's sung model has been looked up: nothing may be finished before that. */
    val loading: Boolean = true,
    val hasSungModel: Boolean = false,
    val isRecording: Boolean = false,
    val selfRecordingPath: String? = null,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * One phrase at a time through the five stages of [SingStage]. The melody is the same two notes he
 * hears, taps and finally drops; what changes from stage to stage is only how much of it still
 * sounds under him.
 */
class SingSayViewModel(private val graph: AppGraph, private val items: List<Item>, private val sessionId: String?) : ViewModel() {
    private val _state = MutableStateFlow(SingSayState(total = items.size, item = items.first(), notes = Melody.forPhrase(items.first().text)))
    val state: StateFlow<SingSayState> = _state.asStateFlow()

    private var startedAt = now()

    /** Where the caregiver's sung take for the current phrase lives, read once when it loads. */
    private var sungModelPath: String? = null

    /**
     * The write of his own take, as a value: the attempt awaits the id instead of reading a field
     * that the next phrase has already cleared.
     */
    private var recordingSave: Deferred<String?>? = null

    /**
     * The attempt + schedule write of the phrase just finished. It runs on the app scope, so the end
     * of the module and the back arrow both join it first: the session counts rows, and a row still
     * in flight is not one.
     */
    private var lastWrite: Job? = null

    /** True from the moment a phrase is finished or skipped until the next one is ready. */
    private var finishing = false

    /** Whatever is being played right now: the model, the melody, or a tapped note. */
    private var playJob: Job? = null

    /**
     * Bumped by every new playback. A cancelled job's `finally` can land after the next one has
     * already started, and it must not clear the flags of a playback that is still going.
     */
    private var playToken = 0

    /** This phrase's sung-model lookup. Cancelled the moment another phrase loads. */
    private var loadJob: Job? = null

    init { load(0) }

    private fun load(i: Int) {
        val item = items[i]
        // The phrase being left must not finish loading: its sung model would be played under the
        // syllables of this one, its "no sung voice" line shown against this one, and its clock
        // started here — the Room executor is a pool, so the two lookups can land out of order.
        loadJob?.cancel()
        recordingSave = null
        sungModelPath = null
        // playing from the first frame: the model is about to start, and a stage button tapped in
        // the gap would belong to the phrase he has just left.
        _state.value = SingSayState(index = i, total = items.size, item = item, notes = Melody.forPhrase(item.text), playing = true)
        loadJob = viewModelScope.launch {
            val sung = runCatching { graph.items.sungRecording(item) }
                .onFailure { graph.errors.record("singsay sung model", it) }.getOrNull()
            sungModelPath = sung?.path
            _state.update { it.copy(hasSungModel = sung != null, loading = false) }
            // Not before the query: its wait is not his time on the phrase.
            startedAt = now()
            finishing = false
            playModel()
        }
    }

    /** Stage 1 and «Άκου»: the caregiver's sung model if there is one, else TTS, then the melody. */
    fun playModel() {
        val token = claimPlayback()
        playJob = viewModelScope.launch {
            _state.update { it.copy(playing = true, lit = -1) }
            try {
                sayModel()
                playMelody(gain = 1f)
            } finally {
                releasePlayback(token)
            }
        }
    }

    /**
     * Silences whatever is sounding and claims the play job for the caller. Cancelling the job is
     * not enough on its own: the melody deliberately sits outside [gr.dimitris.app.core.audio.Voice],
     * so `Voice.quiet()` does not touch it, and [Job.cancel] only lands at the next suspension
     * point — a voice started meanwhile would be heard over a tone that is still sounding.
     */
    private fun claimPlayback(): Int {
        playJob?.cancel()
        graph.synth.stop()
        return ++playToken
    }

    /** Only the newest playback owns the flags; an older job's `finally` can land after it started. */
    private fun releasePlayback(token: Int) {
        if (playToken == token) _state.update { it.copy(playing = false, lit = -1) }
    }

    /**
     * The model voice: the caregiver singing the phrase when she has recorded it, else the ordinary
     * spoken model ([gr.dimitris.app.core.speech.ItemSpeaker] — her spoken take, or Greek TTS). The
     * melody that follows carries the tune either way, which is what makes the fallback work at all.
     */
    private suspend fun sayModel() {
        val file = sungModelPath?.let { graph.files.resolve(it) }
        if (file != null && file.exists()) report(graph.voice.play(file), SPEECH_FAILED, "singsay play sung")
        else report(graph.speaker.speak(_state.value.item), SPEECH_FAILED, "singsay speak")
    }

    private suspend fun playMelody(gain: Float) = report(
        graph.synth.play(_state.value.notes.map { it.pitch }, gain = gain) { i -> _state.update { it.copy(lit = i) } },
        SYNTH_FAILED, "singsay melody",
    )

    /**
     * Records what one sound did. Silence is the one failure Dimitris cannot diagnose himself, so it
     * is said on the screen, and cleared by the next sound of the same kind that does come out.
     */
    private fun report(result: Result<*>, message: String, where: String) = result.fold(
        onSuccess = { _state.update { if (it.error == message) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record(where, e); _state.update { it.copy(error = message) } },
    )

    /** The tap pad: one tap lights the next syllable and, while the stage still has backing, sounds it. */
    fun tap() {
        val s = _state.value
        if (s.playing || s.notes.isEmpty()) return
        val next = (s.lit + 1) % s.notes.size
        _state.update { it.copy(lit = next) }
        val gain = SingStage.gainFor(s.stage, s.repetition)
        if (gain <= 0f) return
        // The tapped note belongs to the same job as every other playback: a second tap replaces the
        // first note instead of racing it for the one track, and finish/leave silence it too.
        claimPlayback()
        playJob = viewModelScope.launch {
            report(graph.synth.play(listOf(s.notes[next].pitch), noteMs = TAP_NOTE_MS, gapMs = 0, gain = gain), SYNTH_FAILED, "singsay tap")
        }
    }

    /** «Το έκανα»: this repetition is done. The fading stage needs three; the others move on at once. */
    fun completeRepetition() {
        val s = _state.value
        if (finishing || s.done) return
        graph.feedback.success()
        when {
            s.stage == SingStage.FADING && s.repetition + 1 < SingStage.FADING_REPS ->
                _state.update { it.copy(repetition = it.repetition + 1, lit = -1) }
            s.stage < SingStage.SPEAK -> _state.update { it.copy(stage = it.stage + 1, repetition = 0, lit = -1) }
            else -> finish(skipped = false)
        }
    }

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
                            graph.errors.record("singsay save recording", e)
                            null
                        }
                    }
                }
                .onFailure { e -> graph.errors.record("singsay record stop", e); _state.update { it.copy(isRecording = false, error = TOO_SHORT) } }
        } else {
            // The melody is not under Voice, so it has to be silenced here: the microphone would
            // otherwise record the phone singing over him.
            claimPlayback()
            runCatching { graph.voice.startRecording() }
                .onSuccess { _state.update { it.copy(isRecording = true, playing = false, error = null) } }
                .onFailure { e -> graph.errors.record("singsay record start", e); _state.update { it.copy(error = NO_RECORDING) } }
        }
    }

    /** The microphone was refused: say so instead of a button that does nothing. */
    fun micDenied() = _state.update { it.copy(error = MIC_DENIED) }

    /**
     * The model voice, then his own take, back to back in one job. The model playback is written out
     * here rather than calling [playModel]: that would cancel the very job it was started from, and
     * the comparison would stop before his own voice was ever reached.
     */
    fun playComparison() {
        val path = _state.value.selfRecordingPath ?: return
        val token = claimPlayback()
        playJob = viewModelScope.launch {
            _state.update { it.copy(playing = true, lit = -1) }
            try {
                sayModel()
                report(graph.voice.play(graph.files.resolve(path)), SPEECH_FAILED, "singsay play self")
            } finally {
                releasePlayback(token)
            }
        }
    }

    fun skip() {
        if (finishing || _state.value.done) return
        graph.feedback.nudge()
        finish(skipped = true)
    }

    private fun finish(skipped: Boolean) {
        if (finishing) return
        finishing = true
        claimPlayback()
        // A take still running belongs to this phrase: it is stopped and kept, not thrown away.
        if (_state.value.isRecording) toggleRecording()
        val s = _state.value
        val stageReached = s.stage
        val cue = SingStage.cueLevelFor(stageReached)
        val outcome = when { skipped -> Outcome.SKIPPED; cue <= 2 -> Outcome.CORRECT; else -> Outcome.ASSISTED }
        // Read eagerly: the clock and the take belong to the phrase being left behind.
        val began = startedAt
        val save = recordingSave
        // Chained, because the app scope runs on a pool with no ordering: whoever joins the last
        // write must be joining every write, or a row can land after the session has counted.
        val prev = lastWrite
        // The app scope, not this screen's: pressing back must not lose the phrase he just sang.
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                // Inside the guard, not before it: the app scope has no exception handler, so a
                // throw from the await would be an uncaught crash rather than a logged failure.
                val recordingId = save?.await()
                graph.db.attempts().insert(
                    Attempt(
                        itemId = s.item.id, module = ModuleId.SINGSAY, sessionId = sessionId, startedAt = began,
                        durationMs = now() - began, outcome = outcome, cueLevel = cue, selfRecordingId = recordingId,
                        detail = """{"stage":$stageReached}""",
                    )
                )
                graph.scheduler.record(s.item.id, ModuleId.SINGSAY, outcome, cue)
            }.onFailure { graph.errors.record("singsay finish", it) }
        }
        val i = s.index + 1
        if (i >= items.size) {
            // The session counts attempt rows as soon as it is told the module is done, so the last
            // phrase's write has to be in the database before "done" ever reaches the screen.
            val write = lastWrite
            viewModelScope.launch { write?.join(); _state.update { it.copy(done = true) } }
        } else {
            load(i)
        }
    }

    /**
     * He pressed back. The melody, the speaker and the microphone all stop here, and [then] waits
     * for the last phrase's write: the session counts rows the moment it is told, so leaving before
     * the row lands would lose the phrase he had just finished.
     */
    fun leave(then: () -> Unit) {
        loadJob?.cancel()
        claimPlayback()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        graph.voice.quiet()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    override fun onCleared() {
        loadJob?.cancel()
        claimPlayback()
        if (graph.voice.isRecording) graph.voice.cancelRecording()
    }

    companion object {
        /** Said on the screen when a spoken model made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /** Said when the melody itself could not be played: without it there is nothing to sing to. */
        const val SYNTH_FAILED = "Δεν παίζει ο ήχος."
        const val MIC_DENIED = "Χωρίς άδεια μικροφώνου"
        const val TOO_SHORT = "Πολύ σύντομη ηχογράφηση"
        const val NO_RECORDING = "Δεν ξεκίνησε η ηχογράφηση"

        /** A tapped note marks the beat, it does not hold it: shorter than a sung one. */
        const val TAP_NOTE_MS = 350
    }
}
