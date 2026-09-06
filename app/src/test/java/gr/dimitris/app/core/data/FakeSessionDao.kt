package gr.dimitris.app.core.data

class FakeSessionDao : SessionDao {
    val rows = mutableMapOf<String, Session>()
    override suspend fun upsert(session: Session) { rows[session.id] = session }
    override suspend fun get(id: String): Session? = rows[id]
    override suspend fun recent(limit: Int): List<Session> =
        rows.values.filter { !it.deleted }.sortedByDescending { it.startedAt }.take(limit)
    override suspend fun between(from: Long, to: Long): List<Session> =
        rows.values.filter { !it.deleted && it.startedAt in from..to }.sortedBy { it.startedAt }

    override suspend fun changedSince(since: Long): List<Session> =
        rows.values.filter { it.updatedAt > since }.sortedBy { it.updatedAt }
    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.values.filter { it.id in ids }.map { RowStamp(it.id, it.updatedAt) }
    override suspend fun upsertFromSync(rows: List<Session>) { rows.forEach { upsert(it) } }
}
