package gr.dimitris.app.caregiver.content

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.audio.CaregiverTake
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.RecordingStyle
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.Syllabifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ItemEditState(
    val id: String? = null,
    val text: String = "",
    val kind: ItemKind = ItemKind.WORD,
    val category: Category = Category.CUSTOM,
    /** Caregiver-pinned to the talk board favourites. */
    val pinned: Boolean = false,
    /** Real price as the caregiver types it, "3,50". Empty means the item has no price. */
    val priceText: String = "",
    val imagePath: String? = null,
    val firstSyllableOverride: String = "",
    val autoSyllable: String? = null,
    /** Existing model voice, if any. */
    val savedRecordingPath: String? = null,
    /** Fresh recording made in this editor, written to the db on save. */
    val newRecording: Recorded? = null,
    val isRecording: Boolean = false,
    /** Existing sung take, if any: the caregiver singing the phrase for "Τραγούδα και πες το". */
    val savedSungPath: String? = null,
    /** Fresh sung take made in this editor, written to the db on save. */
    val newSungRecording: Recorded? = null,
    val isRecordingSung: Boolean = false,
    val saving: Boolean = false,
    /**
     * Something has been changed since the last save (or since the item was loaded). Only
     * «Δοκίμασέ το» reads it: running the word means running what is *written down*, so a form with
     * an unsaved change in it saves first and a form without one does not touch the row at all.
     */
    val dirty: Boolean = false,
    val error: String? = null,
) {
    val recordingPath: String? get() = newRecording?.file?.absolutePath ?: savedRecordingPath
    val sungPath: String? get() = newSungRecording?.file?.absolutePath ?: savedSungPath
    val isNew: Boolean get() = id == null

    /**
     * Whether «Δοκίμασέ το» can be pressed.
     *
     * A word that has never been saved is *not* excluded: she types it and runs it, and the save
     * that has to happen first happens under the button without closing the editor. Blank text and
     * a half-typed price are refused by [ItemEditViewModel.save] in Greek, beside the button, the
     * same way «Αποθήκευση» refuses them — a dead button explains nothing.
     *
     * An open microphone does stop it, exactly as it stops the dialogue editor's «Παίξ' το».
     * Leaving the screen with the recorder running would have the practice screen cancel the take
     * on its way out while this form still believed it was recording: «Στοπ» would then be showing
     * for a take that no longer exists, and pressing it would fail.
     */
    val canTry: Boolean get() = !saving && !isRecording && !isRecordingSung
}

class ItemEditViewModel(private val graph: AppGraph, private val itemId: String?) : ViewModel() {
    private val _state = MutableStateFlow(ItemEditState())
    val state: StateFlow<ItemEditState> = _state.asStateFlow()

    init {
        if (itemId != null) viewModelScope.launch {
            val item = graph.items.get(itemId) ?: return@launch
            val model = graph.items.modelRecording(item)
            val sung = graph.items.sungRecording(item)
            _state.value = ItemEditState(
                id = item.id, text = item.text, kind = item.kind, category = item.category, pinned = item.pinned,
                priceText = item.priceCents?.let { Euro.format(it).removeSuffix(" €") } ?: "", imagePath = item.imagePath,
                firstSyllableOverride = item.firstSyllableOverride ?: "", autoSyllable = Syllabifier.firstSyllable(item.text),
                savedRecordingPath = model?.path, savedSungPath = sung?.path,
            )
        }
    }

