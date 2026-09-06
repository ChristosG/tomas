package gr.dimitris.app.core.data

import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.Syllabifier
import kotlinx.coroutines.flow.Flow
import java.io.File

/** All writes to items go through here so derived fields are always consistent. */
class ItemRepository(
    private val items: ItemDao,
    private val recordings: RecordingDao,
    /** How a media file becomes a stored path. The app passes MediaFiles::relativize; JVM tests keep it absolute. */
    private val relativize: (File) -> String = { it.absolutePath },
    private val clock: () -> Long = ::now,
) {
    fun observeAll(): Flow<List<Item>> = items.observeActive()
    fun observeByCategory(category: Category): Flow<List<Item>> = items.observeByCategory(category)
    suspend fun get(id: String): Item? = items.get(id)

    /**
     * [at] is the stamp the row gets. It defaults to now, which is what every caregiver edit wants;
     * the seed importer passes a stamp derived from the manifest version instead
     * ([gr.dimitris.app.core.seed.SeedIds.stamp]) so that two phones importing the same bundled
     * vocabulary write byte-identical rows — and so that any edit the family makes, stamped with a
     * real clock, always out-ranks a fresh import under last-write-wins.
     */
    suspend fun save(draft: Item, at: Long = clock()): Item {
        val text = draft.text.trim()
        val override = draft.firstSyllableOverride?.trim()?.takeIf { it.isNotEmpty() }
        val saved = draft.copy(
            text = text,
            firstSound = Greek.firstSound(text),
            firstSyllable = override ?: Syllabifier.firstSyllable(text),
            firstSyllableOverride = override,
            updatedAt = at,
        )
        items.upsert(saved)
        return saved
    }

    suspend fun delete(id: String) = items.softDelete(id, clock())

    /**
     * A sung take is a second voice for the same item, never the model one: only a spoken caregiver
     * recording becomes what the talk board and the word coach play back.
     */
    suspend fun addRecording(itemId: String, file: File, durationMs: Long, who: Who, style: RecordingStyle = RecordingStyle.SPOKEN): Recording {
        val path = relativize(file)
        // The same take, offered again: a re-saved dialogue line hands its existing file straight
        // back in. Writing a second row for it would leave a duplicate pointing at one file, and
        // ten passes over a dialogue would leave ten. Every real new take is a new file, so this
        // only ever catches the re-attachment.
        recordings.latestFor(itemId, who, style)?.takeIf { it.path == path }?.let { return link(itemId, it, who, style) }
        val recording = Recording(itemId = itemId, path = path, who = who, style = style, durationMs = durationMs, recordedAt = clock())
        recordings.upsert(recording)
        return link(itemId, recording, who, style)
    }

    /** A spoken caregiver take is the item's model voice; anything else is a second recording of it. */
    private suspend fun link(itemId: String, recording: Recording, who: Who, style: RecordingStyle): Recording {
        if (who == Who.CAREGIVER && style == RecordingStyle.SPOKEN) {
            items.get(itemId)?.takeIf { it.modelRecordingId != recording.id }
                ?.let { items.upsert(it.copy(modelRecordingId = recording.id, updatedAt = clock())) }
        }
        return recording
    }

    suspend fun modelRecording(item: Item): Recording? =
        item.modelRecordingId?.let { recordings.get(it) } ?: recordings.latestFor(item.id, Who.CAREGIVER, RecordingStyle.SPOKEN)

    /** The caregiver singing this item, for "Τραγούδα και πες το". Null when nobody has sung it yet. */
    suspend fun sungRecording(item: Item): Recording? = recordings.latestFor(item.id, Who.CAREGIVER, RecordingStyle.SUNG)
}
