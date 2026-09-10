package gr.dimitris.app.modules.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of [SqlRunner] that do not need a database: **which statements it will run at all**,
 * and **when two answers are the same answer**.
 *
 * They are here and not on a device because they are the rules, and a rule checked once per
 * instrumented run is a rule nobody is checking. The half that does need SQLite — that the database is
 * really built, that Greek table names really work unquoted, that a syntax error really comes back as
 * a Greek line — is `androidTest`'s `SqlRunnerTest`, which also pins the one thing that could make all
 * of this a lie: [SqlQuery]'s pure evaluator agreeing with SQLite about the same query.
 */
class SqlRunnerTest {

    // ------------------------------------------------------------- what it will run

    @Test fun `a plain SELECT is run`() {
        assertNull(SqlGuard.problem("SELECT λέξη FROM λέξεις"))
        assertNull(SqlGuard.problem("  select * from users where age > 30  "))
        assertNull(SqlGuard.problem("SELECT COUNT(*) FROM orders;"))
    }

    @Test fun `nothing typed is said as nothing typed, not as a syntax error`() {
        assertEquals(SqlGuard.EMPTY, SqlGuard.problem(""))
        assertEquals(SqlGuard.EMPTY, SqlGuard.problem("   \n  "))
        assertEquals(SqlGuard.EMPTY, SqlGuard.problem(";"))
        assertEquals(SqlGuard.EMPTY, SqlGuard.problem("-- μόνο ένα σχόλιο"))
    }

    @Test fun `anything that is not a SELECT is refused by name`() {
        for (statement in listOf(
            "DELETE FROM users",
            "UPDATE users SET age = 1",
            "INSERT INTO users VALUES (13, 'x', 1, 'y')",
            "DROP TABLE users",
            "PRAGMA table_info(users)",
            "ATTACH DATABASE 'x' AS y",
            "CREATE TABLE t (a TEXT)",
            "VACUUM",
            "EXPLAIN SELECT * FROM users",
            "WITH x AS (SELECT 1) SELECT * FROM x",
        )) {
            assertEquals(statement, SqlGuard.ONLY_SELECT, SqlGuard.problem(statement))
        }
    }

    @Test fun `a SELECT with a second statement hiding behind it is one at a time`() {
        assertEquals(SqlGuard.ONE_AT_A_TIME, SqlGuard.problem("SELECT 1; DROP TABLE users"))
        assertEquals(SqlGuard.ONE_AT_A_TIME, SqlGuard.problem("SELECT * FROM users;SELECT * FROM orders"))
        // One trailing semicolon is punctuation, not a second statement.
        assertNull(SqlGuard.problem("SELECT * FROM users;"))
        assertNull(SqlGuard.problem("SELECT * FROM users ;  "))
    }

    /** `WITH … INSERT` is the one way a write can wear a SELECT's clothes. It is refused twice over. */
    @Test fun `a WITH that ends in a write is refused`() {
        assertEquals(
            SqlGuard.ONLY_SELECT,
            SqlGuard.problem("WITH x AS (SELECT id FROM users) INSERT INTO orders SELECT * FROM x"),
        )
        assertEquals(
            SqlGuard.ONLY_SELECT,
            SqlGuard.problem("SELECT 1 FROM users WHERE id IN (WITH y AS (SELECT 1) SELECT 1)"),
        )
    }

    /**
     * The rule that makes the word list survive contact with his own vocabulary: a word inside a
     * string literal is a *value*, and one of his cards could be any word at all.
     */
    @Test fun `a forbidden word inside a string is a value and not a statement`() {
        assertNull(SqlGuard.problem("SELECT λέξη FROM λέξεις WHERE λέξη = 'delete'"))
        assertNull(SqlGuard.problem("SELECT * FROM λέξεις WHERE κατηγορία = 'Πράγματα; drop'"))
    }

    @Test fun `comments go, and what is inside a string stays`() {
        assertEquals("SELECT * FROM users", SqlGuard.strip("SELECT * FROM users -- όλους"))
        assertEquals(
            "SELECT * FROM users",
            SqlGuard.strip("SELECT * /* σχόλιο */ FROM users").replace(Regex("\\s+"), " "),
        )
        assertEquals("SELECT '--' FROM users", SqlGuard.strip("SELECT '--' FROM users"))
        // A comment between two words leaves a space behind it, so two words stay two words.
        assertTrue(SqlGuard.strip("SELECT/*x*/name FROM users").contains("SELECT name"))
    }

    @Test fun `the statement it runs has no comment and no trailing semicolon`() {
        assertEquals("SELECT * FROM users", SqlGuard.statement("SELECT * FROM users; -- τέλος"))
        assertEquals("SELECT * FROM users", SqlGuard.statement("  SELECT * FROM users ;; "))
    }

    /** An unterminated comment or string is never a hole the rules fall through. */
    @Test fun `half a comment and half a string are still refused where they should be`() {
        assertEquals(SqlGuard.ONLY_SELECT, SqlGuard.problem("DELETE FROM users /* ώπα"))
        assertEquals(SqlGuard.EMPTY, SqlGuard.problem("/* ποτέ δεν κλείνει"))
        assertNull(SqlGuard.problem("SELECT * FROM λέξεις WHERE λέξη = 'ανοιχτό"))
    }

    // --------------------------------------------------------- when two answers agree

    private fun result(vararg rows: List<String>) = SqlResult(listOf("a"), rows.toList())

    @Test fun `the same rows in another order are the same answer, unless the order was asked for`() {
        val one = result(listOf("Γιώργος"), listOf("Μαρία"))
        val other = result(listOf("Μαρία"), listOf("Γιώργος"))
        assertTrue("no ORDER BY: the set is the answer", SqlResult.same(one, other, ordered = false))
        assertFalse("with ORDER BY the order is the answer", SqlResult.same(one, other, ordered = true))
        assertTrue("and the same order still agrees", SqlResult.same(one, one, ordered = true))
    }

    @Test fun `the column names are not part of the answer, but their number is`() {
        val named = SqlResult(listOf("name"), listOf(listOf("Μαρία")))
        val qualified = SqlResult(listOf("users.name"), listOf(listOf("Μαρία")))
        assertTrue("SELECT name and SELECT users.name are one question", SqlResult.same(named, qualified, false))
        val two = SqlResult(listOf("name", "city"), listOf(listOf("Μαρία", "Μαρία")))
        assertFalse("two columns is a different answer", SqlResult.same(named, two, false))
    }

    @Test fun `a different number of rows is a different answer, duplicates included`() {
        val once = result(listOf("καφές"))
        val twice = result(listOf("καφές"), listOf("καφές"))
        assertFalse("a join that doubles a row is not the same answer", SqlResult.same(once, twice, false))
        assertTrue(SqlResult.same(twice, result(listOf("καφές"), listOf("καφές")), false))
    }

    @Test fun `two empty answers agree`() {
        assertTrue(SqlResult.same(result(), result(), false))
        assertTrue(SqlResult.same(result(), result(), true))
    }
}
