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
    /** The other direction, for the one thing that has to reach a file: pruning his old takes. */
    private val resolve: (String) -> File = ::File,
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
        prune(itemId, who, style)
        return link(itemId, recording, who, style)
    }

    /**
     * Only the newest [HIS_TAKES] of **his** takes of one word are kept; the rest are soft-deleted
     * and their files removed.
     *
     * A take used to be a deliberate «Ηχογράφηση» tap and an AAC file of about half a megabyte a
     * minute. Since spec §13 folded the two microphones into one it is *every* «Μίλα» window and raw
     * PCM at nearly four times the size, so a word he practises daily would quietly gather hundreds
     * of megabytes on his phone and, through sync, on the caregiver's too — and a row under it in
     * «Πρόοδος» for every window he ever opened.
     *
     * Three, because three is what anyone actually listens back to: this one, and the two before it
     * to hear whether it is coming easier. The delete is soft and stamped, so it travels as a
     * deletion on the next sync and the other phone loses the file too.
     *
     * A caregiver's model voice is never pruned. Hers is the thing being practised against, there is
     * one of it per word, and it is not hers to lose.
     */
    private suspend fun prune(itemId: String, who: Who, style: RecordingStyle) {
        if (who != Who.DIMITRIS) return
        val old = recordings.allFor(itemId, who, style).drop(HIS_TAKES)
        if (old.isEmpty()) return
        val at = clock()
        for (row in old) {
            recordings.softDelete(row.id, at)
            // The row is gone whatever happens to the file: a delete that fails — a file already
            // removed, a path from a phone this backup came from — must not stop the next one.
            runCatching { resolve(row.path).takeIf { it.isFile }?.delete() }
        }
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

    companion object {
        /**
         * How many of his own takes of one word survive. Three: this one, and the two before it to
         * hear whether it is coming easier. See [prune].
         */
        const val HIS_TAKES = 3
    }
}
