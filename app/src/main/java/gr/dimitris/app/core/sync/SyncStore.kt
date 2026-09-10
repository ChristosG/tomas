package gr.dimitris.app.core.sync

import gr.dimitris.app.core.data.Advice
import gr.dimitris.app.core.data.AdviceDao
import gr.dimitris.app.core.data.AppDatabase
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.AttemptDao
import gr.dimitris.app.core.data.ErrorLog
import gr.dimitris.app.core.data.ErrorLogDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemDao
import gr.dimitris.app.core.data.Note
import gr.dimitris.app.core.data.NoteDao
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

    /**
     * Writes rows another phone sent, exactly as they arrived. Never touches `updatedAt`.
     *
     * Returns the ids of rows this version of the app could not turn into a row of this table at
     * all — a column a later APK added without a default, a value of the wrong shape, something
     * hand-pushed with `curl`. Those are *not* written and never will be, so they are reported
     * rather than thrown: a permanent failure that came back as an exception would hold the pull
     * cursor in front of the page for ever and no sync would ever advance again. A failure of the
     * database itself still throws — that one is worth waiting for.
     */
    suspend fun apply(table: String, rows: List<Map<String, Any?>>): List<String>

    /**
     * Rows still holding a `media://` in a media column: a file that did not download when the row
     * arrived. The server will never offer the row again — the cursor has passed it — so every sync
     * asks for these itself.
     */
    suspend fun awaitingMedia(table: String): List<Map<String, Any?>>

    /**
     * Whether any row still alive anywhere points at [path]. Asked before a deletion pulled from
     * another phone takes the local file with it.
     *
     * Across every table that has a media column, not only the one the deletion came from: two rows
     * can name one file — a dialogue line re-saved hands its existing take back in — and a
     * photograph column holding a recordings path, which a row pushed by hand can do, is no reason
     * to delete a take a live row still plays.
     */
    suspend fun mediaStillUsed(path: String): Boolean
}

/** The nine DAOs the sync writes through. [of] takes them from the live database. */
class SyncDaos(
    val items: ItemDao,
    val recordings: RecordingDao,
    val attempts: AttemptDao,
    val schedules: ScheduleDao,
    val sessions: SessionDao,
    val errorLogs: ErrorLogDao,
    val scripts: ScriptDao,
    val advice: AdviceDao,
    val notes: NoteDao,
) {
    companion object {
        fun of(db: AppDatabase) = SyncDaos(
            db.items(), db.recordings(), db.attempts(), db.schedules(), db.sessions(), db.errorLogs(), db.scripts(),
            db.advice(), db.notes(),
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
            Tables.ADVICE -> d.advice.changedSince(since)
            Tables.NOTES -> d.notes.changedSince(since)
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
            Tables.ADVICE -> d.advice.stamps(ids)
            Tables.NOTES -> d.notes.stamps(ids)
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

    /**
     * Every media column in the schema: `recordings.path` and `items.imagePath`, matched on the
     * relative path this phone stores — which is what both columns hold once a row has landed
     * ([MediaRefs.incoming] relativizes them). The recordings side is asked first because it is the
     * one that answers on any real phone.
     */
    override suspend fun mediaStillUsed(path: String): Boolean {
        val d = daos()
        return d.recordings.activeWithPath(path) > 0 || d.items.activeWithImage(path) > 0
    }

    override suspend fun apply(table: String, rows: List<Map<String, Any?>>): List<String> {
        if (rows.isEmpty()) return emptyList()
        val spec = Tables.of(table) ?: return rows.mapNotNull { it["id"] as? String }
        // Converted first, one at a time, so a row this version cannot read is set aside instead of
        // taking the whole page — and the page's write below is then all database, no parsing.
        val unmappable = mutableListOf<String>()
        val entities = mutableListOf<Any>()
        for (row in rows) {
            // Checked before Gson, not after. Gson is happy to leave a column out and hand back an
            // entity with a null where a non-null Kotlin property should be; it is the generated
            // Room code that then throws, and by then it is a database failure rather than a bad
            // row. `README.md` §4's own `curl` example pushes exactly such a row.
            val entity = if (spec.missing(row).isEmpty()) runCatching { entityOf(table, row) }.getOrNull() else null
            if (entity == null) unmappable += spec.idOf(row) ?: "?" else entities += entity
        }
        if (entities.isNotEmpty()) write(table, entities)
        return unmappable
    }

    private fun entityOf(table: String, row: Map<String, Any?>): Any = when (table) {
        Tables.ITEMS -> Rows.to(row, Item::class.java)
        Tables.RECORDINGS -> Rows.to(row, Recording::class.java)
        Tables.ATTEMPTS -> Rows.to(row, Attempt::class.java)
        Tables.SCHEDULES -> Rows.to(row, Schedule::class.java)
        Tables.SESSIONS -> Rows.to(row, Session::class.java)
        Tables.ERROR_LOGS -> Rows.to(row, ErrorLog::class.java)
        Tables.SCRIPTS -> Rows.to(row, Script::class.java)
        Tables.SCRIPT_LINES -> Rows.to(row, ScriptLine::class.java)
        Tables.ADVICE -> Rows.to(row, Advice::class.java)
        Tables.NOTES -> Rows.to(row, Note::class.java)
        else -> error("unknown table $table")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun write(table: String, entities: List<Any>) {
        val d = daos()
        when (table) {
            Tables.ITEMS -> d.items.upsertFromSync(entities as List<Item>)
            Tables.RECORDINGS -> d.recordings.upsertFromSync(entities as List<Recording>)
            Tables.ATTEMPTS -> d.attempts.upsertFromSync(entities as List<Attempt>)
            Tables.SCHEDULES -> d.schedules.upsertFromSync(entities as List<Schedule>)
            Tables.SESSIONS -> d.sessions.upsertFromSync(entities as List<Session>)
            Tables.ERROR_LOGS -> d.errorLogs.upsertFromSync(entities as List<ErrorLog>)
            Tables.SCRIPTS -> d.scripts.upsertScriptsFromSync(entities as List<Script>)
            Tables.SCRIPT_LINES -> d.scripts.upsertLinesFromSync(entities as List<ScriptLine>)
            Tables.ADVICE -> d.advice.upsertFromSync(entities as List<Advice>)
            Tables.NOTES -> d.notes.upsertFromSync(entities as List<Note>)
        }
    }
}
