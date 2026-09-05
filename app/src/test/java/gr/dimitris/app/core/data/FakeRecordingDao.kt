package gr.dimitris.app.core.data

class FakeRecordingDao : RecordingDao {
    val rows = mutableMapOf<String, Recording>()
    override suspend fun upsert(recording: Recording) { rows[recording.id] = recording }
    override suspend fun get(id: String): Recording? = rows[id]?.takeIf { !it.deleted }
    override suspend fun latestFor(itemId: String, who: Who, style: RecordingStyle): Recording? =
        rows.values.filter { it.itemId == itemId && it.who == who && it.style == style && !it.deleted }.maxByOrNull { it.recordedAt }
    override suspend fun softDelete(id: String, now: Long) { rows[id]?.let { rows[id] = it.copy(deleted = true, updatedAt = now) } }
}
