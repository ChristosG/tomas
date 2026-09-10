package gr.dimitris.app.core.audio

import java.io.File

/**
 * Files this phone means to delete, but not yet.
 *
 * Retention keeps only the newest few of his takes of each word, and the older rows are soft-deleted
 * so the other phone loses them too. The obvious thing — delete the row and the file in one breath —
 * is what the first version did, and it quietly broke the half that matters: a row on the wire
 * carries `media://<sha>` only when the file is still there to be hashed
 * ([gr.dimitris.app.core.sync.MediaRefs.outgoing]), so a deletion whose bytes were already gone
 * travelled with this phone's own `recordings/<uuid>.wav` — a name the other phone has never heard
 * of. It stored the row, looked for that file, found nothing, and kept its own copy for ever.
 *
 * So the file waits here until the deletion has actually been pushed. Then it goes.
 *
 * A plain text file, one record a line, `<epoch millis>\t<relative path>`: this is a list of at most
 * a few dozen short strings that has to survive being killed mid-sitting, and a table or a DataStore
 * would be more machinery than the job is worth. Every operation rewrites the whole file, which at
 * this size is one small write.
 *
 * Nothing here throws. A list that cannot be read is an empty list and a file that cannot be deleted
 * is tried again next time: none of it is his work, and none of it is worth a Greek line.
 */
class PendingRemovals(private val file: File) {

    /** One file waiting to go, and when it was asked for. */
    data class Waiting(val path: String, val since: Long)

    /** Adds [path] to the list. Recording the same path twice is not an error; it is one entry. */
    @Synchronized fun add(path: String, at: Long) {
        val kept = read().filterNot { it.path == path }
        write(kept + Waiting(path, at))
    }

    @Synchronized fun all(): List<Waiting> = read()

    /**
     * Deletes every file asked for at or before [upTo] and forgets it, and hands back how many went.
     * [resolve] turns a stored path into a file the way the rest of the app does.
     *
     * Called with the moment a successful push started: everything recorded before then travelled in
     * it, so the other phone has the deletion and these bytes are no longer anyone's. A file that has
     * already gone — a backup restored over it, a caregiver clearing storage — counts as done.
     *
     * [stillUsed] is the question the receiver's own sweep asks before it deletes anything
     * (`SyncEngine`/`SyncStore.mediaStillUsed`), asked here too so both ends of the same deletion
     * ask it. An entry whose path a *live* row names is not this list's to delete any more —
     * a restore or a pull can hand a file back with a row for it — so it is dropped from the list
     * rather than acted on, and it is not counted as gone, because nothing went.
     */
    @Synchronized fun release(
        upTo: Long,
        stillUsed: (String) -> Boolean = { false },
        resolve: (String) -> File,
    ): Int {
        val waiting = read()
        if (waiting.isEmpty()) return 0
        val (due, later) = waiting.partition { it.since <= upTo }
        if (due.isEmpty()) return 0
        var gone = 0
        val stuck = mutableListOf<Waiting>()
        for (entry in due) {
            if (runCatching { stillUsed(entry.path) }.getOrDefault(false)) continue
            val removed = runCatching {
                val target = resolve(entry.path)
                !target.exists() || target.delete()
            }.getOrDefault(false)
            if (removed) gone++ else stuck += entry
        }
        write(later + stuck)
        return gone
    }

    /**
     * Forgets everything. For a database that has been replaced under it:
     * [gr.dimitris.app.AppGraph.reopenDatabase], which is the backup import.
     */
    @Synchronized fun clear() {
        runCatching { file.delete() }
    }

    private fun read(): List<Waiting> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val at = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            val path = line.substring(tab + 1)
            if (path.isEmpty()) null else Waiting(path, at)
        }
    }.getOrDefault(emptyList())

    private fun write(entries: List<Waiting>) {
        runCatching {
            if (entries.isEmpty()) file.delete()
            else file.writeText(entries.joinToString("\n") { "${it.since}\t${it.path}" })
        }
    }

    companion object {
        /**
         * How long a file waits when nothing ever pushes it.
         *
         * Sync is optional — it is off until a caregiver types in an address — and on a phone that
         * never syncs there is no "after the push" to wait for. A week is long past the point where
         * the pruned take was of any use to anyone, and it is the only thing standing between a phone
         * with no server and a recordings folder that only ever grows.
         */
        const val UNSYNCED_MS = 7L * 24 * 60 * 60 * 1000
    }
}
