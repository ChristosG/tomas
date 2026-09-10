package gr.dimitris.app.modules.sql

import kotlin.random.Random

/** The four ways this module asks a question. One per level, and two at level 5. */
enum class SqlPuzzleKind {
    /** Tiles to put in order. Levels 1 and 5. */
    ORDER,

    /** A result on the screen and three queries to choose between. Level 2. */
    PICK,

    /** One keyword missing from a written query, and three words to fill it with. Level 3. */
    KEYWORD,

    /** A result on the screen and a keyboard. Levels 4 and 5. */
    WRITE,
}

/**
 * One question, complete: what is asked, what answers it, and everything the board needs to draw.
 *
 * Every field but [target] and [question] is empty on the kinds that do not use it, which keeps one
 * type for four boards — the same shape [gr.dimitris.app.modules.sentences.Sentence] has, and for the
 * same reason: a sealed hierarchy would put four `when` branches in every one of the ViewModel's
 * methods to say the same four things.
 */
data class SqlPuzzle(
    val level: Int,
    val kind: SqlPuzzleKind,
    val set: SqlTableSet,
    /** The whole Greek line — the instruction and what is wanted. «Άκου» reads exactly this. */
    val question: String,
    /** The query that answers it. Its text is the answer on an [SqlPuzzleKind.ORDER] board too. */
    val target: SqlQuery,
    /** The tiles, shuffled. [answerTiles] is the order they go in. */
    val tiles: List<String> = emptyList(),
    val answerTiles: List<String> = emptyList(),
    /** Three of them: whole queries on a [SqlPuzzleKind.PICK] board, keywords on a [SqlPuzzleKind.KEYWORD] one. */
    val options: List<String> = emptyList(),
    /** Which of [options] is right. Empty where there are none. */
    val answer: String = "",
    /** The query with `___` where the keyword should be. */
    val blanked: String = "",
    /** Whether the answer's own result is on the screen from the start: it is the question on two boards. */
    val showResult: Boolean = false,
    /** The tables whose shape the board shows him. */
    val shown: List<String> = emptyList(),
) {
    /** The answer to an ordering board, as one line of SQL. */
    val orderedAnswer: String get() = answerTiles.joinToString(" ")
}

/**
 * The question generator: pure, seeded, and the only thing that decides what a level *is*.
 *
 * The contract, and every word of it is checked by `SqlPuzzlesTest` over five hundred draws a level:
 *
 * * every puzzle has **exactly one** correct answer;
 * * the answer's query **runs**, and returns neither nothing nor the whole table — a `WHERE` that
 *   selects all twelve rows has taught him nothing about `WHERE`;
 * * every distractor is **valid SQL that gives a different result** — never a broken query he could
 *   have ruled out without reading it, and never a right answer wearing a different hat.
 *
 * All three are claims about *results*, which is why [SqlQuery] can compute one without a device.
 *
 * Which tables a puzzle is about is decided here too, from what is actually on the phone. `λέξεις`
 * and `μέρα` are his own practice and may be thin or empty on a phone that has just been installed
 * ([SqlTables.usable]); the textbook's two are always there. So the first morning is `users` and
 * `orders`, and the questions about his own words arrive as soon as there are words to ask about.
 */
object SqlPuzzles {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 5

    /** The blank a keyword board leaves. The same one the sentence builder uses, for the same reason. */
    const val BLANK = "___"

    const val ORDER_THEM = "Βάλε τις λέξεις στη σειρά."
    const val WHICH_QUERY = "Ποια ερώτηση δίνει αυτό;"
    const val FILL_IT = "Συμπλήρωσε τη λέξη που λείπει."
    const val WRITE_IT = "Γράψε την ερώτηση."

    /** How many shots the generator gets at a level before it falls back on [fixed]. */
    private const val TRIES = 60

