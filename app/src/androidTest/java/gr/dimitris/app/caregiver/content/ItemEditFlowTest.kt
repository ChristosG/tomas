package gr.dimitris.app.caregiver.content

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.today.CAREGIVER_HOLD_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Chris' report, on the device: he added a word in caregiver mode and had no way to see it in use —
 * "I would have to use the app for hours until it randomly appears".
 *
 * Only a device can answer this one, because it is a question about navigation: that «Δοκίμασέ το»
 * really opens the word coach on *that* word and on nothing else, and that coming back out lands on
 * the editor she left rather than on Today. The rest — the ordering that puts her new word first in
 * the next sitting — is `SessionBuilderTest`'s.
 */
class ItemEditFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private lateinit var word: Item
    private var lockBefore = false

    @Before fun seedOneWord() = runBlocking<Unit> {
        lockBefore = graph.settings.caregiverLock.first()
        graph.settings.setCaregiverLock(false)
        word = graph.items.save(Item(text = WORD, category = Category.FOOD))
    }

    /** The words and the setting were ours, not his — the one the draft case wrote included. */
    @After fun removeSeed() = runBlocking<Unit> {
        graph.items.delete(word.id)
        drafts().forEach { graph.items.delete(it.id) }
        graph.settings.setCaregiverLock(lockBefore)
    }

    /** Whatever the new-draft case left behind, found by its text: the id is never handed back. */
    private suspend fun drafts(): List<Item> =
        graph.db.items().activeOfKinds(listOf(ItemKind.WORD, ItemKind.PHRASE)).filter { it.text == DRAFT_WORD }

    /**
     * The whole answer in one run: the button is live on a word that is saved, it opens the word
     * coach on a sitting of exactly one item — «Λέξεις 1/1», which no random plan would give —
     * that word and no other is the one behind the cues, and back is the editor she came from.
     */
    @Test fun theSavedWordRunsAtOnceAndBackReturnsToTheEditor() {
        openTheSeededWord()

        compose.onNodeWithText(TRY_IT).assertIsEnabled().performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(ONE_WORD_SITTING) }
        // The word is what he is trying to say, so it is not on the screen until the ladder has
        // been walked to the top. Four «Βοήθεια» is the longest that ladder ever is.
        repeat(4) { if (enabled(HELP)) compose.onNodeWithText(HELP).performClick() }
        compose.waitUntil(TIMEOUT_MS) { shown(WORD) }
        compose.onNodeWithText(WORD).assertIsDisplayed()

        compose.onNodeWithContentDescription(BACK).performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(EDITOR_TITLE) }
        compose.onNodeWithText(TRY_IT).assertIsEnabled()
    }

    /**
     * The path a caregiver actually takes: she types a word she has never saved and wants to see it
     * work. The save happens under the button and the editor stays open — the header is still «Νέα
     * λέξη», she never left the form — the word runs, and back lands on that same form with what
     * she typed still in it. A second «Αποθήκευση» from here updates that row rather than writing a
     * second copy of the word.
     */
    @Test fun aWordSheHasOnlyJustTypedRunsWithoutLeavingTheForm() {
        openTheWordList()
        compose.onNodeWithText(NEW_WORD).performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(TRY_IT) }
        // The word field: the first of the three the form has.
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(DRAFT_WORD)

        compose.onNodeWithText(TRY_IT).assertIsEnabled().performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(ONE_WORD_SITTING) }
        repeat(4) { if (enabled(HELP)) compose.onNodeWithText(HELP).performClick() }
        compose.waitUntil(TIMEOUT_MS) { shown(DRAFT_WORD) }

        compose.onNodeWithContentDescription(BACK).performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(TRY_IT) }
        compose.onNodeWithText(DRAFT_WORD).assertIsDisplayed()
        compose.onNodeWithText(NEW_WORD).assertIsDisplayed()   // the header did not change under her
        // Exactly one row, however many times the word was saved on the way.
        assertEquals(1, runBlocking { drafts() }.size)
    }

    /**
     * **A sung sentence says what «Τραγούδι» means and cannot be run through the word coach.**
     *
     * «Δοκίμασέ το» opens the word coach for any id, and a `SINGING` phrase is the one item the word
     * coach is written to keep out — a twenty-syllable read-aloud is not a word to name under a
     * picture. The caregiver opens exactly these rows, because the sung take is recorded on this form.
     * So the button goes off, and the line under the chips says why: a dead button with no explanation
     * would be a worse bug than the one it fixes.
     */
    @Test fun aSungSentenceSaysWhatTheCategoryCostsAndCannotBeRun() {
        openTheSeededWord()
        compose.onNodeWithText(TRY_IT).assertIsEnabled()
        assertFalse("the hint is on a form nobody has filed under «Τραγούδι»", shown(SINGING_HINT))

        compose.onNodeWithText(PHRASE).performScrollTo().performClick()
        compose.onNodeWithText(SINGING).performScrollTo().performClick()

        compose.waitUntil(TIMEOUT_MS) { shown(SINGING_HINT) }
        compose.onNodeWithText(SINGING_HINT).assertIsDisplayed()
        compose.onNodeWithText(TRY_IT).assertIsNotEnabled()

        // A word filed under «Τραγούδι» is still a word: it is the pair that excludes it.
        compose.onNodeWithText(WORD_KIND).performScrollTo().performClick()
        compose.onNodeWithText(TRY_IT).assertIsEnabled()
    }

    /** Hold the name, say yes, open the words — the way a caregiver gets here. */
    private fun openTheWordList() {
        compose.onNodeWithTag("title").performTouchInput {
            down(center)
            advanceEventTime(CAREGIVER_HOLD_MS + 200)
            up()
        }
        compose.onNodeWithText("Ναι").performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(WORDS_ENTRY) }
        compose.onNodeWithText(WORDS_ENTRY).performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(NEW_WORD) }
    }

    /** Search for it rather than scrolling: the list holds every seeded word too. */
    private fun openTheSeededWord() {
        openTheWordList()
        compose.onNode(hasSetTextAction()).performTextInput(SEARCH)
        compose.waitUntil(TIMEOUT_MS) { shown(WORD) }
        compose.onNodeWithText(WORD).performClick()
        compose.waitUntil(TIMEOUT_MS) { shown(EDITOR_TITLE) }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun enabled(text: String) =
        compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        /** Ours, and nothing the seed vocabulary contains, so the assertions cannot match his words. */
        const val WORD = "καφές δοκιμής επεξεργασίας"

        /** A fragment of it that the search field itself will not then be mistaken for. */
        const val SEARCH = "δοκιμής επεξ"

        /** Typed into a brand-new form by the draft case, and taken back out afterwards. */
        const val DRAFT_WORD = "παγωτό δοκιμής προχείρου"

        const val TRY_IT = "Δοκίμασέ το"
        const val NEW_WORD = "Νέα λέξη"

        /** The two chips the sung-sentence case taps, and the category it files it under. */
        const val WORD_KIND = "Λέξη"
        const val PHRASE = "Φράση"
        val SINGING = Category.SINGING.greek
        const val WORDS_ENTRY = "Λέξεις και εικόνες"
        const val EDITOR_TITLE = "Επεξεργασία"
        const val HELP = "Βοήθεια"
        const val BACK = "Πίσω"

        /** One item, not a plan of eight: proof the route ran the word she named. */
        const val ONE_WORD_SITTING = "Λέξεις 1/1"

        const val TIMEOUT_MS = 20_000L
    }
}
