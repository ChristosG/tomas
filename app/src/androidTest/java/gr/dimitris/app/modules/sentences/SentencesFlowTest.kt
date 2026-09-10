package gr.dimitris.app.modules.sentences

import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.judge.JudgeClient
import gr.dimitris.app.core.judge.TurnJudge
import gr.dimitris.app.today.MODULE_GRID_TAG
import gr.dimitris.app.ui.components.LISTEN_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The things about the sentence builder that only a device can answer: that the right order is an
 * answer of his own, that a wrong one is a sentence to copy rather than a dead end, and that the
 * level-4 board really does carry a card that no arrangement of it can use.
 */
class SentencesFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
    private var levelBefore = 1
    private var since = 0L

    /** Level 1 is two words, which is the only length a test can name the right order of. */
    @Before fun startAtLevelOne() = runBlocking<Unit> {
        levelBefore = graph.settings.sentencesLevel.first()
        graph.settings.setSentencesLevel(1)
        since = System.currentTimeMillis()
    }

    /** His level is his; a test that borrows it puts it back, and so is the judge. */
    @After fun restoreLevel() = runBlocking<Unit> {
        graph.settings.setSentencesLevel(levelBefore)
        realJudge?.let { graph.judge = it }
    }

    /** The judge the typed cases run against: the emulator can reach the Anthropic API no more than a mic. */
    private var realJudge: TurnJudge? = null

    /** What the fake client answers, reply by reply. */
    private val replies = ArrayDeque<String>()

    /**
     * A judge that answers with canned verdicts, with a key behind it so [TurnJudge.available] says
     * yes and the sitting is planned with typed boards in it. The secret store is untouched: the key
     * is the judge's own, which is what the seam is for.
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

    @Test fun theRightOrderIsAnAnswerOfHisOwn() {
        val right = openPractice()
        tapInOrder(right)
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.outcome == Outcome.CORRECT } }
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }

    @Test fun aWrongOrderShowsTheSentenceToCopyAndCountsAsHelped() {
        val right = openPractice()
        tapInOrder(right.reversed())
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(SentencesViewModel.WRONG_ORDER)).fetchSemanticsNodes().isNotEmpty() }
        // Said out loud and left on the screen, in the order he has to copy.
        compose.onNodeWithText("Σωστά: ${right.joinToString(" ")}").assertIsDisplayed()
        // A wrong order is not an answer: nothing is written until the sentence is finished.
        assertTrue("a miss was recorded as an attempt", attempts().isEmpty())

        tapInOrder(right)
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.outcome == Outcome.ASSISTED } }
        assertEquals("one sentence, one row", 1, attempts().size)
    }

    /**
     * There is no cue ladder here, so «Άκου» gives him the whole answer — the sentence in the order
     * the cards go down — and it is still on offer from the first second, because spec §12 does not
     * make him earn the model. What it costs is the row: the sentence is written as assisted work at
     * the listening level rather than as one he found himself, so the level progression stays honest.
     */
    @Test fun theModelIsOnOfferFromTheFirstSecondAndTheRowSaysHeUsedIt() {
        val right = openPractice()

        compose.onNodeWithTag(LISTEN_TAG).assertIsEnabled()
        compose.onNodeWithTag(LISTEN_TAG).performClick()
        tapInOrder(right)

        compose.waitUntil(TIMEOUT_MS) { attempts().isNotEmpty() }
        val written = attempts().single()
        assertEquals("a sentence he had said to him is work done with help", Outcome.ASSISTED, written.outcome)
        assertTrue("hearing the model is level 3's worth of help: ${written.cueLevel}", (written.cueLevel ?: 0) >= 3)
        assertTrue("the row has to carry the listen: ${written.detail}", written.detail.contains("\"listened\":1"))
    }

    /**
     * The next sentence opens with «Άκου» live, even when the last one is still being read out.
     *
     * He taps the green «Επόμενο» the instant the success mark appears, and the sentence he just
     * built is still being spoken. Before the fix the new board inherited `modelPlaying = true` and
     * greyed its «Άκου» for a second or two — Chris's field bug in miniature, on the one screen
     * where «Άκου» *is* the answer. It self-healed when the old utterance ended, which is exactly
     * why a click-and-wait test could never see it: this drives the ViewModel so the assertion
     * lands in the same call stack as the advance.
     */
    @Test fun theNextSentenceOpensWithTheModelOnOffer() {
        lateinit var vm: SentencesViewModel
        compose.runOnUiThread { vm = SentencesViewModel(graph, sessionId = null) }
        compose.waitUntil(TIMEOUT_MS) { (vm.state.value.sentence?.tiles?.size ?: 0) >= 2 }

        // Built in the right order: the module says the whole sentence and the mark goes up.
        compose.runOnUiThread { vm.state.value.sentence!!.tiles.forEach { vm.tap(it) } }
        assertEquals(true, vm.state.value.correct)
        assertTrue("the sentence he built is being read back", vm.state.value.modelPlaying)

        compose.runOnUiThread { vm.next() }

        assertTrue("the new sentence is a different one", vm.state.value.index == 1)
        assertEquals("and its «Άκου» is live from the first frame", false, vm.state.value.modelPlaying)
        compose.runOnUiThread { vm.leave {} }
    }

    /**
     * Level 4 puts one card on the board that belongs to no arrangement of it. The board is tapped
     * along in the order it happens to be in until the app answers: either that order was the
     * sentence, or the sentence is shown — and it is always shorter than the board, because one of
     * the cards is not in it.
     */
    @Test fun theOddCardOutIsNeverPartOfTheSentence() {
        runBlocking { graph.settings.setSentencesLevel(4) }
        val board = openBoard()
        assertTrue("level 4 owes him an extra card: $board", board.size >= 3)

        for (label in board) {
            tapInOrder(listOf(label))
            if (answered()) break
        }
        val shown = correction(board)
        if (shown == null) {
            // The board happened to be in the sentence's own order: that is an answer of his own.
            compose.waitUntil(TIMEOUT_MS) { attempts().any { it.itemId == LEVEL_4 && it.outcome == Outcome.CORRECT } }
            return
        }
        assertTrue("the whole board is the sentence: $shown against $board", shown.size < board.size)
        // Named, not counted: which card the sentence left out is the thing level 4 is about. That
        // every word of it *is* on the board is asserted inside `correction`, where the first word
        // that is not can say so by name.
        assertTrue("every card on the board was used: $shown against $board", board.any { it !in shown })

        tapInOrder(shown)
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.itemId == LEVEL_4 && it.outcome == Outcome.ASSISTED } }
    }

    /**
     * Level 5 is the articles, and every third board asks for one of them on its own: the sentence
     * with «τον» taken out of it and three small words to choose between.
     *
     * The right one is found by trying them, exactly as he would: a wrong tap is «Σχεδόν.» with the
     * whole sentence left on the screen and the three cards still live, and there is no way to fail
     * — which is the assertion that matters more than which card it was.
     */
    @Test fun aGapBoardAsksForOneSmallWordAndTakesOnlyTheOneThatFits() {
        runBlocking { graph.settings.setSentencesLevel(5) }
        openBoard()
        // Boards one and two are cards; the third is the gap. Passing on a board is a legal way past it.
        repeat(2) { skipBoard() }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SENTENCE_GAP_TAG).fetchSemanticsNodes().size == 3 }

        val options = compose.onAllNodesWithTag(SENTENCE_GAP_TAG).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
        assertEquals("three small words and no more: $options", 3, options.size)
        for (option in options) {
            compose.onNode(hasTestTag(SENTENCE_GAP_TAG) and hasText(option)).performClick()
            if (compose.onAllNodes(hasText("Επόμενο")).fetchSemanticsNodes().isNotEmpty()) break
            // Not the one: the sentence is on the screen to read, and the cards are still his.
            compose.onNode(hasText(SentencesViewModel.WRONG_ORDER)).assertIsDisplayed()
        }
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.detail.contains(GAP_ROW) } }
        // The two boards he passed on are level-5 rows too, so the gap board is found by how it was
        // asked rather than by which level it was at.
        val row = attempts().single { it.detail.contains(GAP_ROW) }
        assertEquals("a gap board is a level-5 board", LEVEL_5, row.itemId)
        assertTrue("the small word he chose is not in the row: ${row.detail}", options.any { row.detail.contains("\"$it\"") })
    }

    /**
     * Level 7 is the one place in the app that asks him to write. A picture, «Γράψε την πρόταση», a
     * keyboard — and the judge, because nothing on the phone can read a sentence.
     *
     * Two goes: the first is refused and answered with the whole form to copy, which is the thing
     * this module gives instead of a wall; the second is accepted, and the row says the work was
     * assisted because the sentence had been on the screen.
     */
    @Test fun aTypedBoardIsJudgedAndAWrongOneComesBackWhole() {
        withJudge(
            """{"accept":false,"expanded":"Θέλω να φάω ψωμί γιατί πεινάω.","feedback":"Κοντά είσαι.","score":0.4}""",
            """{"accept":true,"expanded":null,"feedback":null,"score":1}""",
        )
        runBlocking { graph.settings.setSentencesLevel(7) }
        openBoard()
        repeat(2) { skipBoard() }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SENTENCE_TYPED_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(SentencesViewModel.WRITE_IT, substring = true).assertIsDisplayed()
        // The field takes the focus on arrival, so the keyboard is already up and his first act is
        // to write rather than to aim at a text field with his left hand.
        waitForIme(up = true)

        compose.onNodeWithTag(SENTENCE_TYPED_TAG).performTextInput("θέλω ψωμί")
        compose.onNodeWithText("Έτοιμο").performClick()

        // Refused: the whole sentence, on the screen, with the keyboard still there and no «λάθος».
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("Θέλω να φάω ψωμί γιατί πεινάω.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("a refused sentence was written down as an attempt", attempts().none { it.detail.contains(TYPED_ROW) })
        compose.onNodeWithText(SentencesViewModel.I_WROTE_IT).assertIsDisplayed()

        // The keyboard survived the judge. Disabling the field while it was thinking used to take the
        // focus away and the keyboard with it, and he came back from the wait to a screen he had to
        // aim at again before he could change a word of what he wrote.
        waitForIme(up = true)

        // And the bottom block is still the three it has always been: «Άκου», «Έτοιμο», «Παράλειψη».
        // «Το έγραψα» belongs to the sentence it is about, in the body, above all three of them.
        assertThreeActionsInTheBottom()

        compose.onNodeWithTag(SENTENCE_TYPED_TAG).performTextInput(" γιατί πεινάω")
        compose.onNodeWithText("Έτοιμο").performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.detail.contains(TYPED_ROW) } }
        val row = attempts().single { it.detail.contains(TYPED_ROW) }
        assertEquals("a typed board is a level-7 board", LEVEL_7, row.itemId)
        assertEquals("a sentence copied off the screen is assisted work", Outcome.ASSISTED, row.outcome)
        assertTrue("the judge is not in the row: ${row.detail}", row.detail.contains("\"judge\""))
        assertTrue("nor is who answered: ${row.detail}", row.detail.contains("\"source\":\"JUDGE\""))
    }

    /**
     * A level-8 typed board asks for a **question**, out loud on the screen and in what the judge is
     * told the board wanted.
     *
     * Without it the board cannot be answered on the first try: a picture of a chemist's under
     * «Γράψε την πρόταση» is answered just as well by «θέλω να πάω στο φαρμακείο» — faultless Greek —
     * and the judge, told to accept only when the meaning agrees with the target, would call that
     * «Σχεδόν.» The only other way to find out what was wanted is «Άκου», which reads the answer out
     * and spends the mark, so every one of these would have come out ASSISTED.
     */
    @Test fun aLevel8TypedBoardAsksForAQuestionAndSaysSo() {
        withJudge("""{"accept":true,"expanded":null,"feedback":"Ωραία ερώτηση.","score":1}""")
        runBlocking { graph.settings.setSentencesLevel(8) }
        openBoard()
        repeat(2) { skipBoard() }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SENTENCE_TYPED_TAG).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(SentencesViewModel.WRITE_A_QUESTION, substring = true).assertIsDisplayed()
        assertTrue(
            "a question board still says «Γράψε την πρόταση»",
            compose.onAllNodes(hasText(SentencesViewModel.WRITE_IT, substring = true)).fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithTag(SENTENCE_TYPED_TAG).performTextInput("πού είναι το φαρμακείο;")
        compose.onNodeWithText("Έτοιμο").performClick()

        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.detail.contains(TYPED_ROW) } }
        val row = attempts().single { it.detail.contains(TYPED_ROW) }
        assertEquals("a level-8 typed board", LEVEL_8, row.itemId)
        assertEquals("a question he wrote himself is his own work", Outcome.CORRECT, row.outcome)
        // The judge's one warm line about a sentence he got right is on the screen, not thrown away.
        compose.onNodeWithText("Ωραία ερώτηση.").assertIsDisplayed()
    }

    /**
     * A device whose words have all been deleted has no sentence to offer. It has to say so and let
     * him straight out — the one thing it must never do is hold him on a screen with nothing on it.
     */
    @Test fun withNoWordsItSaysSoAndLetsHimOut() {
        val words = runBlocking { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }
        assertTrue("nothing to take away", words.isNotEmpty())
        runBlocking { words.forEach { graph.db.items().softDelete(it.id, now()) } }
        try {
            compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText("Προτάσεις"))
            compose.onNodeWithText("Προτάσεις").performClick()
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(hasText("Χρειάζονται περισσότερες λέξεις.")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Εντάξει").performClick()
            // Back on Today, with nothing written and nothing counted.
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Ξεκίνα")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("an empty board wrote an attempt", attempts().isEmpty())
        } finally {
            // His vocabulary is his: every row goes back exactly as it was.
            runBlocking { words.forEach { graph.db.items().upsert(it) } }
        }
    }

    /** Opens free practice and returns the sentence's words in the order they have to be tapped. */
    private fun openPractice(): List<String> {
        val labels = openBoard()
        assertEquals("level 1 is a verb and its object: $labels", 2, labels.size)
        val verb = labels.first { it in VERBS }
        return listOf(verb, labels.first { it != verb })
    }

    /** Opens free practice and returns the words on the board, in the order they are laid out. */
    private fun openBoard(): List<String> {
        // The vocabulary arrives on first launch, and a board built before it does has nothing on it.
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }.map { it.text }.containsAll(SEED_WORDS)
        }
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText("Προτάσεις"))
        compose.onNodeWithText("Προτάσεις").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SENTENCE_TILE_TAG).fetchSemanticsNodes().size >= 2 }
        return compose.onAllNodesWithTag(SENTENCE_TILE_TAG).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
    }

    private fun tapInOrder(labels: List<String>) = labels.forEach { label ->
        compose.onNode(hasTestTag(SENTENCE_TILE_TAG) and hasText(label)).performClick()
    }

    /**
     * The bottom block is the three actions it has always been, and nothing else has moved into it.
     *
     * Read by position rather than by slot, which is the only thing a semantics tree can see: «Άκου»,
     * «Έτοιμο» and «Παράλειψη» in that order down the screen, and «Το έγραψα» — which is on screen at
     * this point — above all three of them, in the body, under the sentence it is about.
     */
    private fun assertThreeActionsInTheBottom() {
        val listen = compose.onNodeWithTag(LISTEN_TAG).fetchSemanticsNode().boundsInRoot.top
        val ready = compose.onNodeWithText("Έτοιμο").fetchSemanticsNode().boundsInRoot.top
        val skip = compose.onNodeWithText("Παράλειψη").fetchSemanticsNode().boundsInRoot.top
        val wrote = compose.onNodeWithText(SentencesViewModel.I_WROTE_IT).fetchSemanticsNode().boundsInRoot.top
        assertTrue("«Άκου» is not the first of the bottom three: $listen vs $ready", listen < ready)
        assertTrue("«Έτοιμο» is not above «Παράλειψη»: $ready vs $skip", ready < skip)
        assertTrue("«Το έγραψα» is a fourth button in the bottom block: $wrote vs $listen", wrote < listen)
    }

    /**
     * Waits for the soft keyboard to be [up], read off the window's own IME insets.
     *
     * The one thing `performTextInput` cannot tell us: it puts text into the semantics node directly,
     * so a board whose keyboard has gone looks identical to one whose keyboard is there.
     *
     * Not every device can answer it. An AVD (or a phone) with a hardware keyboard suppresses the
     * soft one altogether, and then the IME insets never report it visible however right the screen
     * is — so on such a device the honest outcome is "not asked here", not "his keyboard is gone".
     * The check is made only *after* the wait has run out, so a device that does have a soft
     * keyboard can never be skipped by a misread of its configuration: it is the failure that is
     * re-examined, never the pass.
     */
    private fun waitForIme(up: Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (imeVisible() == up) return
            Thread.sleep(IME_POLL_MS)
        }
        Assume.assumeTrue("this device shows no soft keyboard, so its IME insets say nothing", hasSoftKeyboard())
        assertEquals("the keyboard", up, imeVisible())
    }

    /** A soft keyboard exists here at all: some IME is enabled, and no hardware keyboard is hiding it. */
    private fun hasSoftKeyboard(): Boolean {
        val activity = compose.activity
        val anyIme = runCatching {
            activity.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.isNotEmpty() == true
        }.getOrDefault(false)
        val config = activity.resources.configuration
        val hardware = config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        return anyIme && !hardware
    }

    private fun imeVisible(): Boolean = compose.runOnUiThread {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    /** Passes on the board he is on and waits for the next one to be drawn. */
    private fun skipBoard() {
        val before = attempts().size
        compose.onNodeWithText("Παράλειψη").performClick()
        compose.waitUntil(TIMEOUT_MS) { attempts().size > before }
        compose.waitForIdle()
    }

    /** True once the sentence is finished, right or wrong. */
    private fun answered(): Boolean = compose.onAllNodes(hasText("Επόμενο")).fetchSemanticsNodes().isNotEmpty() ||
        correctionText() != null

    /** The sentence the screen is showing him to copy, as written, or null while there is none. */
    private fun correctionText(): String? = compose.onAllNodes(hasText(CORRECTION, substring = true)).fetchSemanticsNodes()
        .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
        ?.substringAfter(CORRECTION)?.trim()

    /**
     * The same sentence read back as the board's own **cards**.
     *
     * Not `split(" ")`: a card can be two words — «σούπερ μάρκετ» is one tile — so splitting on
     * spaces counted four cards in a three-card sentence and the size assertion below failed
     * whenever the templates happened to pick one. Longest label first, so one card is never
     * mistaken for the beginning of another.
     *
     * The reconstruction is checked rather than trusted. Rebuilding a sentence *out of* the board
     * would otherwise make «every word is on the board» a tautology and turn the real bug this test
     * hunts — a correction naming a word the board never offered — into a short list that slips past
     * the size assertion and dies minutes later on a timeout with nothing to read. So the first word
     * that is not a card fails here, by name, and a reconstruction that does not add back up to the
     * sentence as written fails too.
     */
    private fun correction(board: List<String>): List<String>? {
        val text = correctionText() ?: return null
        val cards = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val card = board.filter { rest.startsWith(it) }.maxByOrNull { it.length }
            assertTrue(
                "the sentence uses «${rest.substringBefore(' ')}», which is on no card of $board",
                card != null,
            )
            cards += card!!
            rest = rest.removePrefix(card).trimStart()
        }
        assertEquals("the sentence did not add back up out of the board's cards", text, cards.joinToString(" "))
        return cards
    }

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(since) }.filter { it.module == ModuleId.SENTENCES }

    private companion object {
        /** The verbs a level-1 sentence starts with. Whichever card is one of these goes first. */
        val VERBS = setOf("θέλω", "τρώω", "πίνω")

        /** Enough of the seed to know the import has landed: one verb and one thing to want. */
        val SEED_WORDS = listOf("θέλω", "νερό")

        const val CORRECTION = "Σωστά: "
        const val LEVEL_4 = "sentences:level:4"
        const val LEVEL_5 = "sentences:level:5"
        const val LEVEL_7 = "sentences:level:7"
        const val LEVEL_8 = "sentences:level:8"

        /** How often the IME insets are read while waiting for the keyboard to settle. */
        const val IME_POLL_MS = 200L

        /** How a row says which of the three ways its board asked. See `sentencesDetail`. */
        const val GAP_ROW = "\"variant\":\"GAP\""
        const val TYPED_ROW = "\"variant\":\"TYPED\""

        /** Not a key: it is only ever handed to the fake client, which throws it away. */
        const val KEY = "sk-ant-test"

        const val TIMEOUT_MS = 15_000L
    }
}