    /**
     * One puzzle at [level], drawn from [tables] with [random].
     *
     * Total by construction: it tries [TRIES] times to build one that keeps the contract above, and
     * if the phone's own tables somehow defeat all of them it returns [fixed] — a hand-written
     * textbook question of the right level, which the same test checks the contract on.
     */
    fun generate(level: Int, tables: SqlTables, random: Random): SqlPuzzle {
        val at = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        repeat(TRIES) {
            val made = when (at) {
                1 -> order(tables, random)
                2 -> pick(tables, random)
                3 -> keyword(tables, random)
                4 -> write(tables, random)
                else -> twoTable(tables, random)
            }
            if (made != null) return made
        }
        return fixed(at)
    }

    // ------------------------------------------------------------------ level 1

    /** Three or four tiles that make `SELECT λέξη FROM λέξεις`. The first query anybody writes. */
    private fun order(tables: SqlTables, random: Random): SqlPuzzle? {
        val source = sources(tables).randomOrNull(random) ?: return null
        val star = random.nextBoolean()
        val pick = source.picks.random(random)
        val query = SqlQuery(
            select = if (star) SqlSelect.All else SqlSelect.Columns(listOf(pick)),
            from = source.table,
        )
        // Three tiles for `SELECT *`, four when a column is named: `SELECT *` is one idea and
        // splitting it would be a tile that means nothing on its own.
        val answer = if (star) listOf("SELECT *", "FROM", source.table)
        else listOf("SELECT", pick, "FROM", source.table)
        return orderBoard(level = 1, source.set, query, answer, tables, random)
    }

    // ------------------------------------------------------------------ level 2

    /** A result on the screen, and three queries. Only one of them produces it. */
    private fun pick(tables: SqlTables, random: Random): SqlPuzzle? {
        val source = sources(tables).randomOrNull(random) ?: return null
        val query = filtered(source, tables, random) ?: return null
        if (!worthAsking(query, tables, source)) return null
        val wrong = distractors(query, source, tables, random)
        if (wrong.size < 2) return null
        val options = (listOf(query.text) + wrong.take(2)).shuffled(random)
        return SqlPuzzle(
            level = 2, kind = SqlPuzzleKind.PICK, set = source.set,
            question = WHICH_QUERY, target = query,
            options = options, answer = query.text, showResult = true, shown = query.reads,
        )
    }

    // ------------------------------------------------------------------ level 3

    /** One keyword taken out of a written query, and three words to put back. */
    private fun keyword(tables: SqlTables, random: Random): SqlPuzzle? {
        val source = sources(tables).randomOrNull(random) ?: return null
        val query = when (random.nextInt(3)) {
            0 -> filtered(source, tables, random)
            1 -> counted(source, tables, random)
            else -> sorted(source, tables, random)
        } ?: return null
        if (!worthAsking(query, tables, source)) return null
        val slot = slots(query).randomOrNull(random) ?: return null
        val wrong = KEYWORDS.filter { it != slot }.shuffled(random).take(2)
        if (wrong.size < 2) return null
        return SqlPuzzle(
            level = 3, kind = SqlPuzzleKind.KEYWORD, set = source.set,
            question = "$FILL_IT ${goal(query)}", target = query,
            options = (listOf(slot) + wrong).shuffled(random), answer = slot,
            blanked = blanked(query, slot), shown = query.reads,
        )
    }

    // ------------------------------------------------------------------ level 4

    /** The answer's result on the screen, and a keyboard. What he writes is run and compared. */
    private fun write(tables: SqlTables, random: Random): SqlPuzzle? {
        val source = sources(tables).randomOrNull(random) ?: return null
        val query = when (random.nextInt(3)) {
            0 -> filtered(source, tables, random)
            1 -> counted(source, tables, random)
            else -> sorted(source, tables, random)
        } ?: return null
        if (!worthAsking(query, tables, source)) return null
        return SqlPuzzle(
            level = 4, kind = SqlPuzzleKind.WRITE, set = source.set,
            question = "$WRITE_IT ${goal(query)}", target = query,
            showResult = true, shown = query.reads,
        )
    }

