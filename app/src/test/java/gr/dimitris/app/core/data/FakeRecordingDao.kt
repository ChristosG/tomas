package gr.dimitris.app.core.data

class FakeRecordingDao : RecordingDao {
    val rows = mutableMapOf<String, Recording>()
    override suspend fun upsert(recording: Recording) { rows[recording.id] = recording }
    override suspend fun get(id: String): Recording? = rows[id]?.takeIf { !it.deleted }
    override suspend fun latestFor(itemId: String, who: Who, style: RecordingStyle): Recording? =
        rows.values.filter { it.itemId == itemId && it.who == who && it.style == style && !it.deleted }.maxByOrNull { it.recordedAt }
    override suspend fun allFor(itemId: String, who: Who, style: RecordingStyle): List<Recording> =
        rows.values.filter { it.itemId == itemId && it.who == who && it.style == style && !it.deleted }
            .sortedByDescending { it.recordedAt }
    override suspend fun softDelete(id: String, now: Long) { rows[id]?.let { rows[id] = it.copy(deleted = true, updatedAt = now) } }
    override suspend fun activeWithPath(path: String): Int = rows.values.count { it.path == path && !it.deleted }
    override suspend fun itemsWithVoice(who: Who): List<String> =
        rows.values.filter { !it.deleted && it.who == who }.map { it.itemId }.distinct()

    override suspend fun changedSince(since: Long): List<Recording> =
        rows.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.values.filter { it.id in ids }.map { RowStamp(it.id, it.updatedAt) }
    override suspend fun upsertFromSync(rows: List<Recording>) { rows.forEach { upsert(it) } }
    override suspend fun awaitingMedia(): List<Recording> = rows.values.filter { it.path.startsWith("media://") }
}
