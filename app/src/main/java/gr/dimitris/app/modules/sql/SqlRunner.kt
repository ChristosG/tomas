package gr.dimitris.app.modules.sql

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
         * two different answers — which is the whole point of having written the `ORDER BY`. The
         * generator only ever sorts on a column with no ties in it ([SqlPuzzles]), so that order is a
         * fact about the data and not about SQLite's mood.
         *
         * Only the *number* of columns is compared, so `SELECT 6` satisfies a `COUNT(*)` target that
         * happens to answer 6. That is knowingly left open: this is a rehab app and the only person
         * it could deceive is the one typing, who would be cheating himself out of an exercise. It is
         * worth knowing before anybody reads an accuracy line for this module.
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
    /**
     * It ran. [ms] is how long **SQLite** took, which is not what the attempt row's own `ms` is: that
     * one is his thinking time, from the moment the board was drawn (`SqlViewModel.record`). This one
     * is for a screen that ever wants to say "the database took no time at all, you did".
     */
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
 *
 * Two things it knowingly does not do. It understands only `'...'` quoting, not double quotes,
 * brackets or backticks, so a legitimate `SELECT "kappa" FROM ...` whose quoted name contained a `;`
 * or a forbidden word would be refused — an over-refusal, which is the safe direction and rare enough
 * to be worth the simplicity. And `sqlite_master` and the `pragma_*` table-valued functions read as
 * ordinary words, so `SELECT * FROM sqlite_master` runs: what it shows is the four in-memory tables'
 * own `CREATE TABLE` lines and `:memory:` as the file name. No user data, no path, nothing that is
 * not already on his screen.
 */
object SqlGuard {
    /** Nothing typed at all. */
    const val EMPTY = "Γράψε μια ερώτηση."

    /** Something that is not a question: an `INSERT`, a `PRAGMA`, a word we do not run. */
    const val ONLY_SELECT = "Μόνο SELECT εδώ."

    /** Two statements, or one and a half. */
    const val ONE_AT_A_TIME = "Μία ερώτηση κάθε φορά."