    // ------------------------------------------------------------------ level 5

    /**
     * Two tables at once: a `JOIN`, or the same question asked with `IN` and a second `SELECT`.
     *
     * Always the textbook's `users` and `orders`, because they are the only two tables here that
     * share a key — `λέξεις` and `μέρα` are two facts about him and have nothing to join on. That is
     * not a compromise: the join is the idea being taught, and `users`/`orders` is the example every
     * book teaches it with.
     */
    private fun twoTable(tables: SqlTables, random: Random): SqlPuzzle? {
        val orders = tables.table(SqlTables.ORDERS) ?: return null
        if (tables.table(SqlTables.USERS) == null) return null
        val condition = orderCondition(orders, random) ?: return null
        val join = random.nextBoolean()
        val query = if (join) SqlQuery(
            select = SqlSelect.Columns(listOf("users.name")),
            from = SqlTables.USERS,
            join = SqlJoin(SqlTables.ORDERS, "users.id", "orders.user_id"),
            where = SqlWhere.Compare("orders.${condition.column}", condition.op, condition.value),
        ) else SqlQuery(
            select = SqlSelect.Columns(listOf("name")),
            from = SqlTables.USERS,
            where = SqlWhere.InSub(
                "id",
                SqlQuery(SqlSelect.Columns(listOf("user_id")), SqlTables.ORDERS, where = condition),
            ),
        )
        val rows = query.run(tables) ?: return null
        val everyone = tables.table(SqlTables.USERS)?.rows?.size ?: 0
        if (rows.empty || rows.rows.size >= everyone) return null
        // The joined form is put in order, the `IN` form is typed: the first is a shape to see, the
        // second is a shape to produce, and level 5 is where he meets both.
        return if (join) {
            val answer = listOf(
                "SELECT users.name",
                "FROM users",
                "JOIN orders",
                "ON users.id = orders.user_id",
                "WHERE orders.${condition.column} ${condition.op} ${condition.value.literal}",
            )
            // Asked the short way, not through [goal]. A five-tile board already carries two table
            // shapes above it, and [goal]'s four lines of «Από τους πίνακες users και orders, δείξε
            // τη στήλη users.name όπου …» pushed the tiles he has to tap off the bottom of the
            // screen. The tiles *are* the query here: what the Greek owes him is the question.
            orderBoard(level = 5, SqlTableSet.TEXTBOOK, query, answer, tables, random, asked(condition))
        } else {
            SqlPuzzle(
                level = 5, kind = SqlPuzzleKind.WRITE, set = SqlTableSet.TEXTBOOK,
                question = "$WRITE_IT ${goalOfTwo(condition)}", target = query,
                showResult = true, shown = query.reads,
            )
        }
    }

    // ------------------------------------------------------------------ the pieces

    /** A tile board, if the tiles are all different: two identical tiles would be two right answers. */
    private fun orderBoard(
        level: Int,
        set: SqlTableSet,
        query: SqlQuery,
        answer: List<String>,
        tables: SqlTables,
        random: Random,
        /** What the query is for, in Greek. [goal] unless the board has a shorter way of asking. */
        wants: String = goal(query),
    ): SqlPuzzle? {
        if (answer.distinct().size != answer.size) return null
        if (answer.joinToString(" ") != query.text) return null
        if (query.run(tables) == null) return null
        // Shuffled until it is not already the answer: a board that opens solved is not a question.
        var tiles = answer.shuffled(random)
        var guard = 0
        while (tiles == answer && guard++ < 10) tiles = answer.shuffled(random)
        if (tiles == answer) return null
        return SqlPuzzle(
            level = level, kind = SqlPuzzleKind.ORDER, set = set,
            question = "$ORDER_THEM $wants", target = query,
            tiles = tiles, answerTiles = answer, shown = query.reads,
        )
    }

