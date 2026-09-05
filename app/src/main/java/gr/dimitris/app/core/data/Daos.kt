package gr.dimitris.app.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ItemCount(val itemId: String, val n: Int)

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
    @Query("SELECT COUNT(*) FROM items WHERE deleted = 0") suspend fun countActive(): Int
    @Query("UPDATE items SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)
}

@Dao
interface RecordingDao {
    @Upsert suspend fun upsert(recording: Recording)
    @Query("SELECT * FROM recordings WHERE id = :id AND deleted = 0") suspend fun get(id: String): Recording?
    @Query("SELECT * FROM recordings WHERE itemId = :itemId AND who = :who AND deleted = 0 ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latestFor(itemId: String, who: Who): Recording?
    @Query("UPDATE recordings SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)
}

@Dao
interface AttemptDao {
    @Insert suspend fun insert(attempt: Attempt)
    @Query("SELECT * FROM attempts WHERE deleted = 0 AND startedAt >= :since ORDER BY startedAt") suspend fun since(since: Long): List<Attempt>
    @Query("SELECT COUNT(*) FROM attempts WHERE deleted = 0 AND itemId = :itemId AND module = :module")
    suspend fun countFor(itemId: String, module: ModuleId): Int
    @Query("SELECT itemId, COUNT(*) AS n FROM attempts WHERE deleted = 0 AND module = :module GROUP BY itemId ORDER BY n DESC LIMIT :limit")
    suspend fun mostUsed(module: ModuleId, limit: Int): List<ItemCount>
}

@Dao
interface ScheduleDao {
    @Upsert suspend fun upsert(schedule: Schedule)
    @Query("SELECT * FROM schedules WHERE itemId = :itemId AND module = :module AND deleted = 0") suspend fun get(itemId: String, module: ModuleId): Schedule?
    @Query("SELECT * FROM schedules WHERE module = :module AND deleted = 0 AND nextDueAt <= :now ORDER BY nextDueAt") suspend fun due(module: ModuleId, now: Long): List<Schedule>
    @Query("SELECT * FROM schedules WHERE module = :module AND deleted = 0") suspend fun all(module: ModuleId): List<Schedule>
}

@Dao
interface SessionDao {
    @Upsert suspend fun upsert(session: Session)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: String): Session?
    @Query("SELECT * FROM sessions WHERE deleted = 0 ORDER BY startedAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<Session>
}

@Dao
interface ErrorLogDao {
    @Insert suspend fun insert(log: ErrorLog)
    @Query("SELECT * FROM error_logs WHERE deleted = 0 ORDER BY at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<ErrorLog>>
    @Query("SELECT * FROM error_logs WHERE deleted = 0 ORDER BY at DESC") suspend fun all(): List<ErrorLog>
    @Query("UPDATE error_logs SET deleted = 1, updatedAt = :now WHERE deleted = 0") suspend fun clearAll(now: Long)
}
