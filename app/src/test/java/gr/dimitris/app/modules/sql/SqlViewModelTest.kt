package gr.dimitris.app.modules.sql

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.FakeAttemptDao
import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeSessionDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.today.SessionViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * The rules [SqlViewModel] runs on, argued with in a second rather than on a phone.
 *
 * Everything here is free of the ViewModel on purpose: an [gr.dimitris.app.AppGraph] needs a
 * `Context` and cannot be built on the JVM, so the three judgements that actually decide what happens
 * to him — *is that the answer*, *what does this count as*, *what goes in the row* — live as functions
 * beside it. `SqlFlowTest` proves the wiring on a device; what is pinned here is what the wiring is
 * for. The fourth thing is [SqlTables.load], which reads his own practice out of the database, and it
 * is driven here over the project's fake DAOs.
 */
class SqlViewModelTest {

    private val tables = SqlTables(SqlTables.textbook())
    private val gson = Gson()

    // --------------------------------------------------------------- what counts as what

    @Test fun `found it himself is his own, found it after help is assisted, passed is passed`() {
        assertEquals(Outcome.CORRECT, sqlOutcome(firstTry = true, skipped = false))
        assertEquals(Outcome.ASSISTED, sqlOutcome(firstTry = false, skipped = false))
        assertEquals(Outcome.SKIPPED, sqlOutcome(firstTry = false, skipped = true))
        // A skip is a skip even if nothing was wrong with the first go: he passed on the question.
        assertEquals(Outcome.SKIPPED, sqlOutcome(firstTry = true, skipped = true))
    }

    // ---------------------------------------------------------------- is that the answer

    private val ordering = SqlPuzzles.fixed(1)
    private val choosing = SqlPuzzles.fixed(2)
    private val filling = SqlPuzzles.fixed(3)
    private val typing = SqlPuzzles.fixed(4)

    @Test fun `an ordering board wants the tiles in the order the query has them`() {
        assertTrue(sqlAccepts(ordering, ordering.orderedAnswer, got = null, wanted = null))
        assertFalse(sqlAccepts(ordering, ordering.tiles.joinToString(" "), null, null))
        assertFalse("half an answer is not an answer", sqlAccepts(ordering, "SELECT name", null, null))
    }

    @Test fun `a choosing board wants the one option, and so does a keyword board`() {
        assertTrue(sqlAccepts(choosing, choosing.answer, null, null))
        assertTrue(sqlAccepts(filling, filling.answer, null, null))
        for (other in choosing.options.filter { it != choosing.answer }) {
            assertFalse(other, sqlAccepts(choosing, other, null, null))
        }
        for (other in filling.options.filter { it != filling.answer }) {
            assertFalse(other, sqlAccepts(filling, other, null, null))
        }
    }

    /**
     * The typed board compares **results** and not text, which is the whole reason the module can
     * accept a query of his that is not the one we had in mind.
     */
    @Test fun `a typed board wants the same answer, however he wrote it`() {
        val wanted = typing.target.run(tables)!!
        // A different query with the same result set: `!=` for `<` with a threshold that happens to
        // split the table the same way is his answer, and it counts.
        val his = SqlQuery(
            SqlSelect.Columns(listOf("name")),
            SqlTables.USERS,
            where = SqlWhere.Compare("age", "<=", SqlValue.Num(39)),
        ).run(tables)!!
        assertTrue("the same rows, written another way", sqlAccepts(typing, "ό,τι έγραψε", his, wanted))

        val other = SqlQuery(SqlSelect.Columns(listOf("city")), SqlTables.USERS).run(tables)!!
        assertFalse("a different answer", sqlAccepts(typing, "ό,τι έγραψε", other, wanted))
        assertFalse("nothing ran", sqlAccepts(typing, "ό,τι έγραψε", null, wanted))
    }

    @Test fun `a typed board that asked for an order wants that order`() {
        val target = SqlQuery(
            SqlSelect.Columns(listOf("name")),
            SqlTables.USERS,
            orderBy = SqlOrder("age"),
        )
        val puzzle = typing.copy(target = target)
        val wanted = target.run(tables)!!
        val backwards = target.copy(orderBy = SqlOrder("age", desc = true)).run(tables)!!
        assertTrue(sqlAccepts(puzzle, "x", wanted, wanted))
        assertFalse("the order was the question", sqlAccepts(puzzle, "x", backwards, wanted))
    }

    // -------------------------------------------------------------------- what is written

    @Test fun `the row says which question, which tables, and what he put down`() {
        val detail = sqlDetail(choosing, query = choosing.answer, ok = true, ms = 4_200, tries = 2)
        val read: Map<String, Any?> = gson.fromJson(detail, object : TypeToken<Map<String, Any?>>() {}.type)
        assertEquals(
            "the keys, in the order they were agreed",
            listOf("kind", "level", "query", "ok", "ms", "tries", "tables"),
            read.keys.toList(),
        )
        assertEquals(SqlPuzzleKind.PICK.name, read["kind"])
        assertEquals(2.0, read["level"])
        assertEquals(choosing.answer, read["query"])
        assertEquals(true, read["ok"])
        assertEquals(4_200.0, read["ms"])
        assertEquals(2.0, read["tries"])
        assertEquals(
            "which of the two worlds the question was about — the one column that makes the row answerable",
            SqlTableSet.TEXTBOOK.name, read["tables"],
        )
    }

