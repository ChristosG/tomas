package gr.dimitris.app.modules.sql

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * One answer: the column names and the cells, every cell as a string.
 *
 * Strings and not values, because a result is a thing on a screen and a thing to be compared with
 * another result, and both of those want the text a cursor hands back. [truncated] says the answer
 * was longer than [MAX_ROWS] and was cut — a fact the screen says out loud rather than quietly
 * showing him half an answer.
 */
data class SqlResult(
    val columns: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean = false,
) {
    val empty: Boolean get() = rows.isEmpty()

    companion object {
        /** How many rows ever come back. A beginner's query over twelve rows cannot want more. */
        const val MAX_ROWS = 50

        /**
         * Whether two answers are the same answer.
         *
         * The column *names* are not compared. `SELECT name` and `SELECT users.name` are the same
         * question asked two ways, and SQLite names their columns differently; insisting on the name
         * would refuse a query of his that is right.
         *
         * [ordered] is true only when the query he was being asked for had an `ORDER BY` in it. Then
         * the order *is* the answer and two results that hold the same rows in a different order are
         * two different answers — which is the whole point of having written the `ORDER BY`.
         */
        fun same(a: SqlResult, b: SqlResult, ordered: Boolean): Boolean {
            if (a.columns.size != b.columns.size) return false
            if (a.rows.size != b.rows.size) return false
            return if (ordered) a.rows == b.rows
            else a.rows.sortedWith(ROWS) == b.rows.sortedWith(ROWS)
        }

        /** A total order over rows, so "the same rows in some order" is one comparison. */
        private val ROWS = Comparator<List<String>> { x, y ->
            val n = minOf(x.size, y.size)
            for (i in 0 until n) {
                val c = x[i].compareTo(y[i])
                if (c != 0) return@Comparator c
            }
            x.size - y.size
        }
    }
}

/** What came of running one query. */
sealed interface SqlOutcome {
    /** It ran. [ms] is how long SQLite took, which goes into the attempt row. */
    data class Rows(val result: SqlResult, val ms: Long) : SqlOutcome

    /**
     * It did not. [greek] is the one line he reads; [english] is SQLite's own message, shown small
     * underneath — he was a programmer, and «near "FORM": syntax error» is the most useful sentence
     * on the screen even though nothing in this app is otherwise in English.
     */
    data class Refused(val greek: String, val english: String? = null) : SqlOutcome
}

/**
 * Whether a statement is one this module will run at all, answered before SQLite ever sees it.
 *
 * Pure, so `SqlGuardTest` can argue with every rule on the JVM. The rules are small and deliberately
 * blunt: **one statement, and it starts with SELECT.** Everything a beginner wants to write is a
 * SELECT, and everything else — a second statement after a semicolon, `PRAGMA`, `ATTACH`, a `WITH`
 * with an `INSERT` hiding in it — is refused by name rather than by hoping the database is harmless.
 *
 * The database *is* harmless, as it happens: it is built fresh in memory per sitting out of
 * [SqlTables] and thrown away, and there is nothing in it but four tables of his own practice and a
 * textbook. The guard is not there to protect the data. It is there so that a man typing in a
 * language he half remembers gets one clear Greek sentence back instead of a stack trace, and so
 * that the one thing this module must never do — teach him that `DELETE` is a thing that works here
 * — cannot happen by accident.
 */
object SqlGuard {
    /** Nothing typed at all. */
    const val EMPTY = "Γράψε μια ερώτηση."

    /** Something that is not a question: an `INSERT`, a `PRAGMA`, a word we do not run. */
    const val ONLY_SELECT = "Μόνο SELECT εδώ."

    /** Two statements, or one and a half. */
    const val ONE_AT_A_TIME = "Μία ερώτηση κάθε φορά."

    /**
     * Words that are never run here, whole-word. `REPLACE` is in the list as a statement and not as
     * the scalar function of the same name: `SELECT replace(name, 'α', 'β')` is a fine thing to write
     * and the list costs him that one function. A beginner's first week does not need it, and the
     * alternative is a parser.
     */
    val FORBIDDEN = listOf(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "ATTACH", "DETACH",
        "PRAGMA", "VACUUM", "REINDEX", "REPLACE", "TRIGGER", "BEGIN", "COMMIT", "ROLLBACK",
        "ANALYZE", "EXPLAIN", "WITH",
    )

    /** The statement with its comments taken out, which is what the other rules are asked about. */
    fun strip(sql: String): String {
        val out = StringBuilder(sql.length)
        var at = 0
        while (at < sql.length) {
            when {
                sql.startsWith("--", at) -> {
                    val end = sql.indexOf('\n', at)
                    if (end < 0) return out.toString().trim()
                    at = end
                }
                sql.startsWith("/*", at) -> {
                    val end = sql.indexOf("*/", at + 2)
                    if (end < 0) return out.toString().trim()
                    at = end + 2
                    // A comment between two words is a space, not nothing: `SELECT/*x*/name` is two
                    // words and must not become one.
                    out.append(' ')
                }
                // A quoted string is untouched: `'--'` is a value, not the start of a comment, and
                // so is a word of his own vocabulary with a dash in it.
                sql[at] == '\'' -> {
                    val end = sql.indexOf('\'', at + 1)
                    if (end < 0) { out.append(sql, at, sql.length); return out.toString().trim() }
                    out.append(sql, at, end + 1)
                    at = end + 1
                }
                else -> { out.append(sql[at]); at++ }
            }
        }
        return out.toString().trim()
    }

