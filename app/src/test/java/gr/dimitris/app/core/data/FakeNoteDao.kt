package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeNoteDao : NoteDao {
    val rows = MutableStateFlow<Map<String, Note>>(emptyMap())
    private fun live() = rows.value.values.filter { !it.deleted }.sortedByDescending { it.at }

    override suspend fun upsert(note: Note) { rows.value = rows.value + (note.id to note) }
    override suspend fun recent(limit: Int): List<Note> = live().take(limit)
    override fun observeRecent(limit: Int): Flow<List<Note>> =
        rows.map { m -> m.values.filter { !it.deleted }.sortedByDescending { it.at }.take(limit) }
    override suspend fun softDelete(id: String, now: Long) {
        rows.value[id]?.let { upsert(it.copy(deleted = true, updatedAt = now)) }
    }

    override suspend fun changedSince(since: Long): List<Note> =
        rows.value.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.value.values.filter { it.id in ids }.map { RowStamp(it.id, it.updatedAt) }
    override suspend fun upsertFromSync(rows: List<Note>) { rows.forEach { upsert(it) } }
}