    /** `SELECT <col> FROM <t> WHERE …` — the query the middle three levels are built on. */
    private fun filtered(source: Source, tables: SqlTables, random: Random): SqlQuery? {
        val table = tables.table(source.table) ?: return null
        val where = condition(source, table, random) ?: return null
        // Never the column the condition is about, where the table has another to offer: a
        // `SELECT κατηγορία … WHERE κατηγορία = 'Χρόνος'` answers with five rows of the same word,
        // which reads as a broken screen and teaches nothing about what a `SELECT` chooses.
        val pick = (source.picks.filter { it != where.column }.ifEmpty { source.picks }).random(random)
        return SqlQuery(SqlSelect.Columns(listOf(pick)), source.table, where = where)
    }

    /** `SELECT COUNT(*) FROM <t> WHERE …` — «πόσες γραμμές». */
    private fun counted(source: Source, tables: SqlTables, random: Random): SqlQuery? {
        val table = tables.table(source.table) ?: return null
        val where = condition(source, table, random) ?: return null
        return SqlQuery(SqlSelect.Count, source.table, where = where)
    }

    /**
     * `SELECT <col> FROM <t> ORDER BY <n>` — the one shape where the order of the rows *is* the
     * answer, and therefore the one shape that has to be careful about ties.
     *
     * It sorts only on a column whose values are all different in this table, and returns null when
     * there is no such column (the generator then draws another shape). SQLite makes no promise about
     * the order of rows with equal keys, so `ORDER BY price` over `(2, 2, 3, 3, 3, …)` would be judged
     * order-sensitively against an order that is not contractual — and an equivalent query of his
     * could be called wrong for a reason nobody could explain to him. The device cross-check agreeing
     * over three hundred draws is evidence, not a guarantee; this is the guarantee.
     */
    private fun sorted(source: Source, tables: SqlTables, random: Random): SqlQuery? {
        val table = tables.table(source.table) ?: return null
        val by = table.columns.filter { it.type == SqlType.INTEGER }
            .map { it.name }
            .filter { name ->
                val values = table.rows.mapNotNull { it.getOrNull(table.indexOf(name)) }
                values.size == table.rows.size && values.distinct().size == values.size
            }
            .randomOrNull(random) ?: return null
        val pick = (source.picks.filter { it != by }.ifEmpty { source.picks }).random(random)
        return SqlQuery(
            SqlSelect.Columns(listOf(pick)),
            source.table,
            orderBy = SqlOrder(by, desc = random.nextBoolean()),
        )
    }

    /** One condition over the table as it really is, so its answer is neither nothing nor everything. */
    private fun condition(source: Source, table: SqlTable, random: Random): SqlWhere.Compare? =
        if (random.nextBoolean()) numberCondition(table, source.number, random)
            ?: textCondition(table, source.text, random)
        else textCondition(table, source.text, random)
            ?: numberCondition(table, source.number, random)

    /** `φορές > 5`, with the 5 taken from the column so there is something on both sides of it. */
    private fun numberCondition(table: SqlTable, column: String, random: Random): SqlWhere.Compare? {
        val values = table.numbersIn(column)
        if (values.size < 2) return null
        return when (random.nextInt(3)) {
            // Above a value that is present: everything strictly above it is a proper, non-empty part.
            0 -> SqlWhere.Compare(column, ">", SqlValue.Num(values[random.nextInt(values.size - 1)]))
            1 -> SqlWhere.Compare(column, "<", SqlValue.Num(values[1 + random.nextInt(values.size - 1)]))
            else -> SqlWhere.Compare(column, "=", SqlValue.Num(values.random(random)))
        }
    }

    /** `κατηγορία = 'Φαγητό & ποτό'`, with the word taken from the column. */
    private fun textCondition(table: SqlTable, column: String, random: Random): SqlWhere.Compare? {
        val values = table.textsIn(column)
        if (values.size < 2) return null
        return SqlWhere.Compare(column, "=", SqlValue.Text(values.random(random)))
    }

