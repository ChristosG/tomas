package gr.dimitris.app.modules.sql

import gr.dimitris.app.core.data.AttemptDao
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.ItemDao
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.SessionDao
import gr.dimitris.app.today.SessionViewModel
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Which of the two worlds a table belongs to.
 *
 * «Η ζωή του» is built out of his own practice — the words he says and the mornings he sits down —
 * and «Το βιβλίο» is the two tables every SQL book opens with. Both are here on purpose. A query
 * that answers a question about *him* is the reason to write one at all; a query over `users` and
 * `orders` is the shape he will recognise from every tutorial his father's company ever wrote.
 */
enum class SqlTableSet(val greek: String) {
    HIS_LIFE("Η ζωή του"),
    TEXTBOOK("Το βιβλίο"),
}

/** The two column types this module has. Everything is a word or a whole number. */
enum class SqlType { TEXT, INTEGER }

data class SqlColumn(val name: String, val type: SqlType)

/**
 * One table: a name, its columns, and the rows themselves.
 *
 * Pure data with no SQLite in it, so the whole of [SqlPuzzles] can be generated and argued with on
 * the JVM — see `SqlPuzzlesTest` — and only [SqlRunner] ever needs a device.
 *
 * [rows] carry `String` under a TEXT column and `Int` under an INTEGER one. Nothing else: a cell is
 * read back out of a real cursor as a string either way, and the pure evaluator in [SqlQuery] has to
 * agree with SQLite about what a cell *is*.
 */
data class SqlTable(
    val set: SqlTableSet,
    val name: String,
    val columns: List<SqlColumn>,
    val rows: List<List<Any>>,
) {
    val columnNames: List<String> get() = columns.map { it.name }

    fun typeOf(column: String): SqlType? = columns.firstOrNull { it.name == column }?.type

    fun indexOf(column: String): Int = columns.indexOfFirst { it.name == column }

    /** Every distinct value in one TEXT column, in the order the rows have it. For the literals a puzzle asks about. */
    fun textsIn(column: String): List<String> {
        val at = indexOf(column)
        if (at < 0) return emptyList()
        return rows.mapNotNull { it.getOrNull(at) as? String }.distinct()
    }

    /** Every distinct value in one INTEGER column, sorted. */
    fun numbersIn(column: String): List<Int> {
        val at = indexOf(column)
        if (at < 0) return emptyList()
        return rows.mapNotNull { it.getOrNull(at) as? Int }.distinct().sorted()
    }

    /** `CREATE TABLE "λέξεις" ("λέξη" TEXT, …)`. Quoted, because a Greek name is still an identifier. */
    val ddl: String
        get() = columns.joinToString(", ", "CREATE TABLE ${quote(name)} (", ")") { "${quote(it.name)} ${it.type.name}" }

    /** `INSERT INTO "λέξεις" VALUES (?, ?, ?)`. The values go as bound arguments, never as text. */
    val insert: String
        get() = columns.joinToString(", ", "INSERT INTO ${quote(name)} VALUES (", ")") { "?" }

    private fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""
}

/**
 * The four tables one sitting of «SQL» can be asked about, all in one database.
 *
 * One database and not two, because a puzzle is a query and a query names its own tables: keeping
 * «Η ζωή του» and «Το βιβλίο» apart would buy nothing and would mean building — and tearing down —
 * a second connection halfway through the sitting.
 *
 * A table is only asked about when it has enough rows to make a question with an answer ([usable]).
 * On a phone that has never been practised on, `μέρα` is empty and `λέξεις` may be thin, and the
 * sitting is drawn from the textbook alone. Nothing is invented to fill them: a table of made-up
 * mornings would be the one thing in this module that is not true.
 */
class SqlTables(val tables: List<SqlTable>) {

    fun table(name: String): SqlTable? = tables.firstOrNull { it.name == name }

    /** Enough rows to ask a question whose answer is neither everything nor nothing. */
    fun usable(name: String): Boolean = (table(name)?.rows?.size ?: 0) >= MIN_ROWS

    fun of(set: SqlTableSet): List<SqlTable> = tables.filter { it.set == set }