    /**
     * The Greek refusal, or null when the statement may run.
     *
     * Order matters: "you wrote nothing" before "that is not a SELECT", and "one at a time" before
     * the word list, so `SELECT 1; DROP TABLE users` is answered with the honest reason.
     */
    fun problem(sql: String): String? {
        val clean = strip(sql).trim().trimEnd(';', ' ', '\t', '\n', '\r')
        if (clean.isBlank()) return EMPTY
        if (';' in withoutStrings(clean)) return ONE_AT_A_TIME
        val words = WORD.findAll(withoutStrings(clean)).map { it.value.uppercase() }.toList()
        if (words.firstOrNull() != "SELECT") return ONLY_SELECT
        if (words.any { it in FORBIDDEN }) return ONLY_SELECT
        return null
    }

    /** The statement it will actually run: comments gone, the trailing semicolon gone. */
    fun statement(sql: String): String = strip(sql).trim().trimEnd(';', ' ', '\t', '\n', '\r')

    /**
     * The statement with every string literal blanked out, so the word rules never read a *value*.
     * Without it a word of his own vocabulary — one of Dimitris' cards is «παγωτό», but one could as
     * easily be «delete» — would refuse his own query.
     */
    private fun withoutStrings(sql: String): String {
        val out = StringBuilder(sql.length)
        var at = 0
        while (at < sql.length) {
            if (sql[at] == '\'') {
                val end = sql.indexOf('\'', at + 1)
                out.append("''")
                if (end < 0) return out.toString()
                at = end + 1
            } else {
                out.append(sql[at]); at++
            }
        }
        return out.toString()
    }

    /** A word, in any alphabet: Greek identifiers are identifiers. */
    private val WORD = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
}

/**
 * The one place a query is really run.
 *
 * A fresh in-memory database per sitting, built on [Dispatchers.IO] out of [SqlTables] and closed on
 * the way out. In memory because there is nothing here worth keeping: the tables are a snapshot of
 * his own practice, rebuilt every time he opens the module, and a file on disk would only be one
 * more thing a backup had to know about.
 *
 * Three promises, in the order they matter:
 *
 * * **It never crashes the screen.** Everything comes back as an [SqlOutcome]; a syntax error is a
 *   Greek line with SQLite's English underneath it, and so is a query that takes too long.
 * * **It never runs anything but a SELECT** ([SqlGuard]).
 * * **It never hangs.** Two seconds, enforced with a [CancellationSignal] rather than with a hopeful
 *   `withTimeout` alone — a cancelled coroutine does not stop a blocked `rawQuery`, and the signal
 *   is the only thing SQLite itself listens to.
 */
class SqlRunner(
    private val tables: SqlTables,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var db: SQLiteDatabase? = null

    /** Builds the database. Called once, off the main thread, before the first puzzle is shown. */
    suspend fun open() {
        if (db != null) return
        db = withContext(io) {
            // `create(null)` is SQLite's own in-memory database. Nothing is written anywhere.
            SQLiteDatabase.create(null).apply {
                for (table in tables.tables) {
                    execSQL(table.ddl)
                    for (row in table.rows) execSQL(table.insert, row.toTypedArray())
                }
            }
        }
    }

    /** Runs one statement of his, or says in Greek why it will not. */
    suspend fun run(sql: String): SqlOutcome {
        SqlGuard.problem(sql)?.let { return SqlOutcome.Refused(it) }
        val database = db ?: return SqlOutcome.Refused(NOT_READY)
        val statement = SqlGuard.statement(sql)
        val signal = CancellationSignal()
        return try {
            withTimeout(TIMEOUT_MS) {
                withContext(io) {
                    // The timeout cancels this coroutine; only the signal can cancel the query that
                    // is already inside SQLite, so the two are tied together here.
                    val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) signal.cancel() }
                    try {
                        read(database, statement, signal)
                    } finally {
                        handle?.dispose()
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            signal.cancel()
            SqlOutcome.Refused(TOO_SLOW)
        } catch (ce: CancellationException) {
            signal.cancel()
            throw ce
        } catch (e: Throwable) {
            // Everything SQLite can say about a statement a person wrote: a syntax error, a column
            // that is not there, a cancelled query. None of them is allowed past this line.
            SqlOutcome.Refused(BROKEN, e.message?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MESSAGE))
        }
    }

    private fun read(database: SQLiteDatabase, statement: String, signal: CancellationSignal): SqlOutcome {
        val began = System.nanoTime()
        database.rawQuery(statement, null, signal).use { cursor ->
            val columns = cursor.columnNames.toList()
            val rows = ArrayList<List<String>>()
            var truncated = false
            while (cursor.moveToNext()) {
                if (rows.size >= SqlResult.MAX_ROWS) { truncated = true; break }
                rows += (0 until cursor.columnCount).map { cursor.getString(it).orEmpty() }
            }
            val ms = (System.nanoTime() - began) / 1_000_000
            return SqlOutcome.Rows(SqlResult(columns, rows, truncated), ms)
        }
    }

    fun close() {
        runCatching { db?.close() }
        db = null
    }

    companion object {
        /** Long enough for anything over twelve rows, short enough that he is never left waiting. */
        const val TIMEOUT_MS = 2_000L

        const val BROKEN = "Η ερώτηση δεν τρέχει."
        const val TOO_SLOW = "Η ερώτηση άργησε πολύ."

        /** The database was not built — a read that failed on the way in. Nothing he did. */
        const val NOT_READY = "Οι πίνακες δεν είναι έτοιμοι."

        /** How much of SQLite's English is shown. One line under the Greek one. */
        const val MAX_MESSAGE = 160
    }
}
