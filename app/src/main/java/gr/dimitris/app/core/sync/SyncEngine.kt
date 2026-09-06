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
 * * `syncPushedUpTo` is an `updatedAt`. Everything newer than it goes up next time. It never passes
 *   **the clock as it was read before the first query**: the eight table reads and the uploads
 *   between them take seconds, and a word the caregiver saves during that window must not end up
 *   below a mark computed from a row a later table happened to hold. It never passes the newest row
 *   actually accepted either, and a row whose photo would not upload holds it below itself, so the
 *   next sync tries that row again rather than leaving the server with a `media://` nobody can
 *   resolve. Rows that arrive in the pull are above the mark too, so the next sync offers them back
 *   once and the server ignores them as ties — a little noise bought for a mark that is one number
 *   and cannot lose a row.
 * * `syncCursor` is the server's sequence number, advanced to the highest one **received** — never
 *   to the number the server reports as its own top, which a capped page would leave rows behind,
 *   and never past a page this phone could not finish writing. The loop asks again until a page
 *   comes back **empty**, not merely shorter than it asked for: a server that clamps the page
 *   smaller than the client asked — a proxy, a later version — would otherwise stop the sync
 *   halfway with nothing said. A cursor the server refuses is a corrupt one: it goes back to zero
 *   and the sync pulls again.
 *
 * Every sync ends by asking again for the files that did not arrive with their rows, because the
 * server has already moved past those rows and would never offer them a second time.
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
        var changed = pull(tally)
        if (repair(tally)) changed = true
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
        // Read before anything else is asked of the database. Everything the caregiver writes from
        // here on is stamped later than this, so it stays above whatever mark this run settles on —
        // the eight table reads below are not one snapshot, and the uploads between them are
        // seconds each. See the class KDoc.
        val startedAt = clock()
        val from = settings.syncPushedUpTo.first()
        // The newest row the server actually took. Rows read but not sent — a photo that would not
        // upload, a batch that failed — are not in it.
        var accepted = from
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
                val (rewritten, uploads) = MediaRefs.outgoing(spec.name, raw, files)
                if (!upload(uploads, tally)) {
                    blocked = minOf(blocked, at)
                    continue
                }
                pending += Pending(at, SyncRow(spec.name, rewritten))
            }
        }

        // Oldest first, so a batch that fails takes only rows at least as new as itself with it.
        // (This is why the order of `Tables.all` does not survive to the wire — the watermark, not
        // readability, decides what goes first; there are no foreign keys, so nothing depends on it.)
        pending.sortBy { it.at }
        for (batch in pending.chunked(BATCH)) {
            val left = try {
                send(batch, tally) { accepted = maxOf(accepted, it) }
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

        settings.setSyncPushedUpTo(maxOf(from, minOf(accepted, blocked - 1, startedAt)))
    }

    /**
     * Sends one batch, halving it when the server refuses the body whole. Returns the rows it could
     * not place at all. [took] is told the `updatedAt` of every row the server accepted.
     *
     * A 413 is "too many bytes" and a 400 is "I do not understand one of these rows" — the server
     * validates a push as a whole and stores nothing when one entry is wrong
     * (`server/store.mjs`), so both are answered the same way: halve, and halve again, until the
     * offending row is alone. Then it is skipped by name and the queue behind it goes on. Without
     * that, one row an older server does not recognise would stop this phone pushing anything, ever,
     * and the only sign would be one Greek line that never changed.
     */
    private suspend fun send(batch: List<Pending>, tally: Tally, took: (Long) -> Unit): List<Pending> {
        try {
            client.push(batch.map { it.row })
            tally.pushed += batch.size
            batch.forEach { took(it.at) }
            return emptyList()
        } catch (e: SyncException) {
            if (e.status != TOO_LARGE && e.status != BAD_REQUEST) throw e
            // Never the same body twice: the server answered and hung up, and asking again with the
            // same bytes would get the same answer.
            if (batch.size == 1) {
                val row = batch.single().row
                // The table and the id, never the content: a row can hold a caregiver's words.
                val name = "${row.table}/${Tables.of(row.table)?.idOf(row.row) ?: ";"}"
                tally.fail(if (e.status == TOO_LARGE) ROW_TOO_BIG else rowRefused(name), "sync push", e)
                return batch
            }
        }
        val half = batch.size / 2
        return send(batch.take(half), tally, took) + send(batch.drop(half), tally, took)
    }

    /** True when every file for a row is on the server. A file already there is not sent again. */
    private suspend fun upload(uploads: List<MediaUpload>, tally: Tally): Boolean {
        for ((sha, file) in uploads) {
            try {
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
            val landed = apply(page.rows, tally)
            if (landed.changed) changed = true
            // The cursor is what makes a row un-askable: the server only ever answers with rows
            // above it. A page this phone could not finish writing — a full disk, a corrupt file, a
            // row from a newer app version Gson cannot map — must therefore be left in front of the
            // cursor and asked for again next sync, not walked past with one Greek line.
            if (!landed.ok) break
            val top = page.rows.maxOf { it.seq }
            // A page that does not move the cursor would be asked for again for ever. It cannot
            // happen against our own server, which only ever answers with rows above `since`.
            if (top <= cursor) break
            cursor = top
            settings.setSyncCursor(cursor)
        }
        return changed
    }

    /** [changed] — something landed, so the screens must be told. [ok] — all of it landed. */
    private class Landed(val changed: Boolean, val ok: Boolean)

    private suspend fun apply(rows: List<PulledRow>, tally: Tally): Landed {
        var changed = false
        var ok = true
        for ((table, pulled) in rows.groupBy { it.table }) {
            // A table this version does not know is not a failure it can recover from by asking
            // again: it would stop the cursor for ever. It is left behind deliberately.
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
                ok = false
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
                ok = false
            }
        }
        return Landed(changed, ok)
    }

    // ---------------------------------------------------------------- repair

    /**
     * The files that did not come with their rows, asked for again.
     *
     * A row whose photo failed to download is stored holding `media://<sha>`, and the pull cursor
     * has already gone past it — the server will not offer it a second time, and a later pull of
     * the same row would be a tie and change nothing. Without this pass the promise the sync screen
     * makes, «Θα ξαναδοκιμάσω.», would never be kept: the word would show a placeholder for ever
     * and «Άκου» would fall back to the robot voice on that phone alone, with nothing said.
     *
     * Two indexed-free but tiny queries (only rows that hold the scheme), and the same fetch the
     * pull uses, so a hash already on disk costs nothing.
     */
    private suspend fun repair(tally: Tally): Boolean {
        var changed = false
        for (spec in Tables.all) {
            if (spec.mediaFields.isEmpty()) continue
            val rows = try {
                store.awaitingMedia(spec.name)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                tally.fail(READ_FAILED, "sync read ${spec.name}", e)
                continue
            }
            if (rows.isEmpty()) continue

            val fetched = mutableMapOf<String, File?>()
            val fixed = mutableListOf<Map<String, Any?>>()
            for (row in rows) {
                for ((sha, extension) in MediaRefs.needed(spec.name, row)) {
                    if (!fetched.containsKey(sha)) fetched[sha] = fetch(sha, extension, tally)
                }
                val out = MediaRefs.incoming(spec.name, row, files) { sha, _ -> fetched[sha] }
                if (out != row) fixed += out
            }
            if (fixed.isEmpty()) continue
            try {
                // Not counted as pulled: nothing arrived, a row this phone already had was mended.
                store.apply(spec.name, fixed)
                changed = true
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                tally.fail(WRITE_FAILED, "sync write ${spec.name}", e)
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
        const val WRITE_FAILED = "Δεν μπόρεσα να γράψω στη βάση. Θα ξαναδοκιμάσω."

        /** [name] is `table/id` — enough to find the row, never a word of what is in it. */
        fun rowRefused(name: String) = "Ο διακομιστής δεν δέχτηκε μία εγγραφή ($name). Την προσπέρασα."

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