    /** Nothing in a row is ever an id or a path: a detail leaves the phone twice. See `Adapt`. */
    @Test fun `a skip with nothing on the board writes an empty query and no key for it`() {
        val detail = sqlDetail(typing, query = "", ok = false, ms = 0, tries = 0)
        val read: Map<String, Any?> = gson.fromJson(detail, object : TypeToken<Map<String, Any?>>() {}.type)
        assertFalse("a blank is written as absence, not as an empty string", read.containsKey("query"))
        assertEquals(false, read["ok"])
    }

    // ---------------------------------------------------------- his own two tables

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 9, 10, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val names = mapOf(
        ModuleId.WORDCOACH to "Λέξεις",
        ModuleId.NUMBERS to "Αριθμοί",
        ModuleId.SQL to "SQL",
    )

    @Test fun `his words arrive as text with their Greek category and how often he has done them`() = runTest {
        val items = FakeItemDao()
        val attempts = FakeAttemptDao()
        val coffee = Item(text = "καφές", kind = ItemKind.WORD, category = Category.FOOD)
        val chemist = Item(text = "φαρμακείο", kind = ItemKind.WORD, category = Category.PLACES)
        val phrase = Item(text = "θέλω καφέ", kind = ItemKind.PHRASE, category = Category.QUICK)
        val gone = Item(text = "σβησμένη", kind = ItemKind.WORD, category = Category.CUSTOM, deleted = true)
        val number = Item(text = "7", kind = ItemKind.NUMBER, category = Category.NUMBERS)
        items.upsertAll(listOf(coffee, chemist, phrase, gone, number))
        repeat(3) { attempts.insert(attempt(coffee.id, ModuleId.WORDCOACH, at(9, it))) }
        attempts.insert(attempt(chemist.id, ModuleId.WORDCOACH, at(9, 30)))

        val words = SqlTables.words(items, attempts)

        assertEquals(listOf("λέξη", "κατηγορία", "φορές"), words.columnNames)
        assertEquals(
            "the most practised first, and a word he has never done still in the table",
            listOf(
                listOf<Any>("καφές", "Φαγητό & ποτό", 3),
                listOf<Any>("φαρμακείο", "Μέρη", 1),
                listOf<Any>("θέλω καφέ", "Γρήγορα", 0),
            ),
            words.rows,
        )
        assertTrue(
            "an id or a path reached a table he reads",
            words.rows.none { row -> row.any { it is String && (it.contains('-') || it.contains('/')) } },
        )
    }

    @Test fun `an apostrophe in a word of his never reaches a table`() = runTest {
        val items = FakeItemDao()
        items.upsert(Item(text = "'; DROP TABLE λέξεις; --", kind = ItemKind.WORD, category = Category.CUSTOM))
        items.upsert(Item(text = "  ", kind = ItemKind.WORD, category = Category.CUSTOM))
        val words = SqlTables.words(items, FakeAttemptDao())
        assertEquals(1, words.rows.size)
        assertEquals("; DROP TABLE λέξεις; --", words.rows.single()[0])
        // And a literal built from it is still one string, because the quotes are gone.
        val literal = SqlValue.Text(words.rows.single()[0] as String).literal
        assertEquals(2, literal.count { it == '\'' })
    }

    @Test fun `his mornings arrive as the time, the Greek name of the exercise, and the minutes`() = runTest {
        val sessions = FakeSessionDao()
        val attempts = FakeAttemptDao()
        val morning = Session(id = "s1", startedAt = at(9, 10), plannedModules = "WORDCOACH,NUMBERS", plannedItemCount = 4)
        sessions.upsert(morning)
        attempts.insert(attempt("w1", ModuleId.WORDCOACH, at(9, 10), session = "s1", ms = 200_000))
        attempts.insert(attempt("w2", ModuleId.WORDCOACH, at(9, 13), session = "s1", ms = 200_000))
        attempts.insert(attempt("n1", ModuleId.NUMBERS, at(9, 20), session = "s1", ms = 30_000))
        // A row from a sitting that is not one of the recent ones, and one from no sitting at all.
        attempts.insert(attempt("x", ModuleId.SQL, at(8, 0), session = null))
        // And the sitting's own summary row, which is what the fix is about: it is written against
        // whichever module was planned *last*, at the session's start and for the whole session's
        // length. Counted, it gave «Αριθμοί» the wrong hour and the whole morning's minutes.
        attempts.insert(
            attempt(SessionViewModel.SESSION_SUMMARY, ModuleId.NUMBERS, at(9, 10), session = "s1", ms = 660_000)
        )

        val day = SqlTables.day(sessions, attempts, names, zone)

        assertEquals(listOf("ώρα", "δραστηριότητα", "λεπτά"), day.columnNames)
        assertEquals(
            listOf(
                listOf<Any>("09:10", "Λέξεις", 6),
                // Half a minute of numbers is still a minute: it is the smallest thing this column
                // can honestly say about a morning.
                listOf<Any>("09:20", "Αριθμοί", 1),
            ),
            day.rows,
        )
    }

