package gr.dimitris.app.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ItemCount(val itemId: String, val n: Int)

/**
 * A row's sync id and how new it is — everything the merge needs to decide whether an arriving row
 * wins, without reading whole rows the phone is going to throw away. See
 * [gr.dimitris.app.core.sync.Merge].
 */
data class RowStamp(val id: String, val updatedAt: Long)

/**
 * When a module was last practised: the newest attempt row it wrote. It is what the daily session
 * rotates on (see [gr.dimitris.app.core.scheduler.ModuleRotation]), so it is read from the rows
 * themselves rather than from a counter that could disagree with them.
 */
data class ModuleUse(val module: ModuleId, val lastAt: Long)

@Dao
interface ItemDao {
    @Upsert suspend fun upsert(item: Item)
    @Upsert suspend fun upsertAll(items: List<Item>)
    @Query("SELECT * FROM items WHERE id = :id") suspend fun get(id: String): Item?
    @Query("SELECT * FROM items WHERE deleted = 0 ORDER BY category, text") fun observeActive(): Flow<List<Item>>
    @Query("SELECT * FROM items WHERE deleted = 0 AND category = :category ORDER BY text")
    fun observeByCategory(category: Category): Flow<List<Item>>
    @Query("SELECT * FROM items WHERE deleted = 0 AND kind IN (:kinds)") suspend fun activeOfKinds(kinds: List<ItemKind>): List<Item>
    @Query("SELECT * FROM items WHERE deleted = 0 AND source = :source") suspend fun activeOfSource(source: Source): List<Item>
    @Query("SELECT * FROM items WHERE deleted = 0") suspend fun allActive(): List<Item>

