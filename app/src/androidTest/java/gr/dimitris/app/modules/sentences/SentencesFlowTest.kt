package gr.dimitris.app.modules.sentences

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
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.today.MODULE_GRID_TAG
import gr.dimitris.app.ui.components.LISTEN_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    /** His level is his; a test that borrows it puts it back. */
    @After fun restoreLevel() = runBlocking<Unit> { graph.settings.setSentencesLevel(levelBefore) }

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
        assertTrue("the sentence uses a card that is not on the board: $shown", board.containsAll(shown))

        tapInOrder(shown)
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.itemId == LEVEL_4 && it.outcome == Outcome.ASSISTED } }
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
     */
    private fun correction(board: List<String>): List<String>? {
        var rest = correctionText() ?: return null
        val cards = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            val card = board.filter { rest.startsWith(it) }.maxByOrNull { it.length } ?: break
            cards += card
            rest = rest.removePrefix(card).trimStart()
        }
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
        const val TIMEOUT_MS = 15_000L
    }
}