    /** The same, over `orders`, for the two-table questions. */
    private fun orderCondition(orders: SqlTable, random: Random): SqlWhere.Compare? =
        if (random.nextBoolean()) textCondition(orders, "item", random) else numberCondition(orders, "price", random)

    /**
     * Whether the question is worth asking: it runs, it answers *something*, and it does not answer
     * with the whole table. Both ends matter — an empty grid says nothing, and a `WHERE` that lets
     * every row through is a `WHERE` he can ignore and still be right.
     */
    private fun worthAsking(query: SqlQuery, tables: SqlTables, source: Source): Boolean {
        val rows = query.run(tables) ?: return false
        val all = tables.table(source.table)?.rows?.size ?: return false
        // A count is one row whatever happens, so what is judged is the count itself.
        if (query.select is SqlSelect.Count) {
            val n = rows.rows.firstOrNull()?.firstOrNull()?.toIntOrNull() ?: return false
            return n in 1 until all
        }
        if (rows.empty) return false
        return query.where == null || rows.rows.size < all
    }

    /**
     * Two queries that are valid, run, and give a **different** answer from [query] and from each
     * other — the whole of what makes a three-way choice a question.
     *
     * "Different" is judged the same way his own answer will be judged: order-insensitive, unless the
     * query being asked for had an `ORDER BY` in it. A distractor that returns the same rows in
     * another order would otherwise be a second right answer on every board but that one.
     */
    private fun distractors(query: SqlQuery, source: Source, tables: SqlTables, random: Random): List<String> {
        val mine = query.run(tables) ?: return emptyList()
        val table = tables.table(source.table) ?: return emptyList()
        val candidates = mutableListOf<SqlQuery>()
        // Another column of the same table: the commonest thing to get wrong, and the easiest to see.
        for (other in source.picks) {
            if (SqlSelect.Columns(listOf(other)).text != query.select.text) {
                candidates += query.copy(select = SqlSelect.Columns(listOf(other)))
            }
        }
        (query.where as? SqlWhere.Compare)?.let { w ->
            // The other side of the comparison. A word is only ever compared for sameness: SQLite is
            // perfectly happy with `city > 'Βόλος'`, and it is a real answer to a real question, but
            // it is not one a beginner should have to rule out — and «is Βόλος bigger than Χανιά» is
            // a lesson about byte order, not about `WHERE`.
            val ops = if (w.value is SqlValue.Num) listOf(">", "<", "=", "!=") else listOf("=", "!=")
            for (op in ops) {
                if (op != w.op) candidates += query.copy(where = w.copy(op = op))
            }
            // Another value out of the same column.
            when (w.value) {
                is SqlValue.Num -> table.numbersIn(w.column).filter { it != w.value.n }
                    .forEach { candidates += query.copy(where = w.copy(value = SqlValue.Num(it))) }
                is SqlValue.Text -> table.textsIn(w.column).filter { it != w.value.s }
                    .forEach { candidates += query.copy(where = w.copy(value = SqlValue.Text(it))) }
            }
            // No condition at all: the whole table, which is a real answer to a different question.
            candidates += query.copy(where = null)
        }
        query.orderBy?.let { candidates += query.copy(orderBy = it.copy(desc = !it.desc)) }
        val kept = mutableListOf<SqlQuery>()
        val seen = mutableListOf<SqlResult>()
        for (candidate in candidates.shuffled(random)) {
            if (candidate.text == query.text) continue
            val result = candidate.run(tables) ?: continue
            if (SqlResult.same(result, mine, query.ordered)) continue
            if (seen.any { SqlResult.same(it, result, query.ordered) }) continue
            kept += candidate
            seen += result
            if (kept.size == 2) break
        }
        return kept.map { it.text }
    }

    /** Which keywords this query has to take out. */
    private fun slots(query: SqlQuery): List<String> = buildList {
        add("SELECT")
        add("FROM")
        if (query.select is SqlSelect.Count) add("COUNT(*)")
        if (query.where != null) add("WHERE")
        if (query.orderBy != null) add("ORDER BY")
    }

