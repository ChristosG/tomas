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
     * **A query that cannot finish is stopped at two seconds**, and the board is usable straight
     * after.
     *
     * [BOMB] is seven copies of `users` joined with a condition nothing can index — 35,831,808 rows
     * of string concatenation, twelve times the six-copy version, which is measured at 1.7 s on this
     * emulator and finishes. That six-copy one is what [aQueryThatIsMerelySlowStillAnswers] pins on
     * the other side: the watchdog is a limit, not a hair trigger. Measured here: refused at
     * 2005 ms and again at 2002 ms.
     *
     * The first cut of the timeout could not stop anything — it hung the `CancellationSignal` off
     * `invokeOnCompletion` on the job that was itself blocked inside `rawQuery`, so the signal was
     * pulled after the query had already finished. This test is written the way it is because the
     * *old* test could not see that: it accepted `Rows` **or** `TOO_SLOW`, so a query that hung for
     * ten minutes and then answered passed it. The wall clock is the assertion.
     */
    @Test fun aQueryThatCannotFinishIsStoppedAtTwoSeconds() {
        val began = System.currentTimeMillis()
        val outcome = run(BOMB)
        val took = System.currentTimeMillis() - began

        assertEquals(SqlRunner.TOO_SLOW, (outcome as SqlOutcome.Refused).greek)
        assertTrue("stopped after $took ms, which is not a two-second limit", took < SqlRunner.TIMEOUT_MS + 3_000)
        assertTrue("stopped after only $took ms, which is a hair trigger", took >= SqlRunner.TIMEOUT_MS - 250)
        // And the connection is still his: a cancelled query must not take the board with it.
        assertEquals(12, count(SqlTables.USERS))
        assertEquals(listOf(listOf("Κώστας")), rows("SELECT name FROM users WHERE city = 'Βόλος'").rows)
    }

    /** The other side of the same limit: something slow but finishable still answers. */
    @Test fun aQueryThatIsMerelySlowStillAnswers() {
        val outcome = run(
            "SELECT COUNT(*) FROM users a, users b, users c, users d, users e, users f " +
                "WHERE a.name || b.name || c.name || d.name || e.name || f.name LIKE '%ζζζ%'"
        )
        assertTrue("a query well inside the limit was refused: $outcome", outcome is SqlOutcome.Rows)
    }

    /**
     * A `WITH RECURSIVE` bomb never reaches the database at all — not even through the apostrophe
     * that used to blind the guard.
     *
     * The stronger of the two answers, and the reason [BOMB] is a cross join rather than a recursive
     * CTE: `WITH` is refused by name ([SqlGuard.FORBIDDEN]), so the classic infinite-CTE bomb is
     * turned away before SQLite is asked to compile it, and the two seconds never have to be spent.
     */
    @Test fun aRecursiveBombNeverReachesTheDatabase() {
        val cte = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c) SELECT x FROM c"
        val began = System.currentTimeMillis()
        assertEquals(SqlGuard.ONLY_SELECT, (run(cte) as SqlOutcome.Refused).greek)
        // And hidden behind an unmatched quote inside a double-quoted token, which SQLite compiles
        // happily and which used to make every rule after it invisible.
        assertEquals(
            SqlGuard.ONLY_SELECT,
            (run("SELECT 1 WHERE \"q'\" AND 1 IN ($cte)") as SqlOutcome.Refused).greek,
        )
        val took = System.currentTimeMillis() - began
        assertTrue("it went to SQLite: $took ms", took < 500)
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

        /**
         * Seven copies of `users` and a condition nothing can index: 35,831,808 rows of string
         * concatenation. `COUNT(*)` over a plain cross join is **not** a bomb — SQLite multiplies the
         * row counts and answers in a quarter of a second — so the `WHERE` is what makes it real
         * work, and the query has to be one [SqlGuard] allows or the timeout is never reached.
         */
        const val BOMB =
            "SELECT COUNT(*) FROM users a, users b, users c, users d, users e, users f, users g " +
                "WHERE a.name || b.name || c.name || d.name || e.name || f.name || g.name LIKE '%ζ%'"
    }
}