    /**
     * The same rule, said on its own: a sitting whose only row is the summary is a sitting with
     * nothing in this table. Without the exclusion it would have been one row of eleven minutes
     * against a module he did not do.
     */
    @Test fun `a sitting that left only a summary row leaves no morning behind`() = runTest {
        val sessions = FakeSessionDao()
        val attempts = FakeAttemptDao()
        sessions.upsert(Session(id = "s", startedAt = at(9, 0), plannedModules = "TRACE", plannedItemCount = 3))
        attempts.insert(
            attempt(SessionViewModel.SESSION_SUMMARY, ModuleId.WORDCOACH, at(9, 0), session = "s", ms = 660_000)
        )

        assertEquals(emptyList<List<Any>>(), SqlTables.day(sessions, attempts, names, zone).rows)
    }

    /**
     * And the same id is what `λέξεις` keeps out of its counts. It used to pass the talk board's
     * expansion, which cannot appear in a query already filtered to `module = WORDCOACH` — while the
     * summary row, which is written against whichever module was planned last, perfectly well can.
     */
    @Test fun `the sitting's summary row is not a word he has practised`() = runTest {
        val items = FakeItemDao()
        val attempts = FakeAttemptDao()
        val coffee = Item(text = "καφές", kind = ItemKind.WORD, category = Category.FOOD)
        items.upsert(coffee)
        attempts.insert(attempt(coffee.id, ModuleId.WORDCOACH, at(9, 0)))
        repeat(5) { attempts.insert(attempt(SessionViewModel.SESSION_SUMMARY, ModuleId.WORDCOACH, at(9, it))) }

        val words = SqlTables.words(items, attempts)
        assertEquals(listOf(listOf<Any>("καφές", "Φαγητό & ποτό", 1)), words.rows)
    }

    @Test fun `a phone nobody has practised on has no tables of his own, and the textbook is the module`() = runTest {
        val read = SqlTables.load(FakeItemDao(), FakeAttemptDao(), FakeSessionDao(), names, zone)

        assertFalse("his words were asked about on an empty phone", read.usable(SqlTables.WORDS))
        assertFalse("his mornings were asked about on an empty phone", read.usable(SqlTables.DAY))
        assertTrue(read.usable(SqlTables.USERS))
        assertTrue(read.usable(SqlTables.ORDERS))
        assertEquals(12, read.table(SqlTables.USERS)!!.rows.size)
        assertEquals(12, read.table(SqlTables.ORDERS)!!.rows.size)
        // And the sitting it produces is a real sitting.
        val random = Random(1)
        for (level in SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL) {
            val puzzle = SqlPuzzles.generate(level, read, random)
            assertNotNull(puzzle.target.run(read))
            assertEquals(SqlTableSet.TEXTBOOK, puzzle.set)
        }
    }

    @Test fun `a phone with practice on it carries all four tables`() = runTest {
        val items = FakeItemDao()
        val attempts = FakeAttemptDao()
        val sessions = FakeSessionDao()
        repeat(SqlTables.MIN_ROWS) { n ->
            items.upsert(Item(text = "λέξη$n", kind = ItemKind.WORD, category = Category.THINGS))
        }
        sessions.upsert(Session(id = "s", startedAt = at(10, 0), plannedModules = "WORDCOACH", plannedItemCount = 1))
        repeat(SqlTables.MIN_ROWS) { n ->
            attempts.insert(attempt("i$n", ModuleId.WORDCOACH, at(10, n), session = "s"))
        }

        val read = SqlTables.load(items, attempts, sessions, names, zone)
        assertTrue(read.usable(SqlTables.WORDS))
        assertEquals("all four", 4, read.tables.size)
        // `μέρα` groups by sitting and module, so six attempts in one module is one row: still thin,
        // and thin is left alone rather than asked about.
        assertFalse(read.usable(SqlTables.DAY))
    }

    private fun attempt(
        itemId: String,
        module: ModuleId,
        at: Long,
        session: String? = null,
        ms: Long = 1_000,
    ) = Attempt(
        itemId = itemId, module = module, sessionId = session, startedAt = at, durationMs = ms,
        outcome = Outcome.CORRECT,
    )

    @Test fun `nothing in a detail is ever a null`() {
        val detail = sqlDetail(ordering, query = ordering.orderedAnswer, ok = false, ms = 1, tries = 1)
        assertFalse(detail.contains("null"))
        assertNull("a detail is one flat object", gson.fromJson(detail, Map::class.java)["detail"])
    }
}
