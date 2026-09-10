package gr.dimitris.app.modules.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The generator's contract, over five hundred draws a level.
 *
 * Three claims, and every one of them is a claim about *results* rather than about text — which is
 * why [SqlQuery] can compute one on the JVM and this test can exist at all:
 *
 * 1. **Exactly one correct answer.** On an ordering board only one arrangement of the tiles is the
 *    query; on a choosing board exactly one of the three options is it; on a keyword board exactly one
 *    of the three words fills the blank.
 * 2. **The answer runs, and answers something.** Never an empty grid, and never the whole table where
 *    a `WHERE` was the point of the question.
 * 3. **Every distractor is valid SQL with a different result.** Not a broken query he could rule out
 *    without reading it, and not a right answer wearing a different hat.
 *
 * The tables are a **fake provider**: two hand-made "his life" tables and the textbook's real two. So
 * this drives the same code path a phone does without a phone, a database, or a sitting of his.
 */
class SqlPuzzlesTest {

    /** How many draws per level. Enough that a one-in-a-hundred hole in the generator shows up. */
    private val draws = 500

    /** His own two tables as they would be after a few weeks of practice. */
    private val hisLife = listOf(
        SqlTable(
            set = SqlTableSet.HIS_LIFE, name = SqlTables.WORDS,
            columns = listOf(
                SqlColumn("λέξη", SqlType.TEXT),
                SqlColumn("κατηγορία", SqlType.TEXT),
                SqlColumn("φορές", SqlType.INTEGER),
            ),
            rows = listOf(
                listOf("καφές", "Φαγητό & ποτό", 31),
                listOf("ψωμί", "Φαγητό & ποτό", 24),
                listOf("νερό", "Φαγητό & ποτό", 19),
                listOf("φαρμακείο", "Μέρη", 12),
                listOf("τράπεζα", "Μέρη", 9),
                listOf("γιατρός", "Άνθρωποι", 7),
                listOf("κόρη", "Άνθρωποι", 5),
                listOf("πονάει", "Ρήματα", 4),
                listOf("θέλω", "Ρήματα", 3),
                listOf("χαρά", "Συναισθήματα", 1),
                listOf("ελευθερία", "Πράγματα", 0),
            ),
        ),
        SqlTable(
            set = SqlTableSet.HIS_LIFE, name = SqlTables.DAY,
            columns = listOf(
                SqlColumn("ώρα", SqlType.TEXT),
                SqlColumn("δραστηριότητα", SqlType.TEXT),
                SqlColumn("λεπτά", SqlType.INTEGER),
            ),
            rows = listOf(
                listOf("09:10", "Λέξεις", 7),
                listOf("09:18", "Αριθμοί", 5),
                listOf("09:25", "Προτάσεις", 4),
                listOf("17:02", "Λέξεις", 6),
                listOf("17:09", "Γράψε", 3),
                listOf("17:14", "SQL", 8),
            ),
        ),
    )

    private val full = SqlTables(hisLife + SqlTables.textbook())

    /** A phone installed this morning: no practice to ask about, and the textbook is the whole module. */
    private val fresh = SqlTables(SqlTables.textbook())

    @Test fun `every level keeps the contract, five hundred times`() {
        for (level in SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL) {
            val random = Random(level * 1_000L + 7)
            repeat(draws) { n ->
                val puzzle = SqlPuzzles.generate(level, full, random)
                check(puzzle, full, "level $level draw $n")
                assertEquals("the level it was asked for", level, puzzle.level)
            }
        }
    }

    @Test fun `a phone with no practice on it is asked about the textbook and nothing else`() {
        for (level in SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL) {
            val random = Random(level * 31L)
            repeat(draws) { n ->
                val puzzle = SqlPuzzles.generate(level, fresh, random)
                check(puzzle, fresh, "fresh level $level draw $n")
                assertEquals("the textbook only", SqlTableSet.TEXTBOOK, puzzle.set)
                assertTrue(
                    "asked about a table that has no rows: ${puzzle.target.text}",
                    puzzle.target.reads.none { it == SqlTables.WORDS || it == SqlTables.DAY },
                )
            }
        }
    }