    companion object {
        const val WORDS = "λέξεις"
        const val DAY = "μέρα"
        const val USERS = "users"
        const val ORDERS = "orders"

        /** Under this a table has nothing to ask: every answer would be the whole table or none of it. */
        const val MIN_ROWS = 6

        /** How many of his words the table holds. Twelve, like the textbook's, so a result still fits a phone. */
        const val WORD_ROWS = 12

        /** How many of his sittings `μέρα` reaches back over, and how many rows it keeps. */
        const val DAY_SESSIONS = 6
        const val DAY_ROWS = 12

        /** A word longer than this is a sentence somebody typed into the vocabulary. */
        const val MAX_CELL = 40

        /**
         * Everything, read off his phone where there is anything to read and made up nowhere.
         *
         * [names] is the module-name map the caregiver's report already keeps
         * ([gr.dimitris.app.caregiver.insights.AdviceSummary.MODULE_NAMES]), so `μέρα` says
         * «Λέξεις» and «Αριθμοί» rather than `WORDCOACH` and `NUMBERS` — the same words he reads on
         * the tiles.
         */
        suspend fun load(
            items: ItemDao,
            attempts: AttemptDao,
            sessions: SessionDao,
            names: Map<ModuleId, String>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): SqlTables = SqlTables(
            listOf(
                words(items, attempts),
                day(sessions, attempts, names, zone),
            ) + textbook()
        )

        /**
         * `λέξεις(λέξη, κατηγορία, φορές)` — the words he practises, their Greek category, and how
         * many times he has been asked for each in «Λέξεις».
         *
         * Text only, and never an id or a path: this table is on a screen and inside every query he
         * writes about it, and a row that carried a UUID would be a row he had to read out.
         */
        suspend fun words(items: ItemDao, attempts: AttemptDao): SqlTable {
            // The synthetic id to keep out is the sitting's summary row, not the talk board's
            // expansion: this query is already filtered to `module = WORDCOACH`, and the expansion is
            // only ever written against TALKBOARD, while the summary is written against whichever
            // module was planned last — which can perfectly well be «Λέξεις».
            val counts = attempts.mostUsed(ModuleId.WORDCOACH, WORD_ROWS * 8, SessionViewModel.SESSION_SUMMARY).first()
                .associate { it.itemId to it.n }
            val rows = items.activeOfKinds(listOf(ItemKind.WORD, ItemKind.PHRASE))
                // The sung sentences are not words he practises: they belong to «Τραγούδα και πες το»
                // alone, and the word coach and the talk board already keep them out. A «λέξεις» table
                // with «Πόσο κάνει το ψωμί;» under «Τραγούδι» in it would be the third place that
                // forgot — and a `φορές` of 0 on every one of them, because «Λέξεις» never asks them.
                .filterNot { it.category == Category.SINGING }
                .mapNotNull { item ->
                    val text = cell(item.text) ?: return@mapNotNull null
                    Triple(text, item.category.greek, counts[item.id] ?: 0)
                }
                .distinctBy { it.first }
                // The ones he has really done first, so «φορές» is a column with a spread in it
                // rather than twelve zeros. The name breaks the tie, so the table is the same table
                // twice in a row and a query he writes twice gives the same answer twice.
                .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.first })
                .take(WORD_ROWS)
                .map { listOf<Any>(it.first, it.second, it.third) }
            return SqlTable(
                set = SqlTableSet.HIS_LIFE,
                name = WORDS,
                columns = listOf(
                    SqlColumn("λέξη", SqlType.TEXT),
                    SqlColumn("κατηγορία", SqlType.TEXT),
                    SqlColumn("φορές", SqlType.INTEGER),
                ),
                rows = rows,
            )
        }

        /**
         * `μέρα(ώρα, δραστηριότητα, λεπτά)` — when he sat down, what he did, and for how long.
         *
         * Read off the attempt rows of his last few sittings rather than off the session's own
         * planned list: the plan is what the app *meant* to give him, and this table is a fact about
         * his morning. A module he opened and left after ten seconds is a minute here, because a
         * minute is the smallest thing this column can honestly say.
         *
         * The sitting's own summary row is left out, the way
         * [gr.dimitris.app.core.data.AttemptDao.lastUsePerModule] and
         * [gr.dimitris.app.caregiver.progress.ProgressStats] already leave it out. It is written
         * against whichever module happened to be planned *last*, at the **session's** start time and
         * for the **whole session's** length — so counting it gave that one module the wrong hour and
         * the whole morning's minutes: a ten-minute sitting ending in «Γράψε» read as
         * `("09:10", "Γράψε", 11)` instead of `("09:25", "Γράψε", 2)`. This is the one table whose
         * justification is that it is true.
         */
        suspend fun day(
            sessions: SessionDao,
            attempts: AttemptDao,
            names: Map<ModuleId, String>,
            zone: ZoneId,
        ): SqlTable {
            val recent = sessions.recent(DAY_SESSIONS)
            val rows = if (recent.isEmpty()) emptyList() else {
                val ids = recent.map { it.id }.toSet()
                val clock = DateTimeFormatter.ofPattern("HH:mm")
                attempts.since(recent.minOf { it.startedAt })
                    .filter { it.sessionId in ids && it.itemId != SessionViewModel.SESSION_SUMMARY }
                    .groupBy { it.sessionId to it.module }
                    .entries
                    .mapNotNull { (key, rows) ->
                        val name = names[key.second] ?: return@mapNotNull null
                        val began = rows.minOf { it.startedAt }
                        val minutes = (rows.sumOf { it.durationMs } / 60_000L).toInt().coerceAtLeast(1)
                        began to listOf<Any>(
                            clock.format(Instant.ofEpochMilli(began).atZone(zone)),
                            name,
                            minutes,
                        )
                    }
                    // Newest sitting first, so a short table is his most recent mornings and not his
                    // oldest ones; within the table the rows read down the morning.
                    .sortedByDescending { it.first }
                    .take(DAY_ROWS)
                    .sortedBy { it.first }
                    .map { it.second }
            }
            return SqlTable(
                set = SqlTableSet.HIS_LIFE,
                name = DAY,
                columns = listOf(
                    SqlColumn("ώρα", SqlType.TEXT),
                    SqlColumn("δραστηριότητα", SqlType.TEXT),
                    SqlColumn("λεπτά", SqlType.INTEGER),
                ),
                rows = rows,
            )
        }

        /**
         * `users` and `orders`, the two tables of every SQL book there has ever been.
         *
         * English identifiers and Greek values on purpose: the keywords and the column names are the
         * thing he already knows from work, and the rows are people and shopping he can read at a
         * glance. Twelve rows each, with the ages, the cities and the prices spread out enough that
         * a `WHERE` has a middle — a column where every answer is all twelve rows teaches nothing.
         */
        fun textbook(): List<SqlTable> = listOf(
            SqlTable(
                set = SqlTableSet.TEXTBOOK,
                name = USERS,
                columns = listOf(
                    SqlColumn("id", SqlType.INTEGER),
                    SqlColumn("name", SqlType.TEXT),
                    SqlColumn("age", SqlType.INTEGER),
                    SqlColumn("city", SqlType.TEXT),
                ),
                rows = listOf(
                    listOf(1, "Γιώργος", 34, "Αθήνα"),
                    listOf(2, "Μαρία", 28, "Θεσσαλονίκη"),
                    listOf(3, "Νίκος", 45, "Πάτρα"),
                    listOf(4, "Ελένη", 52, "Αθήνα"),
                    listOf(5, "Δημήτρης", 39, "Λάρισα"),
                    listOf(6, "Σοφία", 23, "Ηράκλειο"),
                    listOf(7, "Κώστας", 61, "Βόλος"),
                    listOf(8, "Άννα", 30, "Αθήνα"),
                    listOf(9, "Πέτρος", 47, "Ιωάννινα"),
                    listOf(10, "Κατερίνα", 36, "Θεσσαλονίκη"),
                    listOf(11, "Θανάσης", 55, "Χανιά"),
                    listOf(12, "Ειρήνη", 41, "Καβάλα"),
                ),
            ),
            SqlTable(
                set = SqlTableSet.TEXTBOOK,
                name = ORDERS,
                columns = listOf(
                    SqlColumn("id", SqlType.INTEGER),
                    SqlColumn("user_id", SqlType.INTEGER),
                    SqlColumn("item", SqlType.TEXT),
                    SqlColumn("price", SqlType.INTEGER),
                ),
                rows = listOf(
                    listOf(1, 1, "καφές", 3),
                    listOf(2, 1, "ψωμί", 2),
                    listOf(3, 2, "γάλα", 2),
                    listOf(4, 3, "τυρί", 7),
                    listOf(5, 3, "καφές", 3),
                    listOf(6, 4, "κρασί", 9),
                    listOf(7, 5, "ψωμί", 2),
                    listOf(8, 6, "πορτοκάλια", 4),
                    listOf(9, 7, "τυρί", 7),
                    listOf(10, 8, "καφές", 3),
                    listOf(11, 9, "μπύρα", 5),
                    listOf(12, 10, "σοκολάτα", 6),
                ),
            ),
        )

        /**
         * One cell of his own text, made safe to put in a table and in a query literal.
         *
         * Quotes and newlines go, because the same word is about to be written into
         * `WHERE κατηγορία = '…'` on a screen he reads; a blank or an over-long one is dropped
         * entirely rather than truncated into something that is not a word he knows.
         */
        internal fun cell(text: String): String? {
            val clean = text.replace('\n', ' ').replace('\r', ' ')
                .filterNot { it == '\'' || it == '"' }
                .trim()
                .replace(WHITESPACE, " ")
            return clean.takeIf { it.isNotEmpty() && it.length <= MAX_CELL }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