    /** The query written out with [slot] replaced by [BLANK]. Built, never search-and-replaced. */
    private fun blanked(query: SqlQuery, slot: String): String = buildString {
        append(if (slot == "SELECT") BLANK else "SELECT").append(' ')
        append(if (slot == "COUNT(*)") BLANK else query.select.text)
        append(' ').append(if (slot == "FROM") BLANK else "FROM").append(' ').append(query.from)
        query.where?.let { append(' ').append(if (slot == "WHERE") BLANK else "WHERE").append(' ').append(it.text) }
        query.orderBy?.let {
            append(' ').append(if (slot == "ORDER BY") BLANK else "ORDER BY").append(' ').append(it.column)
            if (it.desc) append(" DESC")
        }
    }

    /** What the query is *for*, in Greek. The column names stay as they are: they are what he types. */
    internal fun goal(query: SqlQuery): String {
        val what = when (val s = query.select) {
            is SqlSelect.Count -> "μέτρησε τις γραμμές"
            is SqlSelect.All -> "δείξε όλες τις στήλες"
            is SqlSelect.Columns -> if (s.names.size == 1) "δείξε τη στήλη ${s.names[0]}" else "δείξε τις στήλες ${s.text}"
        }
        val where = when (val w = query.where) {
            null -> ""
            is SqlWhere.Compare -> " όπου ${w.column} ${w.op} ${w.value.literal}"
            is SqlWhere.InSub -> " όπου ${w.column} βγαίνει από τη δεύτερη ερώτηση"
        }
        val order = query.orderBy?.let { " ταξινομημένα κατά ${it.column}" + if (it.desc) " ανάποδα" else "" }.orEmpty()
        val from = if (query.join != null) "Από τους πίνακες ${query.from} και ${query.join.table}, "
        else "Από τον πίνακα ${query.from}, "
        return "$from$what$where$order."
    }

    /**
     * The two-table question said as a person would ask it, which is not how [goal] says it: «Ποιοι
     * έχουν παραγγελία με item = 'καφές';» rather than «Από τους πίνακες users και orders, δείξε τη
     * στήλη users.name όπου orders.item = 'καφές'.»
     *
     * Both boards of level 5 ask it. The typed one adds which column to answer with, because he has
     * to write that; the ordering one does not, because the tiles already say it.
     */
    private fun asked(condition: SqlWhere.Compare): String =
        "Ποιοι έχουν παραγγελία με ${condition.column} ${condition.op} ${condition.value.literal};"

    private fun goalOfTwo(condition: SqlWhere.Compare): String =
        "${asked(condition)} Δείξε τη στήλη name."

