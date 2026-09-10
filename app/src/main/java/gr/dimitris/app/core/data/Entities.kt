package gr.dimitris.app.core.data

import androidx.room.ColumnInfo
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

/**
 * How a recording was made. A sung take is the caregiver singing the phrase on its melody, for
 * "Τραγούδα και πες το"; it is never the model voice the talk board or the word coach plays.
 */
enum class RecordingStyle { SPOKEN, SUNG }
/**
 * Every module that can write an attempt row, plus the talk board, which writes them for his own
 * speech. Stored by *name* everywhere it is stored — Room's enum converter, the sync wire, the
 * preference keys in [gr.dimitris.app.core.settings.Settings] — so a new one may be appended without
 * moving anything that already exists.
 *
 * `SQL` is phase 13's: Dimitris was a programmer, his father runs a software company, and he still
 * does very basic SQL exercises. It is a module on exactly the same footing as the rest — it rotates
 * into the daily sitting, it has its own row of five dots, and it writes one attempt per puzzle.
 */
enum class ModuleId { TALKBOARD, WORDCOACH, NUMBERS, SINGSAY, SCRIPTS, SENTENCES, TRACE, ARCADE, SQL }
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
    /** Caregiver-pinned to the talk board favourites. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Real price in cents, for the euro exercises (caregivers copy it from Wolt). */
    val priceCents: Int? = null,
    /**
     * How hard this word is, 1 to 5, against the dot row of
     * [gr.dimitris.app.core.difficulty.Difficulty] — phase 13's answer to "the app is too easy".
     *
     * Until now the only grading the vocabulary had was its [kind]: a word, or a phrase. That is two
     * steps for five dots, and Dimitris, who is stronger than the app assumed, spent three of them
     * on the same two hundred everyday words. A tier is what the *word* says about its own
     * difficulty: 1 «νερό», 2 «θέλω καφέ», 3 «λογαριασμός», 4 «αποφασίζω», 5 «ελευθερία». A dot
     * admits every tier at or below it ([gr.dimitris.app.core.difficulty.Difficulty.wordCoachTier]),
     * so the easy words never leave the pool — they are the easy ends of the sandwich.
     *
     * Defaulted in the database as well as in Kotlin: every word already on a phone, hers as much as
     * ours, becomes tier 1 and stays exactly as reachable as it was. Read it through
     * [gr.dimitris.app.core.difficulty.Difficulty.clamp] — a row pushed by a phone that has never
     * heard of tiers arrives carrying a 0.
     */
    @ColumnInfo(defaultValue = "1") val tier: Int = DEFAULT_TIER,
    /**
     * What gender this noun is — `"M"`, `"F"`, `"N"` — or null when nobody has said.
     *
     * The sentence builder needs it for every noun it puts an article in front of, and until now it
     * had to *guess* from the ending ([gr.dimitris.app.core.greek.nounForm]): «-ο» and «-ι» neuter,
     * «-η» feminine, and a hand-written list for «γάλα», «πόρτα» and everything else whose ending
     * lies. A word a caregiver adds is not in that list, so at levels 5–8 it was simply never
     * offered — the article levels drew on the seed alone. A column she can set is what lets her own
     * words in, and it wins over the guess wherever it is filled in.
     *
     * Nullable on purpose, and null nearly everywhere: a verb, an adjective, a phrase and a number
     * have no gender, and a noun nobody has graded falls back on the inference exactly as before.
     */
    val gender: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
    companion object {
        /** The easiest tier, and what every word written before phase 13 is. */
        const val DEFAULT_TIER = 1

        /** The three values [gender] may carry. Read them with [gr.dimitris.app.core.greek.Gender.of]. */
        const val MASCULINE = "M"
        const val FEMININE = "F"
        const val NEUTER = "N"
    }
}

