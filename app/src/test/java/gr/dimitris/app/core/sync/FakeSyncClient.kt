package gr.dimitris.app.core.sync

import java.io.File

/**
 * A server in a map. It keeps the same promises the real one does — a sequence number per accepted
 * row, only the current version of a row ever returned, pages capped at [limit] — because the whole
 * point of the cursor is what happens when a page is capped.
 */
class FakeSyncClient(private val cap: Int = 500) : SyncClient {
    /** table+id → (seq, row), the server's index. */
    private val rows = LinkedHashMap<String, Pair<Long, PulledRow>>()
    val media = mutableMapOf<String, ByteArray>()

    var seq = 0L
        private set

    val pushed = mutableListOf<SyncRow>()
    var pushes = 0
        private set
    var pulls = 0
        private set
    var uploads = 0
        private set
    var downloads = 0
        private set

    /** What the next call of each kind should throw instead of answering. Cleared once thrown. */
    var failPush: SyncException? = null
    var failPull: SyncException? = null
    var failUpload: SyncException? = null
    var failDownload: SyncException? = null

    /** Rows over this many in one push are answered with a 413, like a body over the server's limit. */
    var maxRowsPerPush = Int.MAX_VALUE

    override suspend fun health(): Long = seq

    override suspend fun push(rows: List<SyncRow>): PushResult {
        failPush?.let { failPush = null; throw it }
        if (rows.size > maxRowsPerPush) throw SyncException(HttpSyncClient.TOO_BIG, 413)
        pushes++
        var accepted = 0
        for (row in rows) {
            val spec = Tables.of(row.table) ?: throw SyncException(HttpSyncClient.REFUSED, 400)
            val id = spec.idOf(row.row) ?: throw SyncException(HttpSyncClient.REFUSED, 400)
            val key = "${row.table}/$id"
            val existing = this.rows[key]
            val keep = when {
                existing == null -> true
                spec.appendOnly -> false
                else -> Rows.updatedAt(row.row) > Rows.updatedAt(existing.second.row)
            }
            if (!keep) continue
            seq++
            this.rows[key] = seq to PulledRow(seq, row.table, row.row)
            pushed += row
            accepted++
        }
        return PushResult(accepted, rows.size - accepted, seq)
    }

    override suspend fun pull(since: Long, limit: Int): PullPage {
        failPull?.let { failPull = null; throw it }
        pulls++
        if (since < 0) throw SyncException(HttpSyncClient.REFUSED, 400)
        val page = rows.values.map { it.second }.filter { it.seq > since }.sortedBy { it.seq }
            .take(minOf(limit, cap))
        return PullPage(page, seq)
    }

    override suspend fun hasMedia(sha: String): Boolean = media.containsKey(sha)

    override suspend fun putMedia(sha: String, file: File) {
        failUpload?.let { failUpload = null; throw it }
        uploads++
        media[sha] = file.readBytes()
    }

    override suspend fun getMedia(sha: String, dest: File) {
        failDownload?.let { failDownload = null; throw it }
        val bytes = media[sha] ?: throw SyncException(HttpSyncClient.NOT_FOUND, 404)
        downloads++
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
    }

    /** Puts a row on the server as if another phone had pushed it. */
    fun seed(table: String, row: Map<String, Any?>) {
        val spec = Tables.of(table)!!
        val id = spec.idOf(row)!!
        seq++
        rows["$table/$id"] = seq to PulledRow(seq, table, row)
    }

    fun rowsOf(table: String): List<Map<String, Any?>> =
        rows.values.map { it.second }.filter { it.table == table }.map { it.row }
}
