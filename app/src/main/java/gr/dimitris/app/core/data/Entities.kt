package gr.dimitris.app.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun now(): Long = System.currentTimeMillis()
fun newId(): String = UUID.randomUUID().toString()

enum class ItemKind { WORD, PHRASE, NUMBER, SCRIPT_LINE }

enum class Category(val greek: String) {
    QUICK("Γρήγορα"),
    FOOD("Φαγητό & ποτό"),
    PLACES("Μέρη"),
    PEOPLE("Άνθρωποι"),
    VERBS("Ρήματα"),
    FEELINGS("Συναισθήματα"),
    BODY("Σώμα"),
    NUMBERS("Αριθμοί"),
    THINGS("Πράγματα"),
    TIME("Χρόνος"),
    CUSTOM("Δικά μας"),
}

enum class Source { SEED, CAREGIVER }
enum class Who { DIMITRIS, CAREGIVER }
enum class ModuleId { TALKBOARD, WORDCOACH, NUMBERS, SINGSAY, SCRIPTS, SENTENCES, TRACE, ARCADE }
enum class Outcome { CORRECT, ASSISTED, SKIPPED }

/**
 * The unit of everything: a word, phrase, number or script line, with its picture and model voice.
 *
 * Every table carries an index on updatedAt: phase 10 syncs by "rows changed since X", and the
 * caregiver screens already sort and filter by it.
 */
@Entity(tableName = "items", indices = [Index("category"), Index("deleted"), Index("source"), Index("updatedAt")])
data class Item(
    @PrimaryKey val id: String = newId(),
    val text: String,
    val kind: ItemKind = ItemKind.WORD,
    val category: Category = Category.CUSTOM,
    val imagePath: String? = null,
    val modelRecordingId: String? = null,
    /** Derived by Greek.firstSound on save. */
    val firstSound: String = "",
    /** Effective first syllable: override if set, else Syllabifier result, else null (cue level 2 is skipped). */
    val firstSyllable: String? = null,
    val firstSyllableOverride: String? = null,
    val source: Source = Source.CAREGIVER,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

@Entity(tableName = "recordings", indices = [Index("itemId"), Index("updatedAt")])
data class Recording(
    @PrimaryKey val id: String = newId(),
    val itemId: String,
    val path: String,
    val who: Who,
    val durationMs: Long,
    val recordedAt: Long = now(),
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

/** Append-only. One row per try in any module. */
@Entity(tableName = "attempts", indices = [Index("itemId"), Index("module"), Index("startedAt"), Index("updatedAt")])
data class Attempt(
    @PrimaryKey val id: String = newId(),
    val itemId: String,
    val module: ModuleId,
    val sessionId: String? = null,
    val startedAt: Long,
    val durationMs: Long,
    val outcome: Outcome,
    /** 0..4 for cue-ladder modules, null where the ladder does not apply (talk board). */
    val cueLevel: Int? = null,
    val selfRecordingId: String? = null,
    /** Module-specific JSON. "{}" when there is nothing to say. */
    val detail: String = "{}",
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

/** Leitner spaced repetition state per (item, module). Filled in by phase 2. */
@Entity(tableName = "schedules", primaryKeys = ["itemId", "module"], indices = [Index("updatedAt")])
data class Schedule(
    val itemId: String,
    val module: ModuleId,
    val box: Int = 1,
    val nextDueAt: Long,
    val lastSeenAt: Long? = null,
    val streak: Int = 0,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

@Entity(tableName = "sessions", indices = [Index("updatedAt")])
data class Session(
    @PrimaryKey val id: String = newId(),
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Comma-separated ModuleId names, in planned order. */
    val plannedModules: String,
    val plannedItemCount: Int,
    val completedItemCount: Int = 0,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

/** Append-only. Dimitris cannot report bugs, so the app keeps its own list for caregivers. */
@Entity(tableName = "error_logs", indices = [Index("updatedAt")])
data class ErrorLog(
    @PrimaryKey val id: String = newId(),
    val at: Long = now(),
    val where_: String,
    val message: String,
    val stack: String,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
    companion object {
        const val MAX_STACK = 4000
        fun from(where: String, e: Throwable): ErrorLog = ErrorLog(
            where_ = where,
            message = (e.message ?: e.javaClass.simpleName).take(500),
            stack = e.stackTraceToString().take(MAX_STACK),
        )
    }
}