    /**
     * The last resort: a hand-written question of the right level over the textbook, for the case
     * where sixty draws all fell foul of the contract. Nothing on a real phone reaches it —
     * `users` and `orders` are constants with room in every column — and it is here so that
     * [generate] is total rather than nearly total, and so that a level can never return null into a
     * sitting he is in the middle of.
     */
    internal fun fixed(level: Int): SqlPuzzle {
        val cheap = SqlQuery(
            SqlSelect.Columns(listOf("name")),
            SqlTables.USERS,
            where = SqlWhere.Compare("age", "<", SqlValue.Num(40)),
        )
        return when (level) {
            1 -> SqlPuzzle(
                level = 1, kind = SqlPuzzleKind.ORDER, set = SqlTableSet.TEXTBOOK,
                question = "$ORDER_THEM ${goal(SqlQuery(SqlSelect.Columns(listOf("name")), SqlTables.USERS))}",
                target = SqlQuery(SqlSelect.Columns(listOf("name")), SqlTables.USERS),
                tiles = listOf("FROM", "SELECT", "users", "name"),
                answerTiles = listOf("SELECT", "name", "FROM", "users"),
                shown = listOf(SqlTables.USERS),
            )
            2 -> SqlPuzzle(
                level = 2, kind = SqlPuzzleKind.PICK, set = SqlTableSet.TEXTBOOK,
                question = WHICH_QUERY, target = cheap,
                options = listOf(
                    cheap.text,
                    cheap.copy(where = SqlWhere.Compare("age", ">", SqlValue.Num(40))).text,
                    cheap.copy(select = SqlSelect.Columns(listOf("city"))).text,
                ),
                answer = cheap.text, showResult = true, shown = listOf(SqlTables.USERS),
            )
            3 -> SqlPuzzle(
                level = 3, kind = SqlPuzzleKind.KEYWORD, set = SqlTableSet.TEXTBOOK,
                question = "$FILL_IT ${goal(cheap)}", target = cheap,
                options = listOf("WHERE", "FROM", "ORDER BY"), answer = "WHERE",
                blanked = blanked(cheap, "WHERE"), shown = listOf(SqlTables.USERS),
            )
            4 -> SqlPuzzle(
                level = 4, kind = SqlPuzzleKind.WRITE, set = SqlTableSet.TEXTBOOK,
                question = "$WRITE_IT ${goal(cheap)}", target = cheap,
                showResult = true, shown = listOf(SqlTables.USERS),
            )
            else -> {
                val two = SqlQuery(
                    SqlSelect.Columns(listOf("users.name")),
                    SqlTables.USERS,
                    join = SqlJoin(SqlTables.ORDERS, "users.id", "orders.user_id"),
                    where = SqlWhere.Compare("orders.item", "=", SqlValue.Text("καφές")),
                )
                SqlPuzzle(
                    level = 5, kind = SqlPuzzleKind.ORDER, set = SqlTableSet.TEXTBOOK,
                    question = "$ORDER_THEM ${goal(two)}", target = two,
                    tiles = listOf("ON users.id = orders.user_id", "SELECT users.name", "WHERE orders.item = 'καφές'", "FROM users", "JOIN orders"),
                    answerTiles = listOf("SELECT users.name", "FROM users", "JOIN orders", "ON users.id = orders.user_id", "WHERE orders.item = 'καφές'"),
                    shown = listOf(SqlTables.USERS, SqlTables.ORDERS),
                )
            }
        }
    }

    /**
     * A table worth asking about, and which of its columns play which part: what a `SELECT` returns,
     * which column a number is compared against, which one a word is.
     */
    private data class Source(
        val table: String,
        val set: SqlTableSet,
        val picks: List<String>,
        val number: String,
        val text: String,
    )

    /** The tables this phone can be asked about today. His own two only when they have rows. */
    private fun sources(tables: SqlTables): List<Source> = listOfNotNull(
        Source(SqlTables.WORDS, SqlTableSet.HIS_LIFE, listOf("λέξη", "κατηγορία"), "φορές", "κατηγορία")
            .takeIf { tables.usable(SqlTables.WORDS) },
        Source(SqlTables.DAY, SqlTableSet.HIS_LIFE, listOf("ώρα", "δραστηριότητα"), "λεπτά", "δραστηριότητα")
            .takeIf { tables.usable(SqlTables.DAY) },
        Source(SqlTables.USERS, SqlTableSet.TEXTBOOK, listOf("name", "city"), "age", "city")
            .takeIf { tables.usable(SqlTables.USERS) },
        // `price` is a pick as well as the number to compare against, so that a question about the
        // item has a column to answer with that is not the item — see [filtered].
        Source(SqlTables.ORDERS, SqlTableSet.TEXTBOOK, listOf("item", "price"), "price", "item")
            .takeIf { tables.usable(SqlTables.ORDERS) },
    )

    /** The words a keyword board offers. Every one of them is a word he will need. */
    private val KEYWORDS = listOf("SELECT", "FROM", "WHERE", "ORDER BY", "COUNT(*)", "JOIN", "AND", "LIMIT")
}
