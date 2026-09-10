package gr.dimitris.app.modules.sql

/**
 * The shape of every query this module asks for, and a pure evaluator for it.
 *
 * Why an evaluator at all, when there is a real SQLite a few lines away in [SqlRunner]: because the
 * one promise [SqlPuzzles] has to keep is that **every puzzle has exactly one correct answer and
 * every distractor gives a different result**. That is a claim about results, so it can only be
 * checked by computing them — and checking it on a device, once per build, is not checking it. With
 * this, `SqlPuzzlesTest` draws five hundred puzzles per level on the JVM and compares result sets;
 * `SqlRunnerTest` on the device then pins the only thing that could make all of it a lie, which is
 * the evaluator and SQLite disagreeing about the same query.
 *
 * Deliberately tiny. It understands exactly the five things a beginner's query is made of — which
 * columns, which table, one join, one condition, one ordering — and nothing else. A query he *types*
 * is never parsed by it: that one goes to SQLite, which is the only honest reader of something a
 * person wrote. See [SqlRunner].
 */
data class SqlQuery(
    val select: SqlSelect,
    val from: String,
    val join: SqlJoin? = null,
    val where: SqlWhere? = null,
    val orderBy: SqlOrder? = null,
) {
    /** The query as he reads it, and as it goes to SQLite. */
    val text: String
        get() = buildString {
            append("SELECT ").append(select.text)
            append(" FROM ").append(from)
            join?.let { append(" JOIN ").append(it.table).append(" ON ").append(it.left).append(" = ").append(it.right) }
            where?.let { append(" WHERE ").append(it.text) }
            orderBy?.let { append(" ORDER BY ").append(it.column).append(if (it.desc) " DESC" else "") }
        }

    /** True when the order of the rows is part of the answer. See [SqlResult.same]. */
    val ordered: Boolean get() = orderBy != null

    /** Every table this query reads, the subquery's included: what the screen has to show him. */
    val reads: List<String>
        get() = listOfNotNull(from, join?.table) + ((where as? SqlWhere.InSub)?.sub?.reads ?: emptyList())

    /**
     * What it answers over [tables], computed in Kotlin. Null when the query names something that is
     * not there — a table or a column [SqlPuzzles] would never have built, and an answer of "no
     * opinion" rather than a crash inside a generator.
     */
    fun run(tables: SqlTables): SqlResult? {
        val base = tables.table(from) ?: return null
        var rows: List<Map<String, Any>> = base.rows.map { row(base, it) }
        var names = base.columnNames
        if (join != null) {
            val other = tables.table(join.table) ?: return null
            names = names + other.columnNames
            rows = rows.flatMap { left ->
                other.rows.map { row(other, it) }.mapNotNull { right ->
                    val a = left[join.left] ?: return@mapNotNull null
                    val b = right[join.right] ?: return@mapNotNull null
                    if (a == b) left + right else null
                }
            }
        }
        if (where != null) rows = where.filter(rows, tables) ?: return null
        orderBy?.let { order ->
            val key = compareBy<Map<String, Any>> { cellKey(it[order.column]) }
            rows = rows.sortedWith(if (order.desc) key.reversed() else key)
        }
        return select.project(rows, names)
    }

    /** One row as a lookup by both `column` and `table.column`, which is how a join is read. */
    private fun row(table: SqlTable, values: List<Any>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(values.size * 2)
        table.columns.forEachIndexed { at, column ->
            val value = values.getOrNull(at) ?: return@forEachIndexed
            out[column.name] = value
            out["${table.name}.${column.name}"] = value
        }
        return out
    }

    private companion object {
        /** Numbers before words, numbers numerically: SQLite's own ordering of a mixed column. */
        fun cellKey(v: Any?): String = when (v) {
            is Int -> "0" + v.toString().padStart(12, '0')
            null -> ""
            else -> "1$v"
        }
    }
}

/** What comes between `SELECT` and `FROM`. */
sealed interface SqlSelect {
    val text: String

