package gr.dimitris.app.core.sync

import gr.dimitris.app.core.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** What one sync did. [errors] are Greek sentences, already fit to put on a screen. */
data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val mediaUp: Int = 0,
    val mediaDown: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()
}

/** What the sync screen shows: whether it is working, and the last thing that happened. */
data class SyncState(
    val running: Boolean = false,
    val report: SyncReport? = null,
    /** A Greek sentence, or the failure that stopped the sync before it had a report. */
    val line: String = "",
    val at: Long = 0,
)

/**
 * Push what changed here, pull what changed anywhere else, and never lose either.
 *
 * Two cursors, both kept in the settings and both moved only after the work they describe
 * succeeded:
 *
 * * `syncPushedUpTo` is an `updatedAt`. Everything newer than it goes up next time. A row whose
 *   photo would not upload holds the mark below itself, so the next sync tries that row again
 *   rather than leaving the server with a `media://` nobody can resolve. Rows that arrive in the
 *   pull are above the mark too, so the next sync offers them back once and the server ignores
 *   them as ties — a little noise bought for a mark that is one number and cannot lose a row.
 * * `syncCursor` is the server's sequence number, advanced to the highest one **received** — never
 *   to the number the server reports as its own top, which a capped page would leave rows behind.
 *   The loop asks again until a page comes back **empty**, not merely shorter than it asked for: a
 *   server that clamps the page smaller than the client asked — a proxy, a later version — would
 *   otherwise stop the sync halfway with nothing said. A cursor the server refuses is a corrupt
 *   one: it goes back to zero and the sync pulls again.
 *
 * One run at a time, and never on the main thread. Everything that goes wrong is a Greek sentence
 * in the report and a row in `error_logs`; nothing here ever throws at a screen.
 */
