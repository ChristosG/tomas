package gr.dimitris.app.modules.trace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.today.MODULE_GRID_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The thing about writing that only a finger on glass can answer: that the letter he draws is read
 * as the letter he was asked for.
 *
 * Every passing trace here is a *hand-like* one — a line down the middle of each stroke of the
 * glyph, the strokes separated by lifting the finger, worked out by [HandTrace] from the letter's
 * own ink. Tracing the outline the app drew would only measure the scorer against itself; what has
 * to hold is that writing the letter the way a person writes it is a pass, at every level, and that
 * a scribble or the wrong letter is not.
 */
class TraceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var levelBefore = 1
    private var since = 0L

    @Before fun rememberLevel() = runBlocking<Unit> {
        levelBefore = graph.settings.traceLevel.first()
        since = System.currentTimeMillis()
    }

    /** His level is his; a test that borrows it puts it back. */
    @After fun restoreLevel() = runBlocking<Unit> { graph.settings.setTraceLevel(levelBefore) }

    @Test fun aHandLikeTraceOfACapitalIsWritingIt() {
        val letter = openPractice(level = 1)
        write(letter)
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val attempt = attempts().single()
        assertEquals("a letter written by hand was not his own answer", Outcome.CORRECT, attempt.outcome)
        // A random capital is not an item anything can look up, so the row is about the level.
        assertEquals("trace:level:1", attempt.itemId)
        assertTrue("the hand he was told to use is not on the row", attempt.detail.contains("\"hand\""))
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }

    /** Level 3 is his own name: eight letters in one box, and the tolerance that comes with them. */
    @Test fun aHandLikeTraceOfHisOwnNameIsWritingIt() {
        val name = openPractice(level = 3)
        assertEquals("Δημήτρης", name)
        write(name)
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        assertEquals(Outcome.CORRECT, attempts().single().outcome)
    }

    /** Level 4 is a word off his own talk board, and it has to be as writeable as his name. */
    @Test fun aHandLikeTraceOfOneOfHisWordsIsWritingIt() {
        waitForVocabulary()
        val word = openPractice(level = 4)
        write(word)
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val attempt = attempts().single()
        assertEquals(Outcome.CORRECT, attempt.outcome)
        // Level 4 is the one level whose row belongs to the word itself.
        assertTrue("a word he practised was recorded against the level", attempt.itemId.startsWith(ITEM_ID_PREFIX).not())
    }

    /**
     * Level 5 is the recall exercise, and it is not begun until he has taken the letter away: until
     * then «Έτοιμο» is dead, so the cheap route — trace what is on the screen and be marked as
     * though it had not been — does not exist. Once he passes, the word comes back to compare.
     *
     * «Το είδα» takes the paper with the letter, so tracing it first and hiding it afterwards is not
     * a way through either: what is marked is only what he wrote once the word was gone.
     */
    @Test fun levelFiveIsNotBegunUntilHeHasTakenTheLetterAway() {
        val word = openPractice(level = 5)
        // Nothing drawn: «Έτοιμο» would be a nudge and a spent try, so it is not offered.
        compose.onNodeWithText("Έτοιμο").assertIsNotEnabled()
        write(word)
        compose.onNodeWithText("Έτοιμο").assertIsNotEnabled()

        compose.onNodeWithText("Το είδα").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(TRACE_TEXT_HIDDEN_TAG).fetchSemanticsNodes().isNotEmpty() }
        // The word he traced while it was still on the screen went with it: there is nothing on the
        // paper to hand in, so «Έτοιμο» is still dead.
        compose.onNodeWithText("Έτοιμο").assertIsNotEnabled()

        // Now he writes it, with nothing to follow. This is the exercise.
        write(word)
        compose.onNodeWithText("Έτοιμο").assertIsEnabled()
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        assertEquals(Outcome.CORRECT, attempts().single().outcome)
        assertEquals("a recall row belongs to the level, not the word", "trace:level:5", attempts().single().itemId)
        // He has earned the look: the word and the letter come back over what he wrote.
        compose.onNodeWithTag(TRACE_TEXT_TAG).assertIsDisplayed()
    }

    @Test fun aTraceNowhereNearTheLetterIsANudgeAndAnotherGo() {
        openPractice(level = 1)
        val (width, height) = canvasSize()
        // A scribble in the middle of the paper: over the letter, and no part of it written.
        compose.onNodeWithTag(TRACE_CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.45f, height * 0.48f))
            moveTo(Offset(width * 0.55f, height * 0.50f))
            moveTo(Offset(width * 0.45f, height * 0.52f))
            moveTo(Offset(width * 0.55f, height * 0.54f))
            up()
        }
        finish()

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(TraceViewModel.TRY_AGAIN)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(TraceViewModel.TRY_AGAIN).assertIsDisplayed()
        // Never a fail state: nothing is written until a letter is finished or passed on, and the
        // letter is still there to be gone over again.
        assertTrue("a poor trace was recorded as an attempt", attempts().isEmpty())
        compose.onNodeWithText("Έτοιμο").assertIsDisplayed()
    }

    /** Written beautifully, and not the letter he was asked for. */
    @Test fun theWrongLetterIsNotTheLetter() {
        val letter = openPractice(level = 1)
        write(if (letter == OTHER_LETTER) "Ο" else OTHER_LETTER)
        finish()

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(TraceViewModel.TRY_AGAIN)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the wrong letter was recorded as an attempt", attempts().isEmpty())
    }

    /**
     * The field test, on the glass: Chris drew a «Κ» over an «Η» and the app said well done.
     *
     * A «Κ» is the wrong letter that comes nearest to being right — over an «Η» it shares the whole
     * left stem, so half of what he draws is on the ink — and it has to be refused for its *shape*:
     * the crossbar of the «Η» is never gone over, and the diagonals of the «Κ» are out in the white.
     * A man told "well done" for the wrong letter is a man practising the wrong movement.
     *
     * Level 1 asks for a random capital, so the «Κ» goes over whichever one came up — and over a
     * «Κ» itself an «Ο» goes instead, since a «Κ» over a «Κ» is the letter. The «Κ»-over-«Η» pair
     * exactly, with the device's own font, is measured in [GlyphsTest].
     */
    @Test fun aKDrawnOverTheLetterIsNudgedAndWritesNoRow() {
        val letter = openPractice(level = 1)
        val drawn = if (letter == WRONG_LETTER) "Ο" else WRONG_LETTER
        write(drawn)
        finish()

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(TraceViewModel.TRY_AGAIN)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(TraceViewModel.TRY_AGAIN).assertIsDisplayed()
        assertTrue("a «$drawn» passed as a «$letter»", attempts().isEmpty())
        // Never a fail state: the letter is still there, and «Έτοιμο» is still the way on.
        compose.onNodeWithText("Έτοιμο").assertIsDisplayed()
    }

    /**
     * His own name, which is level 3 and the same eight letters every time — so unlike the shuffled
     * capitals above, what this asks is the same on every run: a whole word written by hand is the
     * word, and another word written just as well over it is not.
     */
    @Test fun aDifferentWordOverHisNameIsNotHisName() {
        val name = openPractice(level = 3)
        assertEquals("Δημήτρης", name)
        write(OTHER_WORD)
        finish()

        waitForNudge()
        assertTrue("«$OTHER_WORD» passed as «$name»", attempts().isEmpty())
    }

    /**
     * The field test of this round, on the glass: his name written properly except that one «η» of
     * it is a «Κ». Seven letters right out of eight is seven letters right — and the nudge has to
     * say *which* letter, because «Ξανά» over a word of eight is not something a man with aphasia
     * can act on.
     */
    @Test fun aKOverOneLetterOfHisNameNamesThatLetter() {
        val name = openPractice(level = 3)
        val (width, height) = canvasSize()
        val glyph = Glyphs.template(name, width, height)
        val eta = glyph.letters.indexOfLast { it.text == WRONG_IN_NAME }
        assertTrue("no «$WRONG_IN_NAME» in «$name»", eta > 0)
        val its = glyph.points.filter { it.letter == eta }
        val left = its.minOf { it.pt.x }
        val right = its.maxOf { it.pt.x }
        val top = its.minOf { it.pt.y }
        val bottom = its.maxOf { it.pt.y }
        val middle = (top + bottom) / 2f

        // Every letter but that one, written by hand, and a «Κ» where it should have been.
        val hand = HandTrace.centreLine(glyph)
        draw(hand.filter { stroke -> stroke.map { it.x }.average() !in left.toDouble()..right.toDouble() })
        draw(
            listOf(
                listOf(Pt(left, top), Pt(left, bottom)),
                listOf(Pt(right, top), Pt(left, middle)),
                listOf(Pt(left, middle), Pt(right, bottom)),
            )
        )
        finish()

        waitForNudge()
        compose.onNodeWithText("${TraceViewModel.TRY_AGAIN} — δες το «$WRONG_IN_NAME».").assertIsDisplayed()
        assertTrue("a «Κ» over one «$WRONG_IN_NAME» passed as «$name»", attempts().isEmpty())
    }

    /**
     * The letter written correctly after a try that missed, without wiping the paper first.
     *
     * The ink of the miss stays on the paper — faded — so he can see where he went, and it is not
     * marked again: what he writes after a nudge is a new attempt. Without that, the wrong first try
     * is still half the ink on the paper, a perfect second try is refused for it, and a man who
     * cannot ask why is left pressing «Έτοιμο» over a letter he has just written correctly. Twice
     * wrong and then right, because two misses used to be unrecoverable without «Καθάρισε».
     */
    @Test fun theLetterWrittenCorrectlyAfterTwoMissesIsAPass() {
        val letter = openPractice(level = 1)
        val wrong = if (letter == WRONG_LETTER) "Ο" else WRONG_LETTER
        write(wrong)
        finish()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(TraceViewModel.TRY_AGAIN)).fetchSemanticsNodes().isNotEmpty() }
        write(wrong)
        finish()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(TraceViewModel.TRY_AGAIN)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a miss was recorded as an attempt", attempts().isEmpty())

        // Now the letter, written over the two tries that missed. Nothing is wiped.
        write(letter)
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val attempt = attempts().single()
        assertEquals("the letter written after two misses was refused", Outcome.ASSISTED, attempt.outcome)
        assertTrue("the two tries that missed are not on the row", attempt.detail.contains("\"tries\":2"))
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }

    /** Sets the level, opens free practice and returns what it is asking him to write. */
    private fun openPractice(level: Int): String {
        runBlocking { graph.settings.setTraceLevel(level) }
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(TITLE))
        compose.onNodeWithText(TITLE).performClick()
        // The level read has to land before there is a letter, and its canvas before there is paper.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(TRACE_CANVAS_TAG).fetchSemanticsNodes().any { it.size.width > 0 && it.size.height > 0 }
        }
        val text = compose.onNodeWithTag(TRACE_TEXT_TAG).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        assertTrue("nothing to write", !text.isNullOrBlank())
        return text!!
    }

    /** The seed vocabulary arrives on first launch; levels 4 and 5 have nothing to offer before it. */
    private fun waitForVocabulary() = compose.waitUntil(TIMEOUT_MS) {
        runBlocking { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }.isNotEmpty()
    }

    /** The canvas as the app measured it, which is the box the letter was laid out in. */
    private fun canvasSize(): Pair<Float, Float> {
        val size = compose.onNodeWithTag(TRACE_CANVAS_TAG).fetchSemanticsNode().size
        return size.width.toFloat() to size.height.toFloat()
    }

    /** Writes [text] on the paper the way a hand would: down the middle of every stroke of it. */
    private fun write(text: String) {
        val (width, height) = canvasSize()
        val strokes = HandTrace.centreLine(Glyphs.template(text, width, height))
        assertTrue("«$text» has no strokes to write in a ${width}x$height box", strokes.isNotEmpty())
        draw(strokes)
    }

    /** Whatever he drew, put on the paper one stroke at a time, his finger lifted between them. */
    private fun draw(strokes: List<List<Pt>>) {
        for (stroke in strokes) {
            compose.onNodeWithTag(TRACE_CANVAS_TAG).performTouchInput {
                down(Offset(stroke.first().x, stroke.first().y))
                stroke.forEach { moveTo(Offset(it.x, it.y)) }
                up()
            }
        }
    }

    /** Waits for the nudge, whichever letter it goes on to name. */
    private fun waitForNudge() = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(hasText(TraceViewModel.TRY_AGAIN, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun finish() = compose.onNodeWithText("Έτοιμο").performClick()

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(since) }.filter { it.module == ModuleId.TRACE }

    private companion object {
        const val TITLE = "Γράψε"
        const val ITEM_ID_PREFIX = "trace:level:"

        /** A bare stem: whatever else came up, this is not it. */
        const val OTHER_LETTER = "Ι"

        /** The letter that comes nearest to being another one, which is why it is the test. */
        const val WRONG_LETTER = "Κ"

        /** Not his name, and near enough to it that the shape is what refuses it. */
        const val OTHER_WORD = "Δημητρα"

        /** The letter of his name that gets written as a «Κ» instead. */
        const val WRONG_IN_NAME = "η"
        const val TIMEOUT_MS = 20_000L
    }
}