    /**
     * Every row, deleted ones included. Only the seed importer wants this: a word the caregiver
     * removed has to keep counting as "already on this device", or the next version bump hands it
     * back to her and she has to delete it again.
     */
    @Query("SELECT * FROM items") suspend fun all(): List<Item>
    @Query("SELECT COUNT(*) FROM items WHERE deleted = 0") suspend fun countActive(): Int
    @Query("UPDATE items SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)
    @Query("SELECT * FROM items WHERE deleted = 0 AND pinned = 1 ORDER BY text") fun observePinned(): Flow<List<Item>>
    @Query("SELECT * FROM items WHERE deleted = 0 AND id IN (:ids)") suspend fun byIds(ids: List<String>): List<Item>
    @Query("SELECT * FROM items WHERE deleted = 0 AND priceCents IS NOT NULL") suspend fun withPrices(): List<Item>

    // Sync (phase 10). Deleted rows are included on purpose: a word the caregiver removed here has
    // to be removed on the other phones too, and a soft delete is an ordinary row change.
    @Query("SELECT * FROM items WHERE updatedAt > :since ORDER BY updatedAt") suspend fun changedSince(since: Long): List<Item>
    @Query("SELECT id, updatedAt FROM items WHERE id IN (:ids)") suspend fun stamps(ids: List<String>): List<RowStamp>

    /**
     * Rows exactly as another phone wrote them. Not [upsert]: nothing here may touch `updatedAt`,
     * which is the merge's whole basis — rewriting it would make every pulled row look newer than
     * the copy it came from and the two phones would push it back and forth for ever.
     */
    @Upsert suspend fun upsertFromSync(rows: List<Item>)
}

@Dao
interface RecordingDao {
    @Upsert suspend fun upsert(recording: Recording)
    @Query("SELECT * FROM recordings WHERE id = :id AND deleted = 0") suspend fun get(id: String): Recording?
    @Query("SELECT * FROM recordings WHERE itemId = :itemId AND who = :who AND style = :style AND deleted = 0 ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latestFor(itemId: String, who: Who, style: RecordingStyle): Recording?
    @Query("UPDATE recordings SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)

    @Query("SELECT * FROM recordings WHERE updatedAt > :since ORDER BY updatedAt") suspend fun changedSince(since: Long): List<Recording>
    @Query("SELECT id, updatedAt FROM recordings WHERE id IN (:ids)") suspend fun stamps(ids: List<String>): List<RowStamp>
    @Upsert suspend fun upsertFromSync(rows: List<Recording>)
}

@Dao
interface AttemptDao {
    @Insert suspend fun insert(attempt: Attempt)
    @Query("SELECT * FROM attempts WHERE deleted = 0 AND startedAt >= :since ORDER BY startedAt") suspend fun since(since: Long): List<Attempt>

    /** One window of history, for the progress dashboard. Inclusive at both ends. */
    @Query("SELECT * FROM attempts WHERE deleted = 0 AND startedAt BETWEEN :from AND :to ORDER BY startedAt")
    suspend fun between(from: Long, to: Long): List<Attempt>

    @Query("SELECT COUNT(*) FROM attempts WHERE deleted = 0 AND itemId = :itemId AND module = :module")
    suspend fun countFor(itemId: String, module: ModuleId): Int
    /** A Flow, so favourites re-rank themselves the moment an attempt is inserted. */
    @Query("SELECT itemId, COUNT(*) AS n FROM attempts WHERE deleted = 0 AND module = :module GROUP BY itemId ORDER BY n DESC LIMIT :limit")
    fun mostUsed(module: ModuleId, limit: Int): Flow<List<ItemCount>>

    /**
     * The last time each module was really practised. A module with no rows at all is simply absent,
     * which is what the rotation reads as "never done" — and never done is what goes first.
     *
     * [skipped] rows are left out, and the caller passes [Outcome.SKIPPED]: a module he opened and
     * passed straight through is a module he did not do, and counting it would send it to the back
     * of the queue for as long as one he had worked at.
     */
    @Query("SELECT module, MAX(startedAt) AS lastAt FROM attempts WHERE deleted = 0 AND outcome != :skipped GROUP BY module")
    suspend fun lastUsePerModule(skipped: Outcome): List<ModuleUse>

    @Query("SELECT * FROM attempts WHERE updatedAt > :since ORDER BY updatedAt") suspend fun changedSince(since: Long): List<Attempt>

    /**
     * Append-only, so the database enforces the merge rule the server also holds: the first row
     * stored for an id wins and a second one is dropped. An attempt is something that happened.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun upsertFromSync(rows: List<Attempt>)
}

@Dao
interface ScheduleDao {
    @Upsert suspend fun upsert(schedule: Schedule)
    @Query("SELECT * FROM schedules WHERE itemId = :itemId AND module = :module AND deleted = 0") suspend fun get(itemId: String, module: ModuleId): Schedule?
    @Query("SELECT * FROM schedules WHERE module = :module AND deleted = 0 AND nextDueAt <= :now ORDER BY nextDueAt") suspend fun due(module: ModuleId, now: Long): List<Schedule>
    @Query("SELECT * FROM schedules WHERE module = :module AND deleted = 0") suspend fun all(module: ModuleId): List<Schedule>

    /**
     * How many distinct **words** have reached the last Leitner box: what the dashboard shows as
     * «Μαθημένες λέξεις». Counted by SQLite rather than by reading items × modules rows into memory
     * only to count them.
     *
     * The join is the point. `schedules.itemId` is not one namespace: the word coach and sing-say
     * store an item's id, but script practice stores the *script's* id
     * ([gr.dimitris.app.modules.scripts.ScriptsViewModel] records against `scriptId`), and both are
     * UUIDs, so a mastered dialogue used to arrive on the dashboard as a mastered word. Only rows
     * that point at a live WORD or PHRASE count — distinct items, not rows, so a word learned in
     * three modules is still one word.
     */
    @Query(
        "SELECT COUNT(DISTINCT s.itemId) FROM schedules s JOIN items i ON i.id = s.itemId " +
            "WHERE s.deleted = 0 AND i.deleted = 0 AND s.box >= :topBox AND i.kind IN ('WORD', 'PHRASE')"
    )
    suspend fun masteredCount(topBox: Int): Int

    @Query("SELECT * FROM schedules WHERE updatedAt > :since ORDER BY updatedAt") suspend fun changedSince(since: Long): List<Schedule>

    /** A schedule has no id column: (itemId, module) is the key, and `"$itemId:$module"` is its sync id. */
    @Query("SELECT itemId || ':' || module AS id, updatedAt FROM schedules WHERE itemId || ':' || module IN (:ids)")
    suspend fun stamps(ids: List<String>): List<RowStamp>

    @Upsert suspend fun upsertFromSync(rows: List<Schedule>)
}

@Dao
interface SessionDao {
    @Upsert suspend fun upsert(session: Session)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: String): Session?
    @Query("SELECT * FROM sessions WHERE deleted = 0 ORDER BY startedAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<Session>

    /** Sittings that *began* in the window: a session is counted on the day he sat down. */
    @Query("SELECT * FROM sessions WHERE deleted = 0 AND startedAt BETWEEN :from AND :to ORDER BY startedAt")
    suspend fun between(from: Long, to: Long): List<Session>

    @Query("SELECT * FROM sessions WHERE updatedAt > :since ORDER BY updatedAt") suspend fun changedSince(since: Long): List<Session>
    @Query("SELECT id, updatedAt FROM sessions WHERE id IN (:ids)") suspend fun stamps(ids: List<String>): List<RowStamp>
    @Upsert suspend fun upsertFromSync(rows: List<Session>)
}

@Dao
interface ScriptDao {
    @Upsert suspend fun upsertScript(script: Script)
    @Upsert suspend fun upsertLines(lines: List<ScriptLine>)
    @Query("SELECT * FROM scripts WHERE deleted = 0 ORDER BY title") fun observeScripts(): Flow<List<Script>>
    @Query("SELECT * FROM scripts WHERE deleted = 0 ORDER BY title") suspend fun activeScripts(): List<Script>

    /**
     * Every dialogue, deleted ones included — the seed importer's "already on this device" set. A
     * dialogue the caregiver removed must not come back on the next version bump.
     */
    @Query("SELECT * FROM scripts") suspend fun allScripts(): List<Script>

    @Query("SELECT * FROM scripts WHERE id = :id") suspend fun get(id: String): Script?
    @Query("SELECT * FROM script_lines WHERE scriptId = :scriptId AND deleted = 0 ORDER BY position") suspend fun linesFor(scriptId: String): List<ScriptLine>

    /**
     * The live line an item belongs to, or null when the item is not a script line. This is how a
     * session item finds its script: nothing keeps "the script we are practising" anywhere, so two
     * screens can never disagree about which one it is.
     */
    @Query("SELECT * FROM script_lines WHERE itemId = :itemId AND deleted = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lineOfItem(itemId: String): ScriptLine?

    @Query("UPDATE scripts SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDeleteScript(id: String, now: Long)
    @Query("UPDATE script_lines SET deleted = 1, updatedAt = :now WHERE scriptId = :scriptId AND deleted = 0") suspend fun softDeleteLinesOf(scriptId: String, now: Long)

    @Query("SELECT * FROM scripts WHERE updatedAt > :since ORDER BY updatedAt") suspend fun scriptsChangedSince(since: Long): List<Script>
    @Query("SELECT id, updatedAt FROM scripts WHERE id IN (:ids)") suspend fun scriptStamps(ids: List<String>): List<RowStamp>
    @Upsert suspend fun upsertScriptsFromSync(rows: List<Script>)

    // Not append-only: phase 5 re-saves a dialogue by soft-deleting its lines and writing new ones,
    // so a line's `deleted` flips and its `updatedAt` moves. Last-write-wins, like every other table.
    @Query("SELECT * FROM script_lines WHERE updatedAt > :since ORDER BY updatedAt") suspend fun linesChangedSince(since: Long): List<ScriptLine>
    @Query("SELECT id, updatedAt FROM script_lines WHERE id IN (:ids)") suspend fun lineStamps(ids: List<String>): List<RowStamp>
    @Upsert suspend fun upsertLinesFromSync(rows: List<ScriptLine>)
}

@Dao
interface ErrorLogDao {
    @Insert suspend fun insert(log: ErrorLog)
    @Query("SELECT * FROM error_logs WHERE deleted = 0 ORDER BY at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<ErrorLog>>
    @Query("SELECT * FROM error_logs WHERE deleted = 0 ORDER BY at DESC") suspend fun all(): List<ErrorLog>
    @Query("UPDATE error_logs SET deleted = 1, updatedAt = :now WHERE deleted = 0") suspend fun clearAll(now: Long)

    @Query("SELECT * FROM error_logs WHERE updatedAt > :since ORDER BY updatedAt") suspend fun changedSince(since: Long): List<ErrorLog>

    /** Append-only, like [AttemptDao.upsertFromSync]: a logged error is a fact, not a value. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun upsertFromSync(rows: List<ErrorLog>)
}
