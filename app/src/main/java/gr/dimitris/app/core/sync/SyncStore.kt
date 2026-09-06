package gr.dimitris.app.core.sync

import gr.dimitris.app.core.data.AppDatabase
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.AttemptDao
import gr.dimitris.app.core.data.ErrorLog
import gr.dimitris.app.core.data.ErrorLogDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemDao
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.RecordingDao
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.ScriptDao
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.SessionDao

/**
 * The database as the engine sees it: rows in, rows out, by table name. Everything above this is
 * about the protocol and nothing above it knows what a DAO is.
 */
interface SyncStore {
    /** Every row whose `updatedAt` is past [since], deleted ones included, oldest first. */
    suspend fun changedSince(table: String, since: Long): List<Map<String, Any?>>

    /** How new the rows already here are, by sync id. Absent means "this phone has never seen it". */
    suspend fun stamps(table: String, ids: List<String>): Map<String, Long>

    /** Writes rows another phone sent, exactly as they arrived. Never touches `updatedAt`. */
    suspend fun apply(table: String, rows: List<Map<String, Any?>>)

    /**
     * Rows still holding a `media://` in a media column: a file that did not download when the row
     * arrived. The server will never offer the row again — the cursor has passed it — so every sync
     * asks for these itself.
     */
    suspend fun awaitingMedia(table: String): List<Map<String, Any?>>
}

/** The seven DAOs the sync writes through. [of] takes them from the live database. */
class SyncDaos(
    val items: ItemDao,
    val recordings: RecordingDao,
    val attempts: AttemptDao,
    val schedules: ScheduleDao,
    val sessions: SessionDao,
    val errorLogs: ErrorLogDao,
    val scripts: ScriptDao,
) {
    companion object {
        fun of(db: AppDatabase) = SyncDaos(
            db.items(), db.recordings(), db.attempts(), db.schedules(), db.sessions(), db.errorLogs(), db.scripts(),
        )
    }
}

/**
 * [SyncStore] over the real DAOs. The DAOs are read through a lambda on every call so a backup
 * import — which closes the database and opens another one — never leaves the engine holding a
 * dao from a database that is gone.
 */
class DaoSyncStore(private val daos: () -> SyncDaos) : SyncStore {

    override suspend fun changedSince(table: String, since: Long): List<Map<String, Any?>> {
        val spec = Tables.of(table) ?: return emptyList()
        val d = daos()
        val entities: List<Any> = when (table) {
            Tables.ITEMS -> d.items.changedSince(since)
            Tables.RECORDINGS -> d.recordings.changedSince(since)
            Tables.ATTEMPTS -> d.attempts.changedSince(since)
            Tables.SCHEDULES -> d.schedules.changedSince(since)
            Tables.SESSIONS -> d.sessions.changedSince(since)
            Tables.ERROR_LOGS -> d.errorLogs.changedSince(since)
            Tables.SCRIPTS -> d.scripts.scriptsChangedSince(since)
            Tables.SCRIPT_LINES -> d.scripts.linesChangedSince(since)
            else -> emptyList()
        }
        return entities.map { spec.withId(Rows.of(it)) }
    }

    /**
     * The append-only tables are asked too, even though their `@Insert(IGNORE)` would drop a
     * duplicate anyway. It is one query a page, and without it every attempt this phone has ever
     * written would be counted as "pulled" the moment it came back around — and the number a
     * caregiver reads on the sync screen would say that hundreds of rows arrived when none did.
     */
    override suspend fun stamps(table: String, ids: List<String>): Map<String, Long> {
        if (ids.isEmpty()) return emptyMap()
        val d = daos()
        val stamps = when (table) {
            Tables.ITEMS -> d.items.stamps(ids)
            Tables.RECORDINGS -> d.recordings.stamps(ids)
            Tables.ATTEMPTS -> d.attempts.stamps(ids)
            Tables.SCHEDULES -> d.schedules.stamps(ids)
            Tables.SESSIONS -> d.sessions.stamps(ids)
            Tables.ERROR_LOGS -> d.errorLogs.stamps(ids)
            Tables.SCRIPTS -> d.scripts.scriptStamps(ids)
            Tables.SCRIPT_LINES -> d.scripts.lineStamps(ids)
            else -> emptyList()
        }
        return stamps.associate { it.id to it.updatedAt }
    }

    override suspend fun awaitingMedia(table: String): List<Map<String, Any?>> {
        val spec = Tables.of(table) ?: return emptyList()
        if (spec.mediaFields.isEmpty()) return emptyList()
        val d = daos()
        val entities: List<Any> = when (table) {
            Tables.ITEMS -> d.items.awaitingMedia()
            Tables.RECORDINGS -> d.recordings.awaitingMedia()
            else -> emptyList()
        }
        return entities.map { spec.withId(Rows.of(it)) }
    }

    override suspend fun apply(table: String, rows: List<Map<String, Any?>>) {
        if (rows.isEmpty()) return
        val d = daos()
        when (table) {
            Tables.ITEMS -> d.items.upsertFromSync(rows.map { Rows.to(it, Item::class.java) })
            Tables.RECORDINGS -> d.recordings.upsertFromSync(rows.map { Rows.to(it, Recording::class.java) })
            Tables.ATTEMPTS -> d.attempts.upsertFromSync(rows.map { Rows.to(it, Attempt::class.java) })
            Tables.SCHEDULES -> d.schedules.upsertFromSync(rows.map { Rows.to(it, Schedule::class.java) })
            Tables.SESSIONS -> d.sessions.upsertFromSync(rows.map { Rows.to(it, Session::class.java) })
            Tables.ERROR_LOGS -> d.errorLogs.upsertFromSync(rows.map { Rows.to(it, ErrorLog::class.java) })
            Tables.SCRIPTS -> d.scripts.upsertScriptsFromSync(rows.map { Rows.to(it, Script::class.java) })
            Tables.SCRIPT_LINES -> d.scripts.upsertLinesFromSync(rows.map { Rows.to(it, ScriptLine::class.java) })
        }
    }
}
