package gr.dimitris.app.modules.sentences

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
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
 * The two things about the sentence builder that only a device can answer: that the right order is
 * an answer of his own, and that a wrong one is a sentence to copy rather than a dead end.
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
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Όχι έτσι.")).fetchSemanticsNodes().isNotEmpty() }
        // Said out loud and left on the screen, in the order he has to copy.
        compose.onNodeWithText("Σωστά: ${right.joinToString(" ")}").assertIsDisplayed()
        // A wrong order is not an answer: nothing is written until the sentence is finished.
        assertTrue("a miss was recorded as an attempt", attempts().isEmpty())

        tapInOrder(right)
        compose.waitUntil(TIMEOUT_MS) { attempts().any { it.outcome == Outcome.ASSISTED } }
        assertEquals("one sentence, one row", 1, attempts().size)
    }

    /** Opens free practice and returns the sentence's words in the order they have to be tapped. */
    private fun openPractice(): List<String> {
        // The vocabulary arrives on first launch, and a board built before it does has nothing on it.
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }.map { it.text }.containsAll(SEED_WORDS)
        }
        compose.onNodeWithTag(MODULE_GRID_TAG).performScrollToNode(hasText("Προτάσεις"))
        compose.onNodeWithText("Προτάσεις").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SENTENCE_TILE_TAG).fetchSemanticsNodes().size >= 2 }
        val labels = compose.onAllNodesWithTag(SENTENCE_TILE_TAG).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
        assertEquals("level 1 is a verb and its object: $labels", 2, labels.size)
        val verb = labels.first { it in VERBS }
        return listOf(verb, labels.first { it != verb })
    }

    private fun tapInOrder(labels: List<String>) = labels.forEach { label ->
        compose.onNode(hasTestTag(SENTENCE_TILE_TAG) and hasText(label)).performClick()
    }

    private fun attempts(): List<Attempt> =
        runBlocking { graph.db.attempts().since(since) }.filter { it.module == ModuleId.SENTENCES }

    private companion object {
        /** The verbs a level-1 sentence starts with. Whichever card is one of these goes first. */
        val VERBS = setOf("θέλω", "τρώω", "πίνω")

        /** Enough of the seed to know the import has landed: one verb and one thing to want. */
        val SEED_WORDS = listOf("θέλω", "νερό")

        const val TIMEOUT_MS = 15_000L
    }
}