@Entity(tableName = "recordings", indices = [Index("itemId"), Index("updatedAt")])
data class Recording(
    @PrimaryKey val id: String = newId(),
    val itemId: String,
    val path: String,
    val who: Who,
    /** Defaulted in SQL as well as in Kotlin, so the rows written before phase 4 stay spoken ones. */
    @ColumnInfo(defaultValue = "SPOKEN") val style: RecordingStyle = RecordingStyle.SPOKEN,
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
    /**
     * 0..4 for cue-ladder modules, null where the ladder does not apply — every talk-board tap, which
     * is him speaking rather than him being marked.
     *
     * One talk-board row does carry a level: the sentence expansion of spec §13
     * ([gr.dimitris.app.modules.talkboard.EXPAND_ITEM]) is written at
     * [gr.dimitris.app.modules.talkboard.EXPAND_CUE_LEVEL], because the phone said the sentence to
     * him before he repeated it. So a mean taken over this column for the talk board is a mean over
     * expansions alone — see [gr.dimitris.app.caregiver.progress.ProgressStats.moduleHistory].
     */
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

// startedAt is indexed because the progress dashboard reads a window of sittings by it
// (SessionDao.between); without it every dashboard open scans the table.
@Entity(tableName = "sessions", indices = [Index("startedAt"), Index("updatedAt")])
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

/** Who says a line in a rehearsed dialogue. OTHER is the waiter, the father, the physio, whoever. */
enum class Speaker { OTHER, DIMITRIS }

/**
 * A rehearsed dialogue ("Στην καφετέρια"). Its lines live in script_lines and point at
 * SCRIPT_LINE items, so a line gets a picture, a model voice and a Leitner box like anything else.
 */
@Entity(tableName = "scripts", indices = [Index("updatedAt")])
data class Script(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val source: Source = Source.CAREGIVER,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

/**
 * One turn of a dialogue. The index on itemId is what lets a session item find its way back to the
 * script it came from, so nothing has to remember "the script we are in" between screens.
 *
 * [tier] and [intent] are phase 12's two columns, and both are about the same thing: a dialogue that
 * can be *hard*. Dimitris told us the app is too easy, and until now a dialogue carried nothing that
 * said how much it asked of him.
 */
@Entity(tableName = "script_lines", indices = [Index("scriptId"), Index("itemId"), Index("updatedAt")])
data class ScriptLine(
    @PrimaryKey val id: String = newId(),
    val scriptId: String,
    val position: Int,
    val speaker: Speaker,
    val itemId: String,
    /**
     * How hard this turn is, 1 to 5, against the dot row of
     * [gr.dimitris.app.core.difficulty.Difficulty]. A dialogue is as hard as its hardest line, and a
     * dot admits every tier at or below it.
     *
     * Defaulted in the database as well as in Kotlin, because a phone upgrading from v7 has rows
     * that predate the whole idea: they become tier 1, the easiest, which is where the six dialogues
     * the app shipped with belong anyway. Read it through [gr.dimitris.app.core.difficulty.Difficulty.clamp]
     * — a row that arrived over sync from a phone that has never heard of tiers carries a 0.
     */
    @ColumnInfo(defaultValue = "1") val tier: Int = DEFAULT_TIER,
    /**
     * What a good answer to this turn has to convey, in Greek, for one of *his* lines: «λέει τι θέλει
     * και πόσο». The line itself is only ever a sample answer (spec §13) — there is no single right
     * reply to an open question — so this is what the caregiver writes down when she wants to say
     * what she is really after, and it goes into the attempt row so a later reader knows what the
     * turn was asking for.
     *
     * Null everywhere else: the other person's lines are said, not judged.
     */
    val intent: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
    companion object {
        /** The easiest tier, and what every line written before phase 12 is. */
        const val DEFAULT_TIER = 1
    }
}

/**
 * One answer from Claude, kept for ever.
 *
 * Chris asked for the advisor to *remember*: "even a vector DB if needed". It does not need one —
 * these are tables, and a table is exactly what a language model reads best. Every advice is stored
 * whole: the report that was sent ([report]), both halves of what came back, and the focus it
 * chose, so the next question can be asked as "here is what you said last time, say what changed".
 *
 * [focusJson] is the raw `## Εστίαση` object as the model wrote it, not a parsed one. A word it
 * named that is not in the vocabulary today may be in it next week — a caregiver adds words all the
 * time — so the filtering happens on every read, against the items that exist then. See
 * [gr.dimitris.app.caregiver.insights.Focus].
 */
@Entity(tableName = "advice", indices = [Index("updatedAt")])
data class Advice(
    @PrimaryKey val id: String = newId(),
    /** When it was asked for. The report's «Προηγούμενες συμβουλές» is ordered on it. */
    val at: Long = now(),
    val model: String,
    /** Exactly the text that went out, so what the answer was based on is never in doubt. */
    val report: String,
    val caregivers: String,
    val dimitris: String,
    /** `{"items":[…],"sounds":[…],"modules":[…],"levels":{…},"why":"…"}`, or "" when there was none. */
    val focusJson: String = "",
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

/**
 * Something a caregiver noticed, in their own words.
 *
 * The numbers say how many exercises he did; they cannot say that he said «καλημέρα» to the
 * neighbour on his own, or that he was tired all week because of the dentist. That is what the
 * people around him know and nothing else in this database records, so it goes to Claude with
 * everything else — and it syncs, because Chris and his father each see a different half of his
 * week.
 *
 * [author] is the device role that wrote it ([gr.dimitris.app.core.settings.DeviceRole]), so two
 * phones' notes stay told apart after they meet.
 */
@Entity(tableName = "notes", indices = [Index("updatedAt")])
data class Note(
    @PrimaryKey val id: String = newId(),
    val at: Long = now(),
    val text: String,
    val author: String,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
    companion object {
        /** A note is a note, not a chapter. Long enough for a paragraph about a good afternoon. */
        const val MAX_TEXT = 2_000
    }
}
