package gr.dimitris.app.caregiver.content

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
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
    val saving: Boolean = false,
    val error: String? = null,
) {
    val recordingPath: String? get() = newRecording?.file?.absolutePath ?: savedRecordingPath
    val isNew: Boolean get() = id == null
}

class ItemEditViewModel(private val graph: AppGraph, private val itemId: String?) : ViewModel() {
    private val _state = MutableStateFlow(ItemEditState())
    val state: StateFlow<ItemEditState> = _state.asStateFlow()

    init {
        if (itemId != null) viewModelScope.launch {
            val item = graph.items.get(itemId) ?: return@launch
            val model = graph.items.modelRecording(item)
            _state.value = ItemEditState(
                id = item.id, text = item.text, kind = item.kind, category = item.category, pinned = item.pinned,
                priceText = item.priceCents?.let { Euro.format(it).removeSuffix(" €") } ?: "", imagePath = item.imagePath,
                firstSyllableOverride = item.firstSyllableOverride ?: "", autoSyllable = Syllabifier.firstSyllable(item.text),
                savedRecordingPath = model?.path,
            )
        }
    }

    fun setText(text: String) = _state.update { it.copy(text = text, autoSyllable = Syllabifier.firstSyllable(text.trim()), error = null) }
    fun setKind(kind: ItemKind) = _state.update { it.copy(kind = kind) }
    fun setCategory(category: Category) = _state.update { it.copy(category = category) }
    fun setPinned(on: Boolean) = _state.update { it.copy(pinned = on) }
    fun setPriceText(t: String) = _state.update { it.copy(priceText = t, error = null) }
    fun setOverride(value: String) = _state.update { it.copy(firstSyllableOverride = value) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun photoPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.images.import(graph.app.contentResolver, uri) }
                .onSuccess { file -> _state.update { it.copy(imagePath = graph.files.relativize(file)) } }
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
            _state.update { it.copy(imagePath = graph.files.relativize(file)) }
        }
    }

    fun toggleRecording() {
        if (_state.value.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        runCatching { graph.voice.startRecording() }
            .onSuccess { _state.update { it.copy(isRecording = true, error = null) } }
            .onFailure { e -> graph.errors.record("recorder start", e); _state.update { it.copy(error = "Δεν ξεκίνησε η ηχογράφηση") } }
    }

    private fun stopRecording() {
        runCatching { graph.voice.stopRecording() }
            .onSuccess { rec -> _state.value.newRecording?.file?.delete(); _state.update { it.copy(isRecording = false, newRecording = rec) } }
            .onFailure { e -> graph.errors.record("recorder stop", e); _state.update { it.copy(isRecording = false, error = "Πολύ σύντομη ηχογράφηση, δοκίμασε ξανά") } }
    }

    fun playRecording() {
        val path = _state.value.recordingPath ?: return
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

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.text.isBlank()) { _state.update { it.copy(error = "Γράψε τη λέξη πρώτα") }; return }
        // A half-typed price would silently become no price at all, so it stops the save instead.
        if (s.priceText.isNotBlank() && Euro.parse(s.priceText) == null) {
            _state.update { it.copy(error = "Η τιμή θέλει μορφή 3,50") }
            return
        }
        if (s.isRecording) stopRecording()
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
                _state.update { it.copy(id = saved.id, newRecording = null, savedRecordingPath = it.recordingPath, saving = false) }
                graph.feedback.success()
                onSaved()
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
    }
}
