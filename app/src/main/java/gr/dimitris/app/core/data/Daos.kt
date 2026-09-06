package gr.dimitris.app.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ItemCount(val itemId: String, val n: Int)

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
}

@Dao
interface RecordingDao {
    @Upsert suspend fun upsert(recording: Recording)
    @Query("SELECT * FROM recordings WHERE id = :id AND deleted = 0") suspend fun get(id: String): Recording?
    @Query("SELECT * FROM recordings WHERE itemId = :itemId AND who = :who AND style = :style AND deleted = 0 ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latestFor(itemId: String, who: Who, style: RecordingStyle): Recording?
    @Query("UPDATE recordings SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)
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
}

@Dao
interface ScheduleDao {
    @Upsert suspend fun upsert(schedule: Schedule)
    @Query("SELECT * FROM schedules WHERE itemId = :itemId AND module = :module AND deleted = 0") suspend fun get(itemId: String, module: ModuleId): Schedule?
    @Query("SELECT * FROM schedules WHERE module = :module AND deleted = 0 AND nextDueAt <= :now ORDER BY nextDueAt") suspend fun due(module: ModuleId, now: Long): List<Schedule>
    @Query("SELECT * FROM schedules WHERE module = :module AND deleted = 0") suspend fun all(module: ModuleId): List<Schedule>

    /**
     * How many distinct words have reached the last Leitner box: what the dashboard shows as
     * «Μαθημένες λέξεις». Counted by SQLite rather than by reading items × modules rows into memory
     * only to count them. Distinct *items*, not rows — a word learned in three modules is one word.
     */
    @Query("SELECT COUNT(DISTINCT itemId) FROM schedules WHERE deleted = 0 AND box >= :topBox")
    suspend fun masteredCount(topBox: Int): Int
}

@Dao
interface SessionDao {
    @Upsert suspend fun upsert(session: Session)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: String): Session?
    @Query("SELECT * FROM sessions WHERE deleted = 0 ORDER BY startedAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<Session>

    /** Sittings that *began* in the window: a session is counted on the day he sat down. */
    @Query("SELECT * FROM sessions WHERE deleted = 0 AND startedAt BETWEEN :from AND :to ORDER BY startedAt")
    suspend fun between(from: Long, to: Long): List<Session>
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
}

@Dao
interface ErrorLogDao {
    @Insert suspend fun insert(log: ErrorLog)
    @Query("SELECT * FROM error_logs WHERE deleted = 0 ORDER BY at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<ErrorLog>>
    @Query("SELECT * FROM error_logs WHERE deleted = 0 ORDER BY at DESC") suspend fun all(): List<ErrorLog>
    @Query("UPDATE error_logs SET deleted = 1, updatedAt = :now WHERE deleted = 0") suspend fun clearAll(now: Long)
}