class SyncEngine(
    private val client: SyncClient,
    private val store: SyncStore,
    private val files: MediaPaths,
    private val settings: Settings,
    /** Bumped after a pull that changed rows, so open screens re-read the database. */
    private val onPulled: () -> Unit = {},
    private val record: (String, Throwable) -> Unit = { _, _ -> },
    /** Address and token both set. The token lives in the encrypted store, which is not ours to read. */
    private val configured: suspend () -> Boolean = { settings.syncUrl.first().isNotBlank() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * At app start, when there is somewhere to sync to. Failures are written down and nothing is
     * shown: nobody tapped anything, so nobody is waiting for an answer. A phone that has not been
     * set up does not touch the network at all — which is every phone until a caregiver types an
     * address, and Dimitris' phone until his father does it for him.
     */
    suspend fun syncAtStart() {
        if (!configured()) return
        syncNow()
    }

    suspend fun syncNow(): Result<SyncReport> {
        if (!running.compareAndSet(false, true)) return Result.failure(SyncException(BUSY))
        _state.value = _state.value.copy(running = true)
        return try {
            withContext(Dispatchers.IO) { run() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            val failure = e as? SyncException ?: SyncException(HttpSyncClient.OFFLINE)
            record("sync", failure)
            _state.value = SyncState(running = false, line = failure.message.orEmpty(), at = clock())
            Result.failure(failure)
        } finally {
            running.set(false)
            _state.value = _state.value.copy(running = false)
        }
    }

    private suspend fun run(): Result<SyncReport> {
        if (settings.syncUrl.first().isBlank()) return Result.failure(SyncException(HttpSyncClient.NOT_CONFIGURED))
        val tally = Tally()
        push(tally)
        val changed = pull(tally)
        val at = clock()
        settings.setLastSyncAt(at)
        if (changed) onPulled()
        val report = tally.report()
        _state.value = SyncState(running = true, report = report, line = line(report, at), at = at)
        return Result.success(report)
    }

    // ---------------------------------------------------------------- push

    private class Pending(val at: Long, val row: SyncRow)

    private suspend fun push(tally: Tally) {
        val from = settings.syncPushedUpTo.first()
        var high = from
        // The `updatedAt` of the oldest row that did not make it. The mark stops just below it, so
        // the next sync starts again from there and nothing is silently left behind.
        var blocked = Long.MAX_VALUE
        val pending = mutableListOf<Pending>()

        for (spec in Tables.all) {
            val rows = try {
                store.changedSince(spec.name, from)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                tally.fail(READ_FAILED, "sync read ${spec.name}", e)
                continue
            }
            for (raw in rows) {
                val at = Rows.updatedAt(raw)
                high = maxOf(high, at)
                val (rewritten, uploads) = MediaRefs.outgoing(spec.name, raw, files)
                if (!upload(uploads, tally)) {
                    blocked = minOf(blocked, at)
                    continue
                }
                pending += Pending(at, SyncRow(spec.name, rewritten))
            }
        }

        // Oldest first, so a batch that fails takes only rows at least as new as itself with it.
        pending.sortBy { it.at }
        for (batch in pending.chunked(BATCH)) {
            val left = try {
                send(batch, tally)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                val failure = e as? SyncException ?: SyncException(HttpSyncClient.OFFLINE)
                tally.fail(failure.message.orEmpty(), "sync push", failure)
                blocked = minOf(blocked, batch.first().at)
                break
            }
            for (skipped in left) blocked = minOf(blocked, skipped.at)
        }

        // Never past this phone's own present. A row pulled from a phone whose clock runs fast is
        // stamped in the future, and letting the mark follow it there would skip everything written
        // here until the clock caught up — words a caregiver typed, gone with nothing said. The
        // future row is offered again on every sync until then, and the server ignores it as a tie.
        settings.setSyncPushedUpTo(maxOf(from, minOf(high, blocked - 1, clock())))
    }

    /** Sends one batch, halving it on a 413. Returns the rows that were too big even alone. */
    private suspend fun send(batch: List<Pending>, tally: Tally): List<Pending> {
        try {
            client.push(batch.map { it.row })
            tally.pushed += batch.size
            return emptyList()
        } catch (e: SyncException) {
            if (e.status != TOO_LARGE) throw e
            // Never the same body twice: the server answered and hung up, and asking again with the
            // same bytes would get the same answer.
            if (batch.size == 1) {
                tally.fail(ROW_TOO_BIG, "sync push", e)
                return batch
            }
        }
        val half = batch.size / 2
        return send(batch.take(half), tally) + send(batch.drop(half), tally)
    }

    /** True when every file for a row is on the server. A file already there is not sent again. */
    private suspend fun upload(uploads: List<File>, tally: Tally): Boolean {
        for (file in uploads) {
            try {
                val sha = MediaRefs.sha256(file)
                if (!client.hasMedia(sha)) {
                    client.putMedia(sha, file)
                    tally.mediaUp++
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                val failure = e as? SyncException ?: SyncException(HttpSyncClient.OFFLINE)
                tally.fail(UPLOAD_FAILED, "sync media up", failure)
                return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- pull

    /** True when something landed, which is when the screens have to be told. */
    private suspend fun pull(tally: Tally): Boolean {
        var cursor = settings.syncCursor.first()
        var reset = false
        var changed = false
        while (true) {
            val page = try {
                client.pull(cursor, PAGE)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                val failure = e as? SyncException ?: SyncException(HttpSyncClient.OFFLINE)
                // A cursor the server will not read is a corrupt one — from a restored backup, or a
                // server whose data directory was replaced. Start again from the beginning, once.
                if (failure.status == BAD_REQUEST && !reset) {
                    reset = true
                    cursor = 0
                    settings.setSyncCursor(0)
                    tally.fail(CURSOR_RESET, "sync pull", failure)
                    continue
                }
                tally.fail(failure.message.orEmpty(), "sync pull", failure)
                break
            }
            if (page.rows.isEmpty()) break
            if (apply(page.rows, tally)) changed = true
            val top = page.rows.maxOf { it.seq }
            // A page that does not move the cursor would be asked for again for ever. It cannot
            // happen against our own server, which only ever answers with rows above `since`.
            if (top <= cursor) break
            cursor = top
            settings.setSyncCursor(cursor)
        }
        return changed
    }

    private suspend fun apply(rows: List<PulledRow>, tally: Tally): Boolean {
        var changed = false
        for ((table, pulled) in rows.groupBy { it.table }) {
            val spec = Tables.of(table) ?: continue
            // Later wins inside a page: the server only ever sends the current version of a row, but
            // nothing here has to depend on that.
            val latest = LinkedHashMap<String, Map<String, Any?>>()
            for (entry in pulled) spec.idOf(entry.row)?.let { latest[it] = entry.row }
            if (latest.isEmpty()) continue

            val stamps = try {
                store.stamps(table, latest.keys.toList())
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                tally.fail(READ_FAILED, "sync read $table", e)
                continue
            }

            val winners = latest.filter { (id, row) ->
                val local = stamps[id]?.let { mapOf<String, Any?>("id" to id, "updatedAt" to it) }
                Merge.decide(local, row, spec.appendOnly)
            }
            // Every file the winning rows need, fetched once each before anything is rewritten: two
            // rows can point at the same photo, and one hash is one download.
            val fetched = mutableMapOf<String, File?>()
            for (row in winners.values) {
                for ((sha, extension) in MediaRefs.needed(table, row)) {
                    if (!fetched.containsKey(sha)) fetched[sha] = fetch(sha, extension, tally)
                }
            }
            val keep = winners.values.map { row ->
                MediaRefs.incoming(table, row, files) { sha, _ -> fetched[sha] }
            }
            if (keep.isEmpty()) continue
            try {
                store.apply(table, keep)
                tally.pulled += keep.size
                changed = true
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                tally.fail(WRITE_FAILED, "sync write $table", e)
            }
        }
        return changed
    }

    /**
     * The bytes behind one `media://`, or null when they could not be had. Null is not a failure of
     * the row: the value stays a `media://` and the next sync asks for the file again.
     */
    private suspend fun fetch(sha: String, extension: String, tally: Tally): File? {
        val destination = File(MediaRefs.folderFor(extension, files), "$sha.$extension")
        if (destination.isFile && destination.length() > 0) return destination
        return try {
            client.getMedia(sha, destination)
            tally.mediaDown++
            destination
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            val failure = e as? SyncException ?: SyncException(HttpSyncClient.OFFLINE)
            tally.fail(DOWNLOAD_FAILED, "sync media down", failure)
            null
        }
    }

    // ---------------------------------------------------------------- tally

    /** Counts, plus the Greek sentences. The same failure said twice is said once. */
    private inner class Tally {
        var pushed = 0
        var pulled = 0
        var mediaUp = 0
        var mediaDown = 0
        private val errors = LinkedHashSet<String>()
        private val written = HashSet<String>()

        /**
         * [e] is what goes in `error_logs`, so every caller that touched the network hands in a
         * [SyncException] — which carries no cause and no request — and only the database paths
         * hand in the real throwable, whose stack is worth keeping and holds no secret.
         *
         * Written down once per kind per run. A wrong token fails the upload of every photo on the
         * phone, and two hundred identical rows would bury the one thing a caregiver came to
         * `Σφάλματα` to read.
         */
        fun fail(line: String, where: String, e: Throwable) {
            if (line.isNotBlank()) errors += line
            if (written.add("$where|$line")) record(where, e)
        }

        fun report() = SyncReport(pushed, pulled, mediaUp, mediaDown, errors.toList())
    }

    companion object {
        /** Rows per push. Small enough that the server's 5 MB body limit is never the thing that trips. */
        const val BATCH = 200

        /** The server clamps a pull to 500; asking for exactly that is one round trip per page. */
        const val PAGE = 500

        private const val BAD_REQUEST = 400
        private const val TOO_LARGE = 413

        const val BUSY = "Ο συγχρονισμός τρέχει ήδη."
        const val CURSOR_RESET = "Ο δείκτης δεν ίσχυε πια· ξεκίνησα από την αρχή."
        const val ROW_TOO_BIG = "Μία εγγραφή ήταν πολύ μεγάλη και δεν στάλθηκε."
        const val UPLOAD_FAILED = "Δεν ανέβηκε κάποιο αρχείο. Θα ξαναδοκιμάσω."
        const val DOWNLOAD_FAILED = "Δεν κατέβηκε κάποιο αρχείο. Θα ξαναδοκιμάσω."
        const val READ_FAILED = "Δεν μπόρεσα να διαβάσω τη βάση."
        const val WRITE_FAILED = "Δεν μπόρεσα να γράψω στη βάση."

        /** The one line a caregiver reads. Greek counts one and many differently. */
        fun line(report: SyncReport, at: Long): String {
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
            val rows = "Έστειλα ${count(report.pushed, "γραμμή", "γραμμές")}, πήρα ${count(report.pulled, "γραμμή", "γραμμές")}."
            val media = if (report.mediaUp == 0 && report.mediaDown == 0) "" else
                " Αρχεία: ${report.mediaUp} πάνω, ${report.mediaDown} κάτω."
            val errors = if (report.errors.isEmpty()) "" else
                " ${count(report.errors.size, "πρόβλημα", "προβλήματα")}."
            return "$rows$media$errors ($time)"
        }

        private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
    }
}
