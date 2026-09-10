package gr.dimitris.app.modules.trace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.judge.JudgeClient
import gr.dimitris.app.core.judge.TurnJudge
import gr.dimitris.app.today.MODULE_GRID_TAG
import gr.dimitris.app.ui.components.LISTEN_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The judge the typed cases run against: the emulator can reach the Anthropic API no more than a mic. */
    private var realJudge: TurnJudge? = null

    /** What the fake client answers, reply by reply. */
    private val replies = ArrayDeque<String>()

    @Before fun rememberLevel() = runBlocking<Unit> {
        levelBefore = graph.settings.traceLevel.first()
        since = System.currentTimeMillis()
    }

    /** His level is his; a test that borrows it puts it back, and so is the judge. */
    @After fun restoreLevel() = runBlocking<Unit> {
        graph.settings.setTraceLevel(levelBefore)
        realJudge?.let { graph.judge = it }
    }

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

    /**
     * Level 3 is a word off his own talk board, written with his finger. It was his own name until
     * phase 13, and the name is now only what a device with no vocabulary falls back on: he knows all
     * his letters, and tracing «Δημήτρης» six times is copying rather than writing.
     */
    @Test fun aHandLikeTraceOfOneOfHisWordsIsWritingIt() {
        waitForVocabulary()
        val word = openPractice(level = 3)
        assertTrue("level 3 owes him a word, not a letter: «$word»", word.length > 1)
        write(word)
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val attempt = attempts().single()
        assertEquals(Outcome.CORRECT, attempt.outcome)
        // The word levels are the ones whose row belongs to the word itself.
        assertTrue("a word he practised was recorded against the level", attempt.itemId.startsWith(ITEM_ID_PREFIX).not())
    }

    /**
     * Writing from memory, which phase 13 moved *inside* the word level as that level's own
     * progression: a word written with no help at all earns him the next one with nothing to follow.
     *
     * The recall word is not begun until he has taken the word away: until then there is no «Έτοιμο»
     * at all, so the cheap route — trace what is on the screen and be marked as though it had not been
     * — does not exist. «Το είδα» takes the paper with the word, so tracing it first and hiding it
     * afterwards is not a way through either. Once he passes, the word comes back to compare.
     *
     * The two share one slot rather than standing one above the other (phase 12's UX audit): the
     * bottom area holds three actions at every level, and the primary is whichever of «Το είδα» and
     * «Έτοιμο» is the real next step.
     */
    @Test fun aWordWrittenWithNoHelpEarnsTheNextOneFromMemory() {
        waitForVocabulary()
        val first = openPractice(level = 3)
        // The first word of a sitting is never from memory: he has not earned it yet, and there is
        // nothing on the screen to have remembered.
        compose.onNodeWithText("Το είδα").assertDoesNotExist()
        write(first)
        finish()
        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        assertEquals("a word written by hand was not his own answer", Outcome.CORRECT, attempts().single().outcome)

        // On to the next word, which he has now earned the right to write from memory.
        compose.onNodeWithText("Επόμενο").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Το είδα")).fetchSemanticsNodes().isNotEmpty() }
        val word = compose.onNodeWithTag(TRACE_TEXT_TAG).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        assertTrue("nothing to remember", !word.isNullOrBlank())
        // The word is still on the paper, so the slot holds «Το είδα» and there is no «Έτοιμο» to
        // press — not even a dead one, and never a fourth button in the row.
        compose.onNodeWithText("Έτοιμο").assertDoesNotExist()
        write(word!!)
        compose.onNodeWithText("Έτοιμο").assertDoesNotExist()

        compose.onNodeWithText("Το είδα").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(TRACE_TEXT_HIDDEN_TAG).fetchSemanticsNodes().isNotEmpty() }
        // The word he traced while it was still on the screen went with it: there is nothing on the
        // paper to hand in, so the «Έτοιμο» that has taken the slot is dead.
        compose.onNodeWithText("Το είδα").assertDoesNotExist()
        compose.onNodeWithText("Έτοιμο").assertIsNotEnabled()

        // Now he writes it, with nothing to follow. This is the exercise.
        write(word)
        compose.onNodeWithText("Έτοιμο").assertIsEnabled()
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().size > 1 }
        val recalled = attempts().last()
        assertEquals(Outcome.CORRECT, recalled.outcome)
        assertTrue("a word written from memory has to say so: ${recalled.detail}", recalled.detail.contains("\"fromMemory\":true"))
        // He has earned the look: the word and the letters come back over what he wrote.
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

    /**
     * A finger that came down on the paper and went up again without moving is not a stroke.
     *
     * A knuckle resting on the glass, or a stray tap, would otherwise leave a dot that arms
     * «Έτοιμο» *and* «Καθάρισε» and joins the marking as one point of it — so an accidental touch
     * would spend a try. For a man writing left-handed with a tremor this is the likeliest thing to
     * happen by accident, and nothing should come of it.
     */
    @Test fun aBareTapOnThePaperIsNotAStroke() {
        openPractice(level = 1)
        val (width, height) = canvasSize()
        compose.onNodeWithTag(TRACE_CANVAS_TAG).performTouchInput {
            down(Offset(width / 2f, height / 2f))
            up()
        }
        compose.waitForIdle()

        // Nothing to wipe and nothing to hand in: the paper is as empty as before he touched it.
        compose.onNodeWithText("Καθάρισε").assertIsNotEnabled()
        compose.onNodeWithText("Έτοιμο").assertIsNotEnabled()
        assertTrue("a tap was recorded as an attempt", attempts().isEmpty())
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
     * A whole word written by hand is the word, and another word written just as well over it is not.
     *
     * The word is whichever of his own the word level offered; «Δημήτρης» — his name, and level 3
     * itself until phase 13 — is a word of a different length and a different shape, which is the
     * point: what refuses it is the shape and not the box it was drawn in.
     */
    @Test fun aDifferentWordOverHisOwnWordIsNotThatWord() {
        waitForVocabulary()
        val word = openPractice(level = 3)
        write(OTHER_WORD)
        finish()

        waitForNudge()
        assertTrue("«$OTHER_WORD» passed as «$word»", attempts().isEmpty())
    }

    /**
     * The field test of this round, on the glass: a word of his written properly except that one letter
     * of it is a «Κ». Three letters right out of four is three letters right — and the nudge has to
     * say *which* letter, because «Ξανά» over a word is not something a man with aphasia can act on.
     */
    @Test fun aKOverOneLetterOfAWordNamesThatLetter() {
        waitForVocabulary()
        val name = openPractice(level = 3)
        val (width, height) = canvasSize()
        val glyph = Glyphs.template(name, width, height)
        // Any letter but a «κ» itself: a «Κ» drawn over a «κ» is the letter, written.
        val eta = glyph.letters.indexOfLast { !it.text.equals(WRONG_LETTER, ignoreCase = true) }
        assertTrue("no letter of «$name» to spoil", eta > 0)
        val wrongAt = glyph.letters[eta].text
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
        // The letter is named. Which *other* letters the «Κ» leaned into is the scorer's business and
        // not this test's — what has to hold is that the nudge points at the letter he got wrong.
        compose.onNode(hasText("«$wrongAt»", substring = true)).assertIsDisplayed()
        assertTrue("a «Κ» over one «$wrongAt» passed as «$name»", attempts().isEmpty())
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

    // ------------------------------------------------------------ level 4: «Υπαγόρευση»

    /**
     * The dictation level, as he meets it: the word is **not** on the screen anywhere, the paper is
     * empty, and the one thing on offer with a clean sheet is hearing the word again.
     *
     * The word being absent is the whole exercise, so it is the assertion that matters most: a level
     * that showed it would be the word level with extra steps.
     */
    @Test fun theDictationShowsNoWordAndOffersTheSoundInstead() {
        waitForVocabulary()
        open(level = 4)

        // Nothing to copy: no word, no letter on the paper, and a row of slots instead.
        compose.onAllNodesWithTag(TRACE_TEXT_TAG).assertCountEquals(0)
        compose.onNodeWithTag(TRACE_SLOTS_TAG).assertIsDisplayed()
        assertEquals(
            "the first slot is empty, and nothing has been written yet",
            TraceViewModel.SLOT,
            text(TRACE_SLOTS_TAG),
        )
        // «Άκου» is the live one until there is ink: there is nothing to wipe and nothing to hand in.
        compose.onNodeWithTag(LISTEN_TAG).assertIsEnabled()
        compose.onNodeWithText("Καθάρισε").assertDoesNotExist()
        compose.onNodeWithText("Έτοιμο").assertIsNotEnabled()
        compose.onNodeWithText("Παράλειψη").assertIsDisplayed()

        // One stroke, and the slot swaps «Άκου» for the way back: three actions either way.
        val (width, height) = canvasSize()
        compose.onNodeWithTag(TRACE_CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.4f, height * 0.4f))
            moveTo(Offset(width * 0.6f, height * 0.6f))
            up()
        }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Καθάρισε")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Έτοιμο").assertIsEnabled()
        compose.onNodeWithText("Καθάρισε").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(LISTEN_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * A dictated word written the way he will write it: letter by letter, each one a hand-like trace of
     * the letter the word spells, with nothing on the paper to follow.
     *
     * Driven through the ViewModel rather than through the glass, for a reason that is the exercise
     * itself: the word is never shown, so a test tapping at the screen has no way of knowing which
     * letter the slot wants. What is real here is everything that decides the answer — the device's own
     * font, its density, the phase-11 scorer, the strictness a caregiver set — and the strokes are
     * [HandTrace]'s, a line down the middle of each stroke of the letter, which is what a hand does.
     *
     * The miss in the middle is the other half of the level: a letter that is not the letter is put on
     * the paper to be traced, the word goes on, and what it costs is the word's mark.
     */
    @Test fun aDictatedWordIsWrittenLetterByLetterAndAMissRevealsTheLetter() {
        waitForVocabulary()
        runBlocking { graph.settings.setTraceLevel(4) }
        lateinit var vm: TraceViewModel
        compose.runOnUiThread { vm = TraceViewModel(graph, sessionId = null) }
        try {
            compose.runOnUiThread { vm.setCanvasSize(PAPER, PAPER) }
            compose.waitUntil(TIMEOUT_MS) { vm.state.value.dictation?.expected != null && vm.state.value.target.points.isNotEmpty() }
            val word = vm.state.value.dictation!!.word
            assertTrue("nothing to dictate", word.isNotEmpty())
            assertFalse("the word he is meant to hear was on the paper", vm.state.value.templateVisible)

            // The first letter, missed: a scribble across the middle of the paper is not a letter.
            compose.runOnUiThread {
                vm.addStroke(listOf(Pt(PAPER * 0.45f, PAPER * 0.48f), Pt(PAPER * 0.55f, PAPER * 0.52f)))
                vm.check()
            }
            compose.waitUntil(TIMEOUT_MS) { vm.state.value.score?.passed == false }
            assertTrue("a letter that missed was not put on the paper to trace", vm.state.value.templateVisible)
            assertEquals("a miss took the slot", 0, vm.state.value.dictation!!.at)
            assertTrue("a miss wrote a row", attempts().isEmpty())

            // Traced over the revealed letter, it passes — and the word carries that help to the end.
            compose.runOnUiThread { vm.clear() }
            writeLetter(vm)
            compose.waitUntil(TIMEOUT_MS) { vm.state.value.dictation!!.at == 1 }
            assertTrue("the letter he was shown does not say so", vm.state.value.dictation!!.written.single().missed)
            assertFalse("the next slot opened with the letter still on it", vm.state.value.templateVisible)

            // The rest of the word, from hearing alone.
            while (vm.state.value.dictation?.done == false) {
                val at = vm.state.value.dictation!!.at
                writeLetter(vm)
                compose.waitUntil(TIMEOUT_MS) { vm.state.value.dictation!!.at > at }
            }

            compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
            val row = attempts().single()
            assertEquals("a word with one letter shown to him is assisted work", Outcome.ASSISTED, row.outcome)
            assertTrue("the row does not say how it was asked: ${row.detail}", row.detail.contains("\"variant\":\"dictation\""))
            assertTrue("nor which letters he wrote: ${row.detail}", row.detail.contains("\"missed\":true"))
            assertTrue("nor that the rest were his own: ${row.detail}", row.detail.contains("\"missed\":false"))
        } finally {
            compose.runOnUiThread { vm.leave {} }
        }
    }

    // ------------------------------------------------- level 5: «Γράψε την πρόταση»

    /**
     * The typed level: a picture, the keyboard, and a whole sentence of his own about the word.
     *
     * Two goes, because the refusal is the part that must never be a wall: the first is answered with
     * the whole form on the screen to copy, the second is accepted, and the row says assisted because
     * the sentence had been in front of him.
     */
    @Test fun aTypedSentenceIsJudgedAndARefusalComesBackWhole() {
        waitForVocabulary()
        withJudge(
            """{"accept":false,"expanded":"Ο μπαμπάς πίνει τον καφέ.","feedback":"Κοντά είσαι.","score":0.4}""",
            """{"accept":true,"expanded":null,"feedback":"Ωραία πρόταση.","score":1}""",
        )
        openTyped()
        compose.onNodeWithText(TraceViewModel.WRITE_IT, substring = true).assertIsDisplayed()
        // No paper at this level: a sentence is not a shape.
        compose.onAllNodesWithTag(TRACE_CANVAS_TAG).assertCountEquals(0)
        // And the three actions are the three: «Άκου», «Έτοιμο», «Παράλειψη».
        compose.onNodeWithTag(LISTEN_TAG).assertIsEnabled()
        compose.onNodeWithText("Έτοιμο").assertIsNotEnabled()
        compose.onNodeWithText("Παράλειψη").assertIsDisplayed()

        compose.onNodeWithTag(TRACE_TYPED_TAG).performTextInput("καφέ μπαμπάς")
        compose.onNodeWithText("Έτοιμο").performClick()

        // Refused: the whole sentence, on the screen, with the keyboard still there and no «λάθος».
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("Ο μπαμπάς πίνει τον καφέ.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("a refused sentence was written down as an attempt", attempts().isEmpty())
        compose.onNodeWithText(TraceViewModel.I_WROTE_IT).assertIsDisplayed()
        // The field survived the judge: it is read-only while the judge reads, never disabled, so it
        // still holds what he wrote and still takes more. (Disabling it would fold into the field's own
        // `focusable` and take the keyboard away with the focus — the bug «Προτάσεις» carries a comment
        // about.) The column has scrolled down to the sentence to copy, so the field may be off the
        // viewport; that it is still *there* and still writable is the property that matters.
        compose.onNodeWithTag(TRACE_TYPED_TAG).assertExists()

        compose.onNodeWithTag(TRACE_TYPED_TAG).performTextInput(" πίνει τον καφέ")
        compose.onNodeWithText("Έτοιμο").performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val row = attempts().single()
        assertEquals("a sentence written with the answer on the screen is assisted work", Outcome.ASSISTED, row.outcome)
        assertEquals("a typed row belongs to the level, not to the picture", "${ITEM_ID_PREFIX}5", row.itemId)
        assertTrue("the row does not say how it was asked: ${row.detail}", row.detail.contains("\"variant\":\"typed\""))
        assertTrue("nor who judged it: ${row.detail}", row.detail.contains("\"source\":\"JUDGE\""))
        // The judge's one warm line about a sentence he got right is on the screen, not thrown away.
        compose.onNodeWithText("Ωραία πρόταση.").assertIsDisplayed()
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }

    /**
     * The same level on a phone whose «Έλεγχος με Claude» is off, which is every phone by default.
     *
     * Nothing on the device can read a sentence, so the level is not offered at all: he gets the word
     * level's work — six words to write with a finger, which is always a writing exercise — and one
     * line on the first screen saying what is missing. A man who set the hardest dot and was quietly
     * handed easier work would have no way of knowing why.
     */
    @Test fun withoutTheJudgeTheSentenceLevelFallsBackToWriting() {
        waitForVocabulary()
        withNoJudge()
        val word = openPractice(level = 5)

        compose.onNodeWithText(TraceViewModel.NEEDS_JUDGE).assertIsDisplayed()
        compose.onAllNodesWithTag(TRACE_TYPED_TAG).assertCountEquals(0)
        assertTrue("a word to write, not an empty screen: «$word»", word.isNotBlank())
        write(word)
        finish()

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val row = attempts().single()
        assertEquals(Outcome.CORRECT, row.outcome)
        // The row has to be readable as what it was: level 5, and not typed.
        assertTrue("a fallback row does not say so: ${row.detail}", row.detail.contains("\"noJudge\":true"))
        assertFalse("a fallback row claims to be typed: ${row.detail}", row.detail.contains("\"variant\""))
    }

    /** Sets the level and opens free practice, with the paper laid out and something on it to write. */
    private fun open(level: Int) {
        runBlocking { graph.settings.setTraceLevel(level) }
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(TITLE))
        compose.onNodeWithText(TITLE).performClick()
        // The level read has to land before there is a letter, and its canvas before there is paper.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(TRACE_CANVAS_TAG).fetchSemanticsNodes().any { it.size.width > 0 && it.size.height > 0 }
        }
    }

    /** The same, for the one level that has a keyboard instead of paper. */
    private fun openTyped() {
        runBlocking { graph.settings.setTraceLevel(5) }
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText(TITLE))
        compose.onNodeWithText(TITLE).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(TRACE_TYPED_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Sets the level, opens free practice and returns what it is asking him to write. */
    private fun openPractice(level: Int): String {
        open(level)
        val text = text(TRACE_TEXT_TAG)
        assertTrue("nothing to write", !text.isNullOrBlank())
        return text!!
    }

    /** Whatever a tagged line of the screen says, joined as he reads it. */
    private fun text(tag: String): String? = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /**
     * The letter the dictation's slot is waiting for, written the way a hand writes it — and handed to
     * the ViewModel as strokes, because at that level the screen cannot tell a test which letter it is.
     */
    private fun writeLetter(vm: TraceViewModel) {
        val letter = vm.state.value.dictation?.expected
        assertTrue("no letter at this slot", !letter.isNullOrEmpty())
        val strokes = HandTrace.centreLine(Glyphs.template(letter!!, PAPER, PAPER))
        assertTrue("«$letter» has no strokes to write", strokes.isNotEmpty())
        compose.runOnUiThread {
            strokes.forEach { vm.addStroke(it) }
            vm.check()
        }
    }

    /** The seed vocabulary arrives on first launch; the word levels have nothing to offer before it. */
    private fun waitForVocabulary() = compose.waitUntil(TIMEOUT_MS) {
        runBlocking { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }.isNotEmpty()
    }

    /**
     * A judge that answers with canned verdicts, with a key behind it so that [TurnJudge.available]
     * says yes and the sitting is planned with typed boards in it. The secret store is untouched: the
     * key is the judge's own, which is what the seam is for.
     */
    private fun withJudge(vararg json: String) {
        realJudge = graph.judge
        replies.clear()
        replies += json
        graph.judge = TurnJudge(
            secrets = { KEY },
            enabled = { true },
            client = JudgeClient { _, _, _ -> replies.removeFirstOrNull() },
        )
    }

    /** The default phone: «Έλεγχος με Claude» off, so nothing can read a sentence he types. */
    private fun withNoJudge() {
        realJudge = graph.judge
        graph.judge = TurnJudge(secrets = { null }, enabled = { false }, client = JudgeClient { _, _, _ -> null })
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

        /** A word of his own, and not one the word level can have offered: the shape is what refuses it. */
        const val OTHER_WORD = "Δημητρα"

        /**
         * The paper the dictation cases write on, in pixels: a square, as the screen gives a single
         * letter, and about the size a phone's own canvas comes out. The marking is in fingertips
         * against the device's real density, so the number only has to be a believable piece of paper.
         */
        const val PAPER = 900f

        /** Never a real key: the judge's [TurnJudge.available] only asks whether there is one. */
        const val KEY = "sk-ant-test"
        const val TIMEOUT_MS = 20_000L
    }
}
