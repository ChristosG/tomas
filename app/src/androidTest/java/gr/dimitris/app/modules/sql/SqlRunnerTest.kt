package gr.dimitris.app.modules.sql

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * The half of this module that only a real SQLite can answer.
 *
 * Three things, and the third is the one that makes the five-hundred-draw JVM test worth anything:
 *
 * 1. The database is really built — four tables, Greek names and Greek values and all, out of
 *    [SqlTables] — and a Greek identifier really works **unquoted**, which is what every query he
 *    writes or orders depends on. SQLite takes any character above 127 as an identifier character; the
 *    whole of levels 1–4 over «λέξεις» rests on that sentence being true, so it is checked here rather
 *    than believed.
 * 2. A statement that will not run comes back as a Greek line with SQLite's own English under it, and
 *    never as a crash.
 * 3. **The pure evaluator in [SqlQuery] and SQLite agree.** [SqlPuzzles]' contract — one right answer,
 *    distractors with a different result — is computed by the evaluator, so if the two ever disagreed
 *    the JVM test would be proving something about a database nobody runs.
 */
class SqlRunnerTest {

    private val words = SqlTable(
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
        ),
    )

    private val day = SqlTable(
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
    )

    private val tables = SqlTables(listOf(words, day) + SqlTables.textbook())
    private lateinit var runner: SqlRunner

    @Before fun open() {
        runner = SqlRunner(tables)
        runBlocking { runner.open() }
    }

    @After fun close() = runner.close()

    private fun run(sql: String): SqlOutcome = runBlocking { runner.run(sql) }
    private fun rows(sql: String): SqlResult = (run(sql) as SqlOutcome.Rows).result

    // ------------------------------------------------------------ the tables are really there

    @Test fun allFourTablesAreBuiltWithTheirRows() {
        assertEquals(words.rows.size, count(SqlTables.WORDS))
        assertEquals(day.rows.size, count(SqlTables.DAY))
        assertEquals(12, count(SqlTables.USERS))
        assertEquals(12, count(SqlTables.ORDERS))
    }

    private fun count(table: String): Int =
        rows("SELECT COUNT(*) FROM $table").rows.single().single().toInt()

    /** The sentence the whole of levels 1–4 over his own words rests on. */
    @Test fun greekTableAndColumnNamesWorkWithoutQuotes() {
        val result = rows("SELECT λέξη FROM λέξεις WHERE φορές > 10")
        assertEquals(listOf("λέξη"), result.columns)
        assertEquals(listOf("καφές", "ψωμί", "νερό", "φαρμακείο"), result.rows.map { it.single() })

        val grouped = rows("SELECT κατηγορία FROM λέξεις WHERE κατηγορία = 'Μέρη'")
        assertEquals(2, grouped.rows.size)

        val hours = rows("SELECT ώρα, λεπτά FROM μέρα WHERE δραστηριότητα = 'Λέξεις' ORDER BY ώρα")
        assertEquals(listOf(listOf("09:10", "7"), listOf("17:02", "6")), hours.rows)
    }

    /** An INTEGER column comes back as its digits, which is what every comparison here assumes. */
    @Test fun numbersComeBackAsTheirDigits() {
        assertEquals(listOf(listOf("31")), rows("SELECT φορές FROM λέξεις WHERE λέξη = 'καφές'").rows)
    }

    // ---------------------------------------------------------------- nothing ever crashes

    @Test fun aSyntaxErrorIsAGreekLineWithTheDatabasesOwnEnglishUnderIt() {
        val refused = run("SELECT λέξη FORM λέξεις") as SqlOutcome.Refused
        assertEquals(SqlRunner.BROKEN, refused.greek)
        assertNotNull("no English to read", refused.english)
        assertTrue("the message should name the trouble: ${refused.english}", refused.english!!.isNotBlank())
    }

    @Test fun aColumnThatIsNotThereIsRefusedAndNotACrash() {
        val refused = run("SELECT δεν_υπάρχει FROM λέξεις") as SqlOutcome.Refused
        assertEquals(SqlRunner.BROKEN, refused.greek)
        assertNotNull(refused.english)
    }

    @Test fun aWriteNeverReachesTheDatabase() {
        val before = count(SqlTables.WORDS)
        for (statement in listOf(
            "DELETE FROM λέξεις",
            "DROP TABLE λέξεις",
            "UPDATE λέξεις SET φορές = 0",
            "INSERT INTO λέξεις VALUES ('x', 'y', 1)",
            "SELECT 1; DELETE FROM λέξεις",
            "PRAGMA writable_schema = 1",
        )) {
            val refused = run(statement)
            assertTrue("$statement was run", refused is SqlOutcome.Refused)
        }
        assertEquals("a write got through", before, count(SqlTables.WORDS))
    }

    /** Fifty rows and no more, and the screen is told it was cut rather than shown half an answer. */
    @Test fun aLongAnswerIsCutAtFiftyRowsAndSaysSo() {
        val result = rows("SELECT a.item, b.item FROM orders a, orders b")
        assertEquals(SqlResult.MAX_ROWS, result.rows.size)
        assertTrue("the screen was not told", result.truncated)

        val short = rows("SELECT item FROM orders")
        assertFalse(short.truncated)
        assertEquals(12, short.rows.size)
    }

    /**
     * A query that cannot finish comes back as a Greek line too. Eight tables joined with a condition
     * nothing can index is 430 million rows of string work, which is not two seconds anywhere.
     *
     * The assertion is deliberately the weaker of the two honest ones — *either* it answered or it
     * said it was too slow — because a machine fast enough to finish it would otherwise fail a test
     * about not hanging. What is really being pinned is that nothing throws and the screen always
     * gets an answer.
     */
    @Test fun aQueryThatCannotFinishIsRefusedRatherThanHanging() {
        val outcome = run(
            "SELECT COUNT(*) FROM orders a, orders b, orders c, orders d, orders e, orders f, orders g, orders h " +
                "WHERE a.item || b.item || c.item || d.item || e.item || f.item || g.item || h.item LIKE '%ζ%'"
        )
        when (outcome) {
            is SqlOutcome.Refused -> assertEquals(SqlRunner.TOO_SLOW, outcome.greek)
            is SqlOutcome.Rows -> assertTrue("it finished, which is allowed", outcome.ms >= 0)
        }
    }

    // -------------------------------------------------- the evaluator and SQLite agree

    /**
     * Every puzzle of every level, drawn the way a sitting draws them, run through **both** readers.
     *
     * This is the hinge of the whole module's testing: `SqlPuzzlesTest` proves the contract using
     * [SqlQuery]'s pure evaluator, and the contract is only worth something if the evaluator says what
     * the database says.
     */
    @Test fun thePureEvaluatorAndSqliteGiveTheSameAnswer() {
        for (level in SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL) {
            val random = Random(level * 7L + 1)
            repeat(DRAWS) { n ->
                val puzzle = SqlPuzzles.generate(level, tables, random)
                val mine = puzzle.target.run(tables)
                assertNotNull("level $level draw $n: the evaluator has no answer", mine)
                val theirs = run(puzzle.target.text)
                assertTrue(
                    "level $level draw $n: SQLite refused a query the generator built: ${puzzle.target.text} ($theirs)",
                    theirs is SqlOutcome.Rows,
                )
                theirs as SqlOutcome.Rows
                assertTrue(
                    "level $level draw $n: they disagree about ${puzzle.target.text}\n" +
                        "  evaluator: ${mine!!.rows}\n  sqlite:    ${theirs.result.rows}",
                    SqlResult.same(mine, theirs.result, puzzle.target.ordered),
                )
                // And the distractors are valid SQL as far as the database is concerned too.
                if (puzzle.kind == SqlPuzzleKind.PICK) {
                    for (option in puzzle.options) {
                        assertTrue(
                            "level $level draw $n: a distractor SQLite will not run: $option",
                            run(option) is SqlOutcome.Rows,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /** Enough draws to cover every shape of every level, few enough to keep the run short. */
        const val DRAWS = 60
    }
}