    /**
     * A `'` that was opened and never closed.
     *
     * Its own line, and not SQLite's «unrecognized token after …», because it is the one mistake this
     * module can name in Greek better than the database can name it in English — and because an
     * unterminated literal is what every rule below it has to read *through*. See [openLiteral].
     */
    const val OPEN_QUOTE = "Λείπει ένα εισαγωγικό."

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
     * Order matters, and it is the order of *honesty*: "you wrote nothing", then "there are two
     * statements here", then "that is not a SELECT", and only then "a quote is missing". So
     * `SELECT 1; DROP TABLE users` is answered with the reason that is actually about it, and a stray
     * apostrophe in front of a chained `DROP` does not get to rename the problem.
     */
    fun problem(sql: String): String? {
        val clean = strip(sql).trim().trimEnd(';', ' ', '\t', '\n', '\r')
        if (clean.isBlank()) return EMPTY
        val bare = withoutStrings(clean)
        if (';' in bare) return ONE_AT_A_TIME
        val words = WORD.findAll(bare).map { it.value.uppercase() }.toList()
        if (words.firstOrNull() != "SELECT") return ONLY_SELECT
        if (words.any { it in FORBIDDEN }) return ONLY_SELECT
        // Last, because it is the mildest thing that can be wrong with a statement — and because a
        // statement that is *also* two statements should be told so first. An unterminated literal is
        // not valid SQL in any case; saying it in Greek is better than SQLite's «unrecognized token».
        if (openLiteral(clean)) return OPEN_QUOTE
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
                if (end < 0) {
                    // **Never truncated.** A quote with no partner used to end the scan here, and
                    // everything after it — a `;`, a `DROP`, a `WITH RECURSIVE` — became invisible to
                    // the rules while [statement] still handed the whole string to SQLite. One
                    // apostrophe inside a double-quoted token was the whole of the bypass. What is
                    // left of the statement is a *value* as far as this scan is concerned, but the
                    // rules must still be allowed to read it.
                    out.append(sql, at + 1, sql.length)
                    return out.toString()
                }
                at = end + 1
            } else {
                out.append(sql[at]); at++
            }
        }
        return out.toString()
    }

    /** True when a `'` was opened and never closed. `'it''s'` is closed twice over and is not one. */
    private fun openLiteral(sql: String): Boolean {
        var at = 0
        while (at < sql.length) {
            if (sql[at] == '\'') {
                val end = sql.indexOf('\'', at + 1)
                if (end < 0) return true
                at = end + 1
            } else {
                at++
            }
        }
        return false
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
 * * **It never hangs.** Two seconds, enforced by a watchdog coroutine that cancels a
 *   [CancellationSignal] — see [run]. A `withTimeout` around the read would have been theatre: the
 *   coroutine doing the reading is blocked inside `rawQuery` with no suspension point to resume at,
 *   so it cannot be interrupted and the timeout cannot fire until the query has finished anyway. The
 *   signal is the only thing SQLite itself listens to, and it has to be pulled by a coroutine that is
 *   not the one waiting.
 */
class SqlRunner(
    private val tables: SqlTables,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var db: SQLiteDatabase? = null

    /** Builds the database. Called once, off the main thread, before the first puzzle is shown. */
    suspend fun open() {
        if (db != null) return
        val built = withContext(io) {
            // `create(null)` is SQLite's own in-memory database. Nothing is written anywhere.
            SQLiteDatabase.create(null).apply {
                for (table in tables.tables) {
                    execSQL(table.ddl)
                    for (row in table.rows) execSQL(table.insert, row.toTypedArray())
                }
            }
        }
        // A cancel that landed while it was being built — he moved the dots, and `load()` started
        // again — must not leave a database behind with nobody holding it. It is only memory, and
        // only until the next collection, but a leak nobody closes is a leak.
        try {
            coroutineContext.ensureActive()
        } catch (ce: CancellationException) {
            built.close()
            throw ce
        }
        if (db != null) { built.close(); return }
        db = built
    }

    /**
     * Runs one statement of his, or says in Greek why it will not.
     *
     * The two seconds are kept by a **watchdog**: a coroutine that does nothing but sleep and then
     * pull the [CancellationSignal]. It has to be a second coroutine, because the first one is inside
     * `rawQuery` and is not going to come back to check the time.
     *
     * The first cut of this hung the signal off `invokeOnCompletion` on the reading job, which was
     * the right instrument on the wrong event: that callback fires when the job reaches a *final*
     * state, and a job blocked in `fillWindow` cannot reach one — so the signal was pulled after the
     * query had finished, which is no timeout at all. A sloppy `FROM users a, users b, …` at level 5
     * enumerates three million rows with `countAllRows`, and the board sat on «Τρέχω...» with
     * «Έτοιμο» dead for as long as SQLite took.
     *
     * SQLite answers a cancelled signal by throwing `OperationCanceledException` out of the cursor,
     * which is why it has a branch of its own below: it is a `RuntimeException`, so without one it
     * would arrive as «Η ερώτηση δεν τρέχει.» — a lie about a query that was fine and merely slow.
     *
     * The watchdog pulls the signal from a `finally`, so **the caller's own cancellation stops the
     * query too**: a screen that goes away mid-query aborts the statement at once instead of leaving a
     * thread on it until it finishes by itself.
     */
    suspend fun run(sql: String): SqlOutcome {
        SqlGuard.problem(sql)?.let { return SqlOutcome.Refused(it) }
        val database = db ?: return SqlOutcome.Refused(NOT_READY)
        val statement = SqlGuard.statement(sql)
        val signal = CancellationSignal()
        return try {
            coroutineScope {
                // `finally`, so the signal is pulled when the watchdog is **cancelled** as well as
                // when it fires. The watchdog is a child of this scope: leaving the screen or moving
                // the dots cancels the scope, which used to cancel the watchdog before its two seconds
                // were up — and `withContext` cannot interrupt a thread inside `fillWindow`, so the
                // `catch (ce: CancellationException)` below only ran *after* the query had finished on
                // its own. A seven-way cross join is ~20 s of that, with `onCleared` closing the
                // database under it. On a normal return the `finally` below cancels the watchdog and
                // its own `finally` then pulls a signal nothing is attached to, which is inert.
                val watchdog = launch { try { delay(TIMEOUT_MS) } finally { signal.cancel() } }
                try {
                    withContext(io) { read(database, statement, signal) }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (cancelled: OperationCanceledException) {
            // The watchdog got there first — or the screen went away and pulled the signal itself.
            SqlOutcome.Refused(TOO_SLOW)
        } catch (ce: CancellationException) {
            // The screen is going: stop the query on its way out rather than leaving a thread on it.
            signal.cancel()
            throw ce
        } catch (e: Throwable) {
            // Everything else SQLite can say about a statement a person wrote: a syntax error, a
            // column that is not there. None of it is allowed past this line.
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
