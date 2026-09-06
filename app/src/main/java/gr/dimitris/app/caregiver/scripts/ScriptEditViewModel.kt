package gr.dimitris.app.caregiver.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.audio.CaregiverTake
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * One turn as the caregiver is editing it. [recordingPath] is a take already on the device (stored
 * relative to the files dir); [newRecording] is one made in this editor and not yet written to the
 * database. Both are kept, because saving replaces the line's item and the old take has to be
 * re-attached to the new one or the dialogue would lose its voice on every edit.
 */
data class EditLine(
    val speaker: Speaker,
    val text: String,
    val recordingPath: String? = null,
    val recordingMs: Long = 0,
    val newRecording: Recorded? = null,
) {
    val hasVoice: Boolean get() = newRecording != null || recordingPath != null
}

data class ScriptEditState(
    val id: String? = null,
    val title: String = "",
    val lines: List<EditLine> = listOf(EditLine(Speaker.OTHER, ""), EditLine(Speaker.DIMITRIS, "")),
    /** Which line the microphone is open for, or null. One take at a time, like everywhere else. */
    val recordingIndex: Int? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    /**
     * Something has been changed since the last save. Only «Παίξ' το» reads it: playing the
     * dialogue means playing what is *written down*, so a form with an unsaved turn in it saves
     * first and a form without one does not touch the rows at all.
     */
    val dirty: Boolean = false,
    /** The dialogue she opened is gone (a restore, a sync). This form is not a new one: it is a dead end. */
    val notFound: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = id == null && !notFound

    /**
     * Whether «Παίξ' το» can be pressed.
     *
     * A dialogue she has only just typed is not excluded: the save it needs happens under the
     * button, without closing the editor. A missing title or a dialogue with no turn of his own is
     * refused by [ScriptEditViewModel.save] in Greek beside the button, as «Αποθήκευση» refuses it.
     * A dialogue that is not there any more has nothing to play, and the microphone being open is
     * not the moment to leave the screen.
     */
    val canTry: Boolean get() = !notFound && !loading && !saving && recordingIndex == null
}

/**
 * The caregiver's dialogue editor. It never writes anything until «Αποθήκευση»: a half-typed turn,
 * a reorder she thought better of, or a take she re-recorded twice all live here and nowhere else.
 *
 * Recording follows [gr.dimitris.app.caregiver.content.ItemEditViewModel]: through
 * [AppGraph.voice] (never the recorder directly, so the one-sound-at-a-time rule holds), the take
 * kept in state until save, and any take that never reached the database deleted in [onCleared].
 */
class ScriptEditViewModel(private val graph: AppGraph, private val scriptId: String?) : ViewModel() {
    private val _state = MutableStateFlow(ScriptEditState(loading = scriptId != null))
    val state: StateFlow<ScriptEditState> = _state.asStateFlow()

