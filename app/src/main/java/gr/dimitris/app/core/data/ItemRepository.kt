package gr.dimitris.app.core.data

import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.Syllabifier
import kotlinx.coroutines.flow.Flow
import java.io.File

/** All writes to items go through here so derived fields are always consistent. */
class ItemRepository(
    private val items: ItemDao,
    private val recordings: RecordingDao,
    private val clock: () -> Long = ::now,
) {
    fun observeAll(): Flow<List<Item>> = items.observeActive()
    fun observeByCategory(category: Category): Flow<List<Item>> = items.observeByCategory(category)
    suspend fun get(id: String): Item? = items.get(id)

    suspend fun save(draft: Item): Item {
        val text = draft.text.trim()
        val override = draft.firstSyllableOverride?.trim()?.takeIf { it.isNotEmpty() }
        val saved = draft.copy(
            text = text,
            firstSound = Greek.firstSound(text),
            firstSyllable = override ?: Syllabifier.firstSyllable(text),
            firstSyllableOverride = override,
            updatedAt = clock(),
        )
        items.upsert(saved)
        return saved
    }

    suspend fun delete(id: String) = items.softDelete(id, clock())

    suspend fun addRecording(itemId: String, file: File, durationMs: Long, who: Who): Recording {
        val recording = Recording(itemId = itemId, path = file.absolutePath, who = who, durationMs = durationMs, recordedAt = clock())
        recordings.upsert(recording)
        if (who == Who.CAREGIVER) {
            items.get(itemId)?.let { items.upsert(it.copy(modelRecordingId = recording.id, updatedAt = clock())) }
        }
        return recording
    }

    suspend fun modelRecording(item: Item): Recording? =
        item.modelRecordingId?.let { recordings.get(it) } ?: recordings.latestFor(item.id, Who.CAREGIVER)
}