    /** The thin end of the same rule: `λέξεις` is full and `μέρα` is empty, so only one of them is asked about. */
    @Test fun `a table with too few rows is left alone and the other one is not`() {
        val half = SqlTables(
            listOf(hisLife[0], hisLife[1].copy(rows = emptyList())) + SqlTables.textbook(),
        )
        val random = Random(99)
        var sawWords = false
        repeat(draws) {
            val puzzle = SqlPuzzles.generate(2, half, random)
            check(puzzle, half, "half")
            assertFalse("an empty table was asked about", SqlTables.DAY in puzzle.target.reads)
            if (SqlTables.WORDS in puzzle.target.reads) sawWords = true
        }
        assertTrue("his words were never asked about even though they are there", sawWords)
    }

    @Test fun `level one is three or four tiles and level five is two tables`() {
        val random = Random(5)
        repeat(draws) {
            val one = SqlPuzzles.generate(1, full, random)
            assertEquals(SqlPuzzleKind.ORDER, one.kind)
            assertTrue("tiles: ${one.tiles}", one.tiles.size in 3..4)
            assertTrue("a level-1 query has no WHERE", one.target.where == null)

            val five = SqlPuzzles.generate(5, full, random)
            assertEquals("two tables", 2, five.target.reads.distinct().size)
            assertTrue(five.kind == SqlPuzzleKind.ORDER || five.kind == SqlPuzzleKind.WRITE)
        }
    }

    @Test fun `every level asks its own kind of question`() {
        val random = Random(11)
        val kinds = (SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL).associateWith { level ->
            List(50) { SqlPuzzles.generate(level, full, random).kind }.toSet()
        }
        assertEquals(setOf(SqlPuzzleKind.ORDER), kinds.getValue(1))
        assertEquals(setOf(SqlPuzzleKind.PICK), kinds.getValue(2))
        assertEquals(setOf(SqlPuzzleKind.KEYWORD), kinds.getValue(3))
        assertEquals(setOf(SqlPuzzleKind.WRITE), kinds.getValue(4))
        assertEquals("level 5 is both: a shape to see and a shape to produce", 2, kinds.getValue(5).size)
    }

    /**
     * The last resort is not a worse puzzle. Sixty draws never all fail on a real phone, but a
     * fallback nothing checks is a fallback that is broken the day it is needed.
     */
    @Test fun `the hand-written fallback keeps the same contract`() {
        for (level in SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL) {
            check(SqlPuzzles.fixed(level), full, "fixed $level")
        }
    }

    @Test fun `a question is always Greek and always says what is wanted`() {
        val random = Random(3)
        for (level in SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL) {
            repeat(50) {
                val puzzle = SqlPuzzles.generate(level, full, random)
                assertTrue("blank question", puzzle.question.isNotBlank())
                assertTrue(
                    "a question with no Greek in it: ${puzzle.question}",
                    puzzle.question.any { it in 'α'..'ω' || it in 'Α'..'Ω' },
                )
            }
        }
    }

    // ------------------------------------------------------------------ the contract

    private fun check(puzzle: SqlPuzzle, tables: SqlTables, where: String) {
        val answer = puzzle.target.run(tables)
        assertNotNull("$where: the answer's own query does not run: ${puzzle.target.text}", answer)
        answer!!
        assertFalse("$where: the answer is an empty grid: ${puzzle.target.text}", answer.empty)
        // A WHERE that lets every row through is a WHERE he can ignore and still be right.
        if (puzzle.target.where != null && puzzle.target.select !is SqlSelect.Count) {
            val all = tables.table(puzzle.target.from)?.rows?.size ?: 0
            assertTrue(
                "$where: the WHERE changes nothing: ${puzzle.target.text}",
                puzzle.target.join != null || answer.rows.size < all,
            )
        }
        assertTrue("$where: the tables it shows him", puzzle.shown.isNotEmpty())
        assertTrue(
            "$where: it shows a table the query does not read",
            puzzle.shown.all { it in puzzle.target.reads },
        )
        when (puzzle.kind) {
            SqlPuzzleKind.ORDER -> {
                assertEquals("$where: the tiles are the answer's own words", puzzle.answerTiles.sorted(), puzzle.tiles.sorted())
                assertEquals("$where: two identical tiles are two right answers", puzzle.tiles.distinct().size, puzzle.tiles.size)
                assertEquals("$where: the tiles do not make the query", puzzle.target.text, puzzle.orderedAnswer)
                assertFalse("$where: the board opens already solved", puzzle.tiles == puzzle.answerTiles)
                assertTrue("$where: nothing to show him", puzzle.tiles.size >= 3)
            }
            SqlPuzzleKind.PICK -> options(puzzle, tables, where, queries = true)
            SqlPuzzleKind.KEYWORD -> {
                options(puzzle, tables, where, queries = false)
                assertEquals("$where: one blank, no more and no fewer", 2, puzzle.blanked.split(SqlPuzzles.BLANK).size)
                assertEquals(
                    "$where: the answer does not rebuild the query",
                    puzzle.target.text,
                    puzzle.blanked.replaceFirst(SqlPuzzles.BLANK, puzzle.answer),
                )
            }
            SqlPuzzleKind.WRITE -> {
                assertTrue("$where: a typed board with no result to aim at", puzzle.showResult)
                assertTrue("$where: a typed board with options on it", puzzle.options.isEmpty())
            }
        }
    }