    fun setText(text: String) = _state.update {
        it.copy(text = text, autoSyllable = Syllabifier.firstSyllable(text.trim()), dirty = true, error = null)
    }
    /**
     * The sung row — its «Στοπ» included — is drawn only for a phrase, so switching to «Λέξη» while
     * a sung take runs would take away the only control that could stop it and leave the spoken
     * button disabled behind it. The take is closed here first, and kept: it is hers, and switching
     * back to «Φράση» finds it where she left it.
     */
    fun setKind(kind: ItemKind) {
        if (kind != ItemKind.PHRASE && _state.value.isRecordingSung) toggleSungRecording()
        _state.update { it.copy(kind = kind, dirty = true) }
    }
    fun setCategory(category: Category) = _state.update { it.copy(category = category, dirty = true) }
    fun setPinned(on: Boolean) = _state.update { it.copy(pinned = on, dirty = true) }
    fun setPriceText(t: String) = _state.update { it.copy(priceText = t, dirty = true, error = null) }
    fun setOverride(value: String) = _state.update { it.copy(firstSyllableOverride = value, dirty = true) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun photoPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.images.import(graph.app.contentResolver, uri) }
                .onSuccess { file -> _state.update { it.copy(imagePath = graph.files.relativize(file), dirty = true) } }
                .onFailure { e -> graph.errors.record("photo import", e); _state.update { it.copy(error = "Δεν άνοιξε η φωτογραφία") } }
        }
    }

    fun photoTaken(file: File, success: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!success) { file.delete(); return@launch }
            runCatching { graph.images.shrinkInPlace(file) }.onFailure { e ->
                graph.errors.record("photo shrink", e)
                _state.update { it.copy(error = "Η φωτογραφία δεν επεξεργάστηκε, αλλά κρατήθηκε.") }
            }
            _state.update { it.copy(imagePath = graph.files.relativize(file), dirty = true) }
        }
    }

    fun toggleRecording() = toggle(
        recording = _state.value.isRecording,
        setRecording = { on -> _state.update { it.copy(isRecording = on) } },
        // The error goes with the take that earned it: a good one replaces a refusal she has read.
        keep = { rec -> _state.value.newRecording?.file?.delete(); _state.update { it.copy(newRecording = rec, dirty = true, error = null) } },
    )

    /** The sung take: the same microphone, kept in its own field so neither recording overwrites the other. */
    fun toggleSungRecording() = toggle(
        recording = _state.value.isRecordingSung,
        setRecording = { on -> _state.update { it.copy(isRecordingSung = on) } },
        keep = { rec -> _state.value.newSungRecording?.file?.delete(); _state.update { it.copy(newSungRecording = rec, dirty = true, error = null) } },
    )

    /**
     * One start/stop dance for both takes. Only where the finished recording is kept differs, so the
     * refusal wording, the error log and the "not recording any more" state live here once.
     *
     * A take with nothing in it never reaches [keep]: hers is the model voice, and one that plays
     * silence is an «Άκου» that answers him with nothing. It is deleted and she is asked again —
     * the same rule his own takes have had since [Recorded.isSilent], said to her instead.
     */
    private fun toggle(recording: Boolean, setRecording: (Boolean) -> Unit, keep: (Recorded) -> Unit) {
        if (recording) {
            runCatching { graph.voice.stopRecording() }
                .onSuccess { rec ->
                    setRecording(false)
                    val kept = CaregiverTake.keptOrDiscarded(rec)
                    if (kept == null) _state.update { it.copy(error = Recorded.SILENT_TAKE_CAREGIVER) } else keep(kept)
                }
                .onFailure { e ->
                    graph.errors.record("recorder stop", e)
                    setRecording(false)
                    _state.update { it.copy(error = "Πολύ σύντομη ηχογράφηση, δοκίμασε ξανά") }
                }
        } else {
            runCatching { graph.voice.startRecording() }
                .onSuccess { setRecording(true); _state.update { it.copy(error = null) } }
                .onFailure { e -> graph.errors.record("recorder start", e); _state.update { it.copy(error = "Δεν ξεκίνησε η ηχογράφηση") } }
        }
    }

    fun playRecording() = play(_state.value.recordingPath)
    fun playSung() = play(_state.value.sungPath)

    private fun play(path: String?) {
        if (path == null) return
        viewModelScope.launch { graph.voice.play(graph.files.resolve(path)).onFailure { graph.errors.record("play recording", it) } }
    }

    fun speakWithTts() {
        val text = _state.value.text.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            graph.voice.speak(text, graph.settings.speechRate.first()).onFailure { graph.errors.record("tts", it) }
        }
    }

    fun micDenied() = _state.update { it.copy(isRecording = false, error = "Χωρίς άδεια μικροφώνου") }
    fun cameraDenied() = _state.update { it.copy(error = "Χωρίς άδεια κάμερας") }

    /**
     * «Δοκίμασέ το»: run this very word through the word coach, now.
     *
     * Chris' report is what this is for — he would add a word and then have "to use the app for
     * hours until it randomly appears". Anything unsaved goes down first, because the word coach
     * reads the row and not this form; with nothing to save it goes straight through and the row is
     * left exactly as it is. A save that is refused (no text, a half-typed price) stops here with
     * its own red line, and nothing opens.
     *
     * A word she has only just typed is saved here too — [save] does not close the editor, only
     * «Αποθήκευση» does — so she stays on the form she was filling in, the word runs, and back
     * lands on the same form with everything she had typed still in it. A second «Αποθήκευση»
     * afterwards updates that same row rather than writing a second copy: the id is in state now.
     */
    fun tryIt(onReady: (String) -> Unit) {
        val s = _state.value
        if (!s.canTry) return
        // A never-saved draft always goes through save, even when nothing has been typed into it:
        // that is the path that says «Γράψε τη λέξη πρώτα» instead of doing nothing at all.
        val id = s.id
        if (s.dirty || id == null) save(onReady) else onReady(id)
    }

    fun save(onSaved: (String) -> Unit) {
        val s = _state.value
        if (s.text.isBlank()) { _state.update { it.copy(error = "Γράψε τη λέξη πρώτα") }; return }
        // A half-typed price would silently become no price at all, so it stops the save instead.
        if (s.priceText.isNotBlank() && Euro.parse(s.priceText) == null) {
            _state.update { it.copy(error = "Η τιμή θέλει μορφή 3,50") }
            return
        }
        // Closing an open take can fail — too short, or nothing said into it at all — and that is
        // the one thing she has to read. Saving through it would close the editor over the message
        // and write the word with the voice she thought she had just given it missing.
        if (s.isRecording) { toggleRecording(); if (_state.value.error != null) return }
        if (s.isRecordingSung) { toggleSungRecording(); if (_state.value.error != null) return }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val existing = s.id?.let { graph.items.get(it) }
                val draft = (existing ?: Item(text = s.text)).copy(
                    text = s.text, kind = s.kind, category = s.category, pinned = s.pinned, imagePath = s.imagePath,
                    priceCents = Euro.parse(s.priceText), firstSyllableOverride = s.firstSyllableOverride,
                )
                val saved = graph.items.save(draft)
                _state.value.newRecording?.let { graph.items.addRecording(saved.id, it.file, it.durationMs, Who.CAREGIVER) }
                // Only a phrase is ever sung: attaching the take to a word would leave an .m4a on
                // disk that no module will ever look for, so a take orphaned by the kind switch is
                // deleted rather than saved.
                val sung = _state.value.newSungRecording?.takeIf { s.kind == ItemKind.PHRASE }
                sung?.let { graph.items.addRecording(saved.id, it.file, it.durationMs, Who.CAREGIVER, RecordingStyle.SUNG) }
                if (sung == null) _state.value.newSungRecording?.file?.delete()
                _state.update {
                    it.copy(
                        id = saved.id, newRecording = null, savedRecordingPath = it.recordingPath,
                        newSungRecording = null, savedSungPath = if (sung != null) it.sungPath else it.savedSungPath,
                        saving = false, dirty = false,
                    )
                }
                graph.feedback.success()
                onSaved(saved.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                graph.errors.record("item save", e)
                _state.update { it.copy(saving = false, error = "Δεν αποθηκεύτηκε. Δοκίμασε ξανά.") }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _state.value.id ?: return onDeleted()
        viewModelScope.launch { graph.items.delete(id); onDeleted() }
    }

    override fun onCleared() {
        if (graph.voice.isRecording) graph.voice.cancelRecording()
        _state.value.newRecording?.file?.delete()
        _state.value.newSungRecording?.file?.delete()
    }
}