    init {
        if (scriptId != null) viewModelScope.launch {
            val loaded = try {
                graph.scripts.load(scriptId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                graph.errors.record("script load", e)
                null
            }
            if (loaded == null) {
                // Not a blank new dialogue: saving here would write a second copy of something she
                // thinks she is editing, so the form says what happened and refuses to save.
                _state.update { it.copy(loading = false, notFound = true, error = NOT_FOUND) }
                return@launch
            }
            val lines = loaded.lines.map { (line, item) ->
                // The model recording is what the dialogue plays for the other side, and what the
                // cue ladder plays at levels 3–4 for his own lines. Either way it belongs to the
                // line and has to survive a re-save.
                val recording = runCatching { graph.items.modelRecording(item) }.getOrNull()
                EditLine(line.speaker, item.text, recording?.path, recording?.durationMs ?: 0)
            }
            _state.value = ScriptEditState(id = loaded.script.id, title = loaded.script.title, lines = lines)
        }
    }

    fun setTitle(title: String) = _state.update { it.copy(title = title, dirty = true, error = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    /** A new turn takes the other speaker's side, which is what a dialogue almost always wants next. */
    fun addLine() = _state.update {
        if (it.lines.size >= MAX_LINES) return@update it.copy(error = TOO_MANY_LINES)
        val next = if (it.lines.lastOrNull()?.speaker == Speaker.OTHER) Speaker.DIMITRIS else Speaker.OTHER
        it.copy(lines = it.lines + EditLine(next, ""), dirty = true, error = null)
    }

    fun setLineText(index: Int, text: String) = edit(index) { it.copy(text = text) }

    fun toggleSpeaker(index: Int) = edit(index) {
        it.copy(speaker = if (it.speaker == Speaker.OTHER) Speaker.DIMITRIS else Speaker.OTHER)
    }

    fun moveUp(index: Int) = swap(index, index - 1)

    fun moveDown(index: Int) = swap(index, index + 1)

    /**
     * Takes the line out, and with it a take that was never saved — the file would otherwise sit in
     * the recordings dir with nothing pointing at it. A take that is already in the database stays:
     * it still belongs to the item the previous save made, and the history points at that.
     */
    fun removeLine(index: Int) {
        val s = _state.value
        if (index !in s.lines.indices) return
        if (s.recordingIndex == index && graph.voice.isRecording) graph.voice.cancelRecording()
        s.lines[index].newRecording?.file?.delete()
        _state.update {
            it.copy(
                lines = it.lines.filterIndexed { i, _ -> i != index },
                recordingIndex = shifted(it.recordingIndex, index),
                dirty = true,
                error = null,
            )
        }
    }

    /**
     * Start or stop the take for one line. Starting while another line is recording cannot happen
     * from the screen — every other button is disabled while the microphone is open — but if it
     * ever did, the running take is closed onto the line it was made for, not onto this one.
     */
    fun toggleRecording(index: Int) {
        val s = _state.value
        if (index !in s.lines.indices) return
        val running = s.recordingIndex
        if (running != null) {
            runCatching { graph.voice.stopRecording() }
                .onSuccess { rec -> keep(running, rec) }
                .onFailure { e ->
                    graph.errors.record("script recorder stop", e)
                    _state.update { it.copy(recordingIndex = null, error = TOO_SHORT) }
                }
            return
        }
        runCatching { graph.voice.startRecording() }
            .onSuccess { _state.update { it.copy(recordingIndex = index, error = null) } }
            .onFailure { e ->
                graph.errors.record("script recorder start", e)
                _state.update { it.copy(error = NO_RECORDING) }
            }
    }

    /**
     * The microphone was refused: say so instead of a button that does nothing. A take that somehow
     * *is* running is closed first — a state that says nothing is recording while the recorder is
     * open refuses every later play and every later take until the editor is left.
     */
    fun micDenied() {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.update { it.copy(recordingIndex = null, error = MIC_DENIED) }
    }

    fun playLine(index: Int) {
        val s = _state.value
        if (s.recordingIndex != null) return
        val line = s.lines.getOrNull(index) ?: return
        val file = line.newRecording?.file ?: line.recordingPath?.let { graph.files.resolve(it) } ?: return
        viewModelScope.launch {
            graph.voice.play(file).onFailure {
                graph.errors.record("script play line", it)
                _state.update { st -> st.copy(error = PLAY_FAILED) }
            }
        }
    }

    /**
     * Writes the whole dialogue in one go. Blank turns are dropped rather than saved as empty
     * bubbles, and a dialogue with no turn of his own is refused: the module only ever plans scripts
     * that give him something to say, so saving one would be saving something that never appears.
     */
    /**
     * «Παίξ' το»: run this very dialogue, now. The other half of Chris' report about words — a
     * conversation she has just written is the one she needs to hear, not whichever one the boxes
     * would have picked. Anything unsaved goes down first, because the module reads the rows and
     * not this form; a save that is refused stops here with its own line and nothing opens.
     *
     * A dialogue she has only just typed is saved here too — [save] does not close the editor, only
     * «Αποθήκευση» does — so she stays on the form, hears the conversation, and back lands on the
     * same form with every turn still in it. A later «Αποθήκευση» updates that same dialogue.
     */
    fun tryIt(onReady: (String) -> Unit) {
        val s = _state.value
        if (!s.canTry) return
        // A never-saved draft always goes through save, even an empty one: that is the path that
        // says «Γράψε πρώτα έναν τίτλο» instead of doing nothing at all.
        val id = s.id
        if (s.dirty || id == null) save(onReady) else onReady(id)
    }

    fun save(onSaved: (String) -> Unit) {
        if (_state.value.saving || _state.value.notFound) return
        // A take still running belongs to this dialogue: it is closed and kept, not thrown away.
        // If closing it failed — too short to be a voice — that is what she needs to read, so the
        // save stops here instead of silently writing the line without the take she just made.
        val open = _state.value.recordingIndex
        if (open != null) {
            toggleRecording(open)
            if (_state.value.error != null) return
        }
        val s = _state.value
        if (s.title.isBlank()) {
            _state.update { it.copy(error = NO_TITLE) }
            return
        }
        val lines = s.lines.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) {
            _state.update { it.copy(error = NO_LINES) }
            return
        }
        if (lines.none { it.speaker == Speaker.DIMITRIS }) {
            _state.update { it.copy(error = NO_TURN_FOR_HIM) }
            return
        }
        // A dialogue is one sitting's work: the session budget leaves it whole, so the length has to
        // be held here or a twenty-turn script would be the whole hour by itself.
        if (lines.size > MAX_LINES) {
            _state.update { it.copy(error = TOO_MANY_LINES) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = graph.scripts.save(
                    s.id, s.title,
                    lines.map { LineDraft(it.speaker, it.text, fileOf(it), it.newRecording?.durationMs ?: it.recordingMs) },
                )
                // Every take is now a row in the database, so none of them may be deleted on the way
                // out. The saved list replaces the edited one: what was blank was never written.
                _state.value = ScriptEditState(
                    id = saved.id, title = saved.title,
                    lines = lines.map { line ->
                        line.copy(
                            recordingPath = line.newRecording?.let { graph.files.relativize(it.file) } ?: line.recordingPath,
                            recordingMs = line.newRecording?.durationMs ?: line.recordingMs,
                            newRecording = null,
                        )
                    },
                )
                graph.feedback.success()
                onSaved(saved.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                graph.errors.record("script save", e)
                _state.update { it.copy(saving = false, error = SAVE_FAILED) }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _state.value.id ?: return onDeleted()
        viewModelScope.launch {
            try {
                graph.scripts.delete(id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                graph.errors.record("script delete", e)
                _state.update { it.copy(error = DELETE_FAILED) }
                return@launch
            }
            onDeleted()
        }
    }

    /** The file that becomes this line's voice: the fresh take, else the one it already had. */
    private fun fileOf(line: EditLine): File? =
        line.newRecording?.file ?: line.recordingPath?.let { graph.files.resolve(it) }

    /**
     * The finished take, onto the line it was made for. The two file deletions happen out here and
     * not inside the [MutableStateFlow.update] block: that block is retried under contention, and a
     * retried delete would be a second delete of a file the first pass had already removed.
     *
     * A take with nothing in it is not kept at all. On the other person's line it *is* the voice he
     * hears, and on his own it is the model the cue ladder plays: either way a silent one is a
     * dialogue that plays silence at him. She is told and the line keeps whatever it had.
     */
    private fun keep(index: Int, rec: Recorded) {
        val take = CaregiverTake.keptOrDiscarded(rec)
        if (take == null) {
            _state.update { it.copy(recordingIndex = null, error = Recorded.SILENT_TAKE_CAREGIVER) }
            return
        }
        val line = _state.value.lines.getOrNull(index)
        if (line == null) {
            // The line was removed while its take was running: the file has nowhere to go.
            take.file.delete()
            _state.update { it.copy(recordingIndex = null) }
            return
        }
        line.newRecording?.file?.delete()
        _state.update { s ->
            val current = s.lines.getOrNull(index) ?: return@update s.copy(recordingIndex = null)
            s.copy(
                lines = s.lines.replacing(index, current.copy(newRecording = take)),
                recordingIndex = null, dirty = true, error = null,
            )
        }
    }

    private fun edit(index: Int, change: (EditLine) -> EditLine) = _state.update { s ->
        val line = s.lines.getOrNull(index) ?: return@update s
        s.copy(lines = s.lines.replacing(index, change(line)), dirty = true, error = null)
    }

    private fun swap(from: Int, to: Int) = _state.update { s ->
        if (from !in s.lines.indices || to !in s.lines.indices) return@update s
        val lines = s.lines.toMutableList()
        lines[from] = s.lines[to]
        lines[to] = s.lines[from]
        // The microphone follows the line it is recording, not the position it started at.
        val recording = when (s.recordingIndex) {
            from -> to
            to -> from
            else -> s.recordingIndex
        }
        s.copy(lines = lines, recordingIndex = recording, dirty = true, error = null)
    }

    private fun List<EditLine>.replacing(index: Int, line: EditLine): List<EditLine> =
        mapIndexed { i, old -> if (i == index) line else old }

    /** Where the open microphone's line ends up once the line at [removed] is gone. */
    private fun shifted(recording: Int?, removed: Int): Int? = when {
        recording == null || recording == removed -> null
        recording > removed -> recording - 1
        else -> recording
    }

    override fun onCleared() {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.value.lines.forEach { it.newRecording?.file?.delete() }
    }

    companion object {
        /**
         * The longest dialogue worth one sitting. The session runs a dialogue whole, so this is the
         * only place its length is held: twelve turns is about four of his, said and cued.
         */
        const val MAX_LINES = 12

        const val NOT_FOUND = "Ο διάλογος δεν βρέθηκε."
        const val TOO_MANY_LINES = "Έως 12 γραμμές"
        const val NO_TITLE = "Γράψε πρώτα έναν τίτλο."
        const val NO_LINES = "Γράψε τουλάχιστον μία γραμμή."
        const val NO_TURN_FOR_HIM = "Χρειάζεται τουλάχιστον μία γραμμή του Δημήτρη."
        const val SAVE_FAILED = "Δεν αποθηκεύτηκε. Δοκίμασε ξανά."
        const val DELETE_FAILED = "Δεν διαγράφηκε. Δοκίμασε ξανά."
        const val TOO_SHORT = "Πολύ σύντομη ηχογράφηση, δοκίμασε ξανά"
        const val NO_RECORDING = "Δεν ξεκίνησε η ηχογράφηση"
        const val MIC_DENIED = "Χωρίς άδεια μικροφώνου"
        const val PLAY_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."
    }
}
