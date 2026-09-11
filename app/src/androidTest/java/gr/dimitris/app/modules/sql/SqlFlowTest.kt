package gr.dimitris.app.modules.sql

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.today.MODULE_GRID_TAG
import gr.dimitris.app.ui.components.LISTEN_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The things about «SQL» that only a device can answer: that a query he puts in order is his own
 * answer, and that a query he *types* is really run against a real SQLite and really compared with
 * what the question asked for.
 *
 * The generator's own contract is argued with on the JVM over five hundred draws a level
 * (`SqlPuzzlesTest`) and the database half on a device (`androidTest`'s `SqlRunnerTest`). What is left
 * for a flow test is the wiring: the tiles reach the screen, the keyboard reaches the runner, and the
 * row that comes out of it says what he did.
 */
class SqlFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var levelBefore = SqlPuzzles.MIN_LEVEL
    private var since = 0L

    @Before fun rememberLevel() = runBlocking<Unit> {
        levelBefore = graph.settings.sqlLevel.first()
        since = System.currentTimeMillis()
    }

    /** His level is his; a test that borrows it puts it back. */
    @After fun restoreLevel() = runBlocking<Unit> { graph.settings.setSqlLevel(levelBefore) }

    /**
     * Level 1: three or four words of SQL, laid down in the order the query has them, and the row
     * that says he found it himself.
     */
    @Test fun theRightOrderIsAnAnswerOfHisOwn() {
        val tiles = openAt(SqlPuzzles.MIN_LEVEL)
        assertTrue("level 1 is three or four tiles: $tiles", tiles.size in 3..4)

        // The bottom block is the three «docs/UX.md» allows, in the order it says: «Άκου», «Έτοιμο»,
        // «Παράλειψη» — and «Έτοιμο» is dead until the board is full.
        assertThreeActionsInTheBottom()

        val order = rightOrder(tiles)
        // A tile he changes his mind about comes back off by tapping it in the strip — the undo, next
        // to the thing it is about, because the bottom block is already full.
        tapInOrder(listOf(order.last()))
        assertEquals(listOf(order.last()), textsOf(SQL_CHOSEN_TAG))
        tapChosen(listOf(order.last()))
        assertTrue("the tile did not come back off", textsOf(SQL_CHOSEN_TAG).isEmpty())

        tapInOrder(order)
        compose.onNodeWithText(READY).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.outcome == Outcome.CORRECT } }
        val row = attempts().single()
        assertEquals("a level-1 row", "sql:level:1", row.itemId)
        assertEquals(ModuleId.SQL, row.module)
        assertTrue("the row does not say which kind of question: ${row.detail}", row.detail.contains("\"kind\":\"ORDER\""))
        assertTrue("nor which world the tables came from: ${row.detail}", row.detail.contains("\"tables\""))
        compose.onNodeWithText(NEXT).assertIsDisplayed()
    }

    /** A wrong order is a nudge and another go; the second is the answer on the screen, and helped work. */
    @Test fun aWrongOrderIsANudgeAndThenTheAnswer() {
        val tiles = openAt(SqlPuzzles.MIN_LEVEL)
        val wrong = rightOrder(tiles).reversed()

        tapInOrder(wrong)
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(AGAIN)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a miss was written down as an attempt", attempts().isEmpty())
        // Never a dead end: the tiles are handed back whole, and he may build it again as he likes.
        assertTrue("the board was not handed back", textsOf(SQL_CHOSEN_TAG).isEmpty())

        tapInOrder(wrong)
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.outcome == Outcome.ASSISTED } }
        compose.onNodeWithText(HERE_IT_IS).performScrollTo().assertIsDisplayed()
        assertEquals("one puzzle, one row", 1, attempts().size)
    }

    /**
     * Level 4: the result on the screen, a keyboard, and a query of his run against the real
     * in-memory database.
     *
     * Three goes, in the order he would have them. A typo first — which is answered in Greek with
     * SQLite's own English under it and **costs him nothing**, because fixing a typo is the part of
     * this he can still do. Then a statement that is not a question at all, refused by name. Then the
     * query the Greek asked for, which is his own work and the row says so.
     */
    @Test fun aTypedQueryIsRunAgainstTheDatabaseAndComparedWithWhatWasAsked() {
        openAt(4)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SQL_TYPED_TAG).fetchSemanticsNodes().isNotEmpty() }
        // The answer's own result is the question: it is on the screen before he writes a word.
        compose.onNodeWithText(WANTED).performScrollTo().assertIsDisplayed()
        val asked = question()
        assertTrue("the question does not say what to write: $asked", asked.startsWith(SqlPuzzles.WRITE_IT))

        type("SELECT λέξη FORM λέξεις")
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(SqlRunner.BROKEN)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a typo was written down as an answer", attempts().isEmpty())

        type("DELETE FROM users")
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(SqlGuard.ONLY_SELECT)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a write was written down as an answer", attempts().isEmpty())

        type(queryFor(asked))
        compose.onNodeWithText(READY).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val row = attempts().single()
        assertEquals("a level-4 row", "sql:level:4", row.itemId)
        assertEquals("a query he wrote himself is his own work, typos and all", Outcome.CORRECT, row.outcome)
        assertTrue("the row does not say which kind: ${row.detail}", row.detail.contains("\"kind\":\"WRITE\""))
        assertTrue("nor how many goes he had: ${row.detail}", row.detail.contains("\"tries\":3"))
        compose.onNodeWithText(NEXT).assertIsDisplayed()
    }

    /**
     * A query that runs away gives the board back: «Η ερώτηση άργησε πολύ.», «Έτοιμο» live again, and
     * nothing written down.
     *
     * Two seconds is what the runner is allowed ([SqlRunner.TIMEOUT_MS]), and until the watchdog
     * landed there was no way to stop a runaway at all: he could type a sloppy multi-table `FROM`,
     * press «Έτοιμο», and sit on «Τρέχω...» with the button dead for as long as SQLite took.
     */
    @Test fun aRunawayQueryGivesTheBoardBack() {
        openAt(4)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SQL_TYPED_TAG).fetchSemanticsNodes().isNotEmpty() }

        val began = System.currentTimeMillis()
        type(BOMB)
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText(SqlRunner.TOO_SLOW)).fetchSemanticsNodes().isNotEmpty()
        }
        val took = System.currentTimeMillis() - began
        assertTrue("the board waited $took ms for a two-second limit", took < SqlRunner.TIMEOUT_MS + 8_000)

        // The board is his again: «Έτοιμο» is live, and a query that ran away is not an answer.
        compose.onNodeWithText(READY).assertIsEnabled()
        assertTrue("a query that never finished was written down", attempts().isEmpty())

        type(queryFor(question()))
        compose.onNodeWithText(READY).performClick()
        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        assertEquals("and the answer after it still counts", Outcome.CORRECT, attempts().single().outcome)
    }

    /**
     * «Παράλειψη» is refused while a query of his is still inside SQLite, and one submission leaves
     * exactly one row.
     *
     * Both halves of the same defect. `runTyped`'s coroutine used to settle onto whatever board was
     * on the screen when SQLite came back — so «Έτοιμο» and then «Παράλειψη» in the same second wrote
     * *two* rows for the first puzzle, added a second entry to the list the promotion is decided from,
     * and painted a verdict on a question he had not answered. The board is checked again on the way
     * out now, and the skip is refused while it could happen at all.
     *
     * Driven through the ViewModel: the race is a frame wide on a screen, and a click-and-wait test
     * could never land inside it.
     */
    @Test fun aSkipIsRefusedWhileHisQueryIsStillRunning() {
        runBlocking { graph.settings.setSqlLevel(4) }
        lateinit var vm: SqlViewModel
        compose.runOnUiThread { vm = SqlViewModel(graph, sessionId = null) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.puzzle != null }

        compose.runOnUiThread { vm.onTypedChange(BOMB); vm.submit() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.running }
        compose.runOnUiThread { vm.skip() }
        assertEquals("«Παράλειψη» passed on a board with an answer in flight", 0, vm.state.value.index)

        compose.waitUntil(TIMEOUT_MS) { !vm.state.value.running }
        assertEquals(SqlRunner.TOO_SLOW, vm.state.value.refusal)
        assertTrue("a query that never finished was written down", attempts().isEmpty())
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * A right query handed in before the target grid has arrived is still right.
     *
     * `sqlAccepts` needs the target's own result to compare against, and it used to be read straight
     * out of state — so a man who reads the question and starts typing at once could hand in a
     * perfectly correct query while the target was still being run, be told «Ξανά.», and then be
     * shown the answer he had already written. The target is waited for now, so a verdict can never
     * exist without one.
     */
    @Test fun aRightQueryIsNotJudgedBeforeTheTargetHasArrived() {
        runBlocking { graph.settings.setSqlLevel(4) }
        lateinit var vm: SqlViewModel
        compose.runOnUiThread { vm = SqlViewModel(graph, sessionId = null) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.puzzle != null }

        // Straight in, without waiting for «Θέλουμε αυτό:» to be drawn.
        val asked = vm.state.value.puzzle!!.question
        compose.runOnUiThread { vm.onTypedChange(queryFor(asked)); vm.submit() }

        compose.waitUntil(TIMEOUT_MS) { vm.state.value.correct != null }
        assertNotNull("judged with no target to judge against", vm.state.value.wanted)
        assertEquals("a right query was called wrong: $asked", true, vm.state.value.correct)
        compose.runOnUiThread { vm.leave {} }
    }

    /** «Άκου» reads the question and nothing else, so it is live from the first frame and costs nothing. */
    @Test fun theQuestionCanBeHeardAndHearingItCostsNothing() {
        val tiles = openAt(SqlPuzzles.MIN_LEVEL)
        compose.onNodeWithTag(LISTEN_TAG).performClick()
        tapInOrder(rightOrder(tiles))
        compose.onNodeWithText(READY).performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        assertEquals(
            "hearing the question is not being given the answer",
            Outcome.CORRECT, attempts().single().outcome,
        )
    }

    /**
     * **The same wrong option twice is one go, not two.**
     *
     * Two misses put the answer on the screen ([SqlViewModel.WRONG_TRIES_BEFORE_REVEAL]), and a tile
     * under a thumb that is not always steady is tapped twice oftener than anybody would like: a double
     * tap used to spend both goes and reveal the answer to a question he had answered once. A
     * *different* wrong option is a real second go and still reveals — the rule is unchanged, only the
     * repeat is ignored.
     *
     * Driven through the ViewModel, because two taps inside one frame is the race and a click-and-wait
     * test cannot promise them; everything it drives is what the option tiles call.
     */
    @Test fun twoTapsOnTheSameWrongOptionAreOneGo() {
        runBlocking { graph.settings.setSqlLevel(2) }
        lateinit var vm: SqlViewModel
        compose.runOnUiThread { vm = SqlViewModel(graph, sessionId = null) }
        try {
            compose.waitUntil(TIMEOUT_MS) { vm.state.value.puzzle?.kind == SqlPuzzleKind.PICK }
            val puzzle = vm.state.value.puzzle!!
            val wrong = puzzle.options.filter { it != puzzle.answer }
            assertEquals("a choosing board has two wrong options", 2, wrong.size)

            compose.runOnUiThread { vm.choose(wrong[0]); vm.choose(wrong[0]) }
            assertEquals("the same option twice was two goes", 1, vm.state.value.wrongTries)
            assertTrue("a double tap showed him the answer", !vm.state.value.revealed)
            assertTrue("a double tap ended the puzzle", vm.state.value.correct == false)

            // The other wrong option is a second answer, and the second answer is where the reveal is.
            compose.runOnUiThread { vm.choose(wrong[1]) }
            assertEquals(2, vm.state.value.wrongTries)
            assertTrue("a real second miss did not show him the answer", vm.state.value.revealed)
            compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
            assertEquals("one puzzle, one row", 1, attempts().size)
            assertTrue("the row does not say how many goes: ${attempts().single().detail}", attempts().single().detail.contains("\"tries\":2"))
        } finally {
            compose.runOnUiThread { vm.leave {} }
        }
    }

    // ------------------------------------------------------------------------- helpers

    /** Opens free practice at [level] and returns the tiles on the board, as they are laid out. */
    private fun openAt(level: Int): List<String> {
        runBlocking { graph.settings.setSqlLevel(level) }
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(TILE))
        compose.onNodeWithText(TILE).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(SQL_TILE_TAG).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(SQL_TYPED_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        return textsOf(SQL_TILE_TAG)
    }

    /**
     * The order the tiles have to go in, worked out the way he would: `SELECT` first, then the column
     * if one is named, then `FROM`, then the table. The four table names are the only thing this has
     * to know, and they are [SqlTables]' own.
     */
    private fun rightOrder(tiles: List<String>): List<String> {
        val select = tiles.first { it.startsWith("SELECT") }
        val from = tiles.first { it == "FROM" }
        val table = tiles.first { it in TABLES }
        val column = tiles.firstOrNull { it != select && it != from && it != table }
        return listOfNotNull(select, column, from, table)
    }

    /**
     * Lays the tiles down in [tiles]' own order.
     *
     * Scrolled to first, every time. The board is below a question, a table shape and the strip, so a
     * tile can sit under the fold on a short screen — and a `performClick` on a node whose centre is
     * off the window is a tap that lands nowhere and is silently lost, which reads here as "the right
     * order was refused".
     */
    private fun tapInOrder(tiles: List<String>) = tiles.forEach { tile ->
        compose.onNode(hasTestTag(SQL_TILE_TAG) and hasText(tile)).performScrollTo().performClick()
    }

    /** Takes every tile back off the strip, which is the undo this screen has. */
    private fun tapChosen(tiles: List<String>) = tiles.forEach { tile ->
        compose.onNode(hasTestTag(SQL_CHOSEN_TAG) and hasText(tile)).performScrollTo().performClick()
    }

    private fun type(sql: String) {
        compose.onNodeWithTag(SQL_TYPED_TAG).performTextClearance()
        compose.onNodeWithTag(SQL_TYPED_TAG).performTextInput(sql)
    }

    private fun textsOf(tag: String): List<String> = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    /** The Greek question as it is on the screen. */
    private fun question(): String = compose.onAllNodes(hasText(SqlPuzzles.WRITE_IT, substring = true))
        .fetchSemanticsNodes()
        .firstNotNullOf { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    /**
     * The query the Greek asked for, read back out of the question.
     *
     * It is a test of the question as much as a way of answering it: a level-4 board is unanswerable
     * unless its one Greek line says which table, which column and which condition, and this is that
     * claim made checkable. See [SqlPuzzles.goal] for the shape it parses.
     */
    private fun queryFor(asked: String): String {
        val goal = asked.substringAfter(SqlPuzzles.WRITE_IT).trim().trimEnd('.')
        val table = FROM_TABLE.find(goal)?.groupValues?.get(1) ?: error("no table in: $goal")
        // Anchored on the table's own name rather than on the first comma: a word of his could have
        // one in it, and the sentence would then be cut in the wrong place.
        var rest = goal.substringAfter("πίνακα $table, ")
        val select = when {
            rest.startsWith(COUNT_THEM) -> "COUNT(*)"
            rest.startsWith(EVERY_COLUMN) -> "*"
            else -> ONE_COLUMN.find(rest)?.groupValues?.get(1) ?: error("no column in: $rest")
        }
        val sorted = SORTED_BY.find(rest)
        if (sorted != null) rest = rest.removeRange(sorted.range)
        val where = WHERE.find(rest)?.groupValues?.get(1)
        return buildString {
            append("SELECT ").append(select).append(" FROM ").append(table)
            where?.let { append(" WHERE ").append(it) }
            sorted?.let {
                append(" ORDER BY ").append(it.groupValues[1])
                if (it.groupValues[2].isNotEmpty()) append(" DESC")
            }
        }
    }

    /**
     * The bottom block is the three actions it is allowed, in the order «docs/UX.md» says: «Άκου»
     * first, «Έτοιμο» under it, «Παράλειψη» last. Read by position, which is the only thing a
     * semantics tree can see.
     */
    private fun assertThreeActionsInTheBottom() {
        val listen = compose.onNodeWithTag(LISTEN_TAG).fetchSemanticsNode().boundsInRoot.top
        val ready = compose.onNodeWithText(READY).fetchSemanticsNode().boundsInRoot.top
        val skip = compose.onNodeWithText(SKIP).fetchSemanticsNode().boundsInRoot.top
        assertTrue("«Άκου» is not the first of the three: $listen vs $ready", listen < ready)
        assertTrue("«Έτοιμο» is not above «Παράλειψη»: $ready vs $skip", ready < skip)
    }

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(since) }.filter { it.module == ModuleId.SQL }

    private companion object {
        const val TILE = "SQL"
        const val READY = "Έτοιμο"
        const val SKIP = "Παράλειψη"
        const val NEXT = "Επόμενο"
        const val AGAIN = "Ξανά."
        const val HERE_IT_IS = "Να το σωστό."
        const val WANTED = "Θέλουμε αυτό:"
        const val TIMEOUT_MS = 20_000L

        /** See `androidTest/SqlRunnerTest.BOMB`: 35 million rows of string work, and guard-legal. */
        const val BOMB =
            "SELECT COUNT(*) FROM users a, users b, users c, users d, users e, users f, users g " +
                "WHERE a.name || b.name || c.name || d.name || e.name || f.name || g.name LIKE '%ζ%'"

        val TABLES = setOf(SqlTables.WORDS, SqlTables.DAY, SqlTables.USERS, SqlTables.ORDERS)

        const val COUNT_THEM = "μέτρησε τις γραμμές"
        const val EVERY_COLUMN = "δείξε όλες τις στήλες"
        val FROM_TABLE = Regex("Από τον πίνακα (\\S+?),")
        val ONE_COLUMN = Regex("δείξε τη στήλη (\\S+)")
        val SORTED_BY = Regex(" ταξινομημένα κατά (\\S+)( ανάποδα)?$")
        val WHERE = Regex(" όπου (.+)$")
    }
}