    /** [all] is every column the query's tables have, in order: what `*` means here. */
    fun project(rows: List<Map<String, Any>>, all: List<String>): SqlResult?

    /** `*` — every column of the table, which is only ever used on a single-table query. */
    data object All : SqlSelect {
        override val text = "*"
        override fun project(rows: List<Map<String, Any>>, all: List<String>): SqlResult =
            SqlResult(all, rows.map { r -> all.map { render(r[it]) } })
    }

    /** One or more named columns, qualified where a join makes that necessary. */
    data class Columns(val names: List<String>) : SqlSelect {
        override val text = names.joinToString(", ")
        override fun project(rows: List<Map<String, Any>>, all: List<String>): SqlResult? {
            if (names.any { it.substringAfterLast('.') !in all }) return null
            return SqlResult(names, rows.map { r -> names.map { render(r[it]) } })
        }
    }

    /** `COUNT(*)` — one row, one number, which is what «πόσα» asks for. */
    data object Count : SqlSelect {
        override val text = "COUNT(*)"
        override fun project(rows: List<Map<String, Any>>, all: List<String>) =
            SqlResult(listOf(text), listOf(listOf(rows.size.toString())))
    }

    companion object {
        /** A cell as a cursor would hand it back: `getString` on an INTEGER column is its digits. */
        fun render(v: Any?): String = v?.toString().orEmpty()
    }
}

/** `JOIN <table> ON <left> = <right>`, with both sides qualified. */
data class SqlJoin(val table: String, val left: String, val right: String)

/** `ORDER BY <column>`, optionally descending. */
data class SqlOrder(val column: String, val desc: Boolean = false)

/** The one condition a query of this module has. */
sealed interface SqlWhere {
    val text: String

    /** The rows that pass, or null when the condition names something that is not there. */
    fun filter(rows: List<Map<String, Any>>, tables: SqlTables): List<Map<String, Any>>?

    /** `<column> <op> <value>`, where the value's type always agrees with the column's. */
    data class Compare(val column: String, val op: String, val value: SqlValue) : SqlWhere {
        override val text get() = "$column $op ${value.literal}"

        override fun filter(rows: List<Map<String, Any>>, tables: SqlTables): List<Map<String, Any>>? {
            if (rows.isNotEmpty() && column !in rows.first()) return null
            return rows.filter { holds(it[column]) }
        }

        private fun holds(cell: Any?): Boolean {
            if (cell == null) return false
            val side = when {
                cell is Int && value is SqlValue.Num -> cell.compareTo(value.n)
                cell is String && value is SqlValue.Text -> cell.compareTo(value.s)
                // A comparison across types is one [SqlPuzzles] never builds, and SQLite's answer to
                // it (every number below every string) is not a rule worth teaching him.
                else -> return false
            }
            return when (op) {
                ">" -> side > 0
                "<" -> side < 0
                ">=" -> side >= 0
                "<=" -> side <= 0
                "=" -> side == 0
                "!=" -> side != 0
                else -> false
            }
        }
    }

    /** `<column> IN (<subquery>)` — the two-table question without the word JOIN in it. */
    data class InSub(val column: String, val sub: SqlQuery) : SqlWhere {
        override val text get() = "$column IN (${sub.text})"

        override fun filter(rows: List<Map<String, Any>>, tables: SqlTables): List<Map<String, Any>>? {
            if (rows.isNotEmpty() && column !in rows.first()) return null
            val inner = sub.run(tables) ?: return null
            val allowed = inner.rows.mapNotNull { it.firstOrNull() }.toSet()
            return rows.filter { SqlSelect.render(it[column]) in allowed }
        }
    }
}

/** A literal, as it is written into the query. */
sealed interface SqlValue {
    val literal: String

    data class Num(val n: Int) : SqlValue {
        override val literal get() = n.toString()
    }

    data class Text(val s: String) : SqlValue {
        /** Doubled quotes, so a word with an apostrophe in it is a string and not a syntax error. */
        override val literal get() = "'" + s.replace("'", "''") + "'"
    }
}