    /** Three options, one of them right, and the other two valid SQL with a different answer. */
    private fun options(puzzle: SqlPuzzle, tables: SqlTables, where: String, queries: Boolean) {
        assertEquals("$where: three options", 3, puzzle.options.size)
        assertEquals("$where: two options the same", 3, puzzle.options.distinct().size)
        assertEquals("$where: exactly one of them is right", 1, puzzle.options.count { it == puzzle.answer })
        if (!queries) return
        assertEquals("$where: the right option is not the query", puzzle.target.text, puzzle.answer)
        val mine = puzzle.target.run(tables)!!
        val seen = mutableListOf(mine)
        for (option in puzzle.options.filter { it != puzzle.answer }) {
            // Parsed back into the shape it was built from: every option on a PICK board is one of
            // the generator's own queries, so finding the one whose text it is proves it is valid.
            val result = resultOf(option, puzzle, tables)
            assertNotNull("$where: a distractor that is not valid SQL: $option", result)
            assertTrue(
                "$where: a distractor with the same answer: $option",
                seen.none { SqlResult.same(it, result!!, puzzle.target.ordered) },
            )
            seen += result!!
        }
    }

    /**
     * What one option returns.
     *
     * The options are *texts*, and nothing here parses SQL — so the shape is found by rebuilding
     * every mutation the generator could have made of the target and matching on the text. A text
     * nothing rebuilds is a distractor the generator invented some other way, and the assertion
     * above fails, which is the right answer: this test is allowed to know exactly as much about the
     * generator as the generator's own contract.
     */
    private fun resultOf(option: String, puzzle: SqlPuzzle, tables: SqlTables): SqlResult? {
        val target = puzzle.target
        val table = tables.table(target.from) ?: return null
        val candidates = mutableListOf<SqlQuery>()
        for (column in table.columnNames) candidates += target.copy(select = SqlSelect.Columns(listOf(column)))
        candidates += target.copy(select = SqlSelect.All)
        candidates += target.copy(select = SqlSelect.Count)
        candidates += target.copy(where = null)
        (target.where as? SqlWhere.Compare)?.let { w ->
            val variants = mutableListOf<SqlWhere.Compare>()
            for (op in listOf(">", "<", ">=", "<=", "=", "!=")) variants += w.copy(op = op)
            for (n in table.numbersIn(w.column)) variants += w.copy(value = SqlValue.Num(n))
            for (s in table.textsIn(w.column)) variants += w.copy(value = SqlValue.Text(s))
            for (op in listOf(">", "<", ">=", "<=", "=", "!=")) {
                for (n in table.numbersIn(w.column)) variants += w.copy(op = op, value = SqlValue.Num(n))
                for (s in table.textsIn(w.column)) variants += w.copy(op = op, value = SqlValue.Text(s))
            }
            for (variant in variants) {
                candidates += target.copy(where = variant)
                for (column in table.columnNames) {
                    candidates += target.copy(select = SqlSelect.Columns(listOf(column)), where = variant)
                }
            }
        }
        target.orderBy?.let { order ->
            candidates += target.copy(orderBy = order.copy(desc = !order.desc))
            for (column in table.columnNames) {
                candidates += target.copy(select = SqlSelect.Columns(listOf(column)), orderBy = order)
                candidates += target.copy(select = SqlSelect.Columns(listOf(column)), orderBy = order.copy(desc = !order.desc))
            }
        }
        return candidates.firstOrNull { it.text == option }?.run(tables)
    }
}
