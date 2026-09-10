package gr.dimitris.app.modules.talkboard

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.judge.JudgeClient
import gr.dimitris.app.core.judge.TurnJudge
import gr.dimitris.app.core.speech.FakeSpeechToText
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.LocalFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class TalkBoardScreenTest {
    @get:Rule(order = 0) val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    /** The recogniser and the judge the expansion runs against: the emulator can reach neither. */
    private val fake = FakeSpeechToText()
    private lateinit var realStt: SpeechToText
    private lateinit var realJudge: TurnJudge
    private var realKey: String? = null
    private var realJudging = false
    private var realSttEnabled = false
    private val seeded = mutableListOf<Item>()

    /** So a view model this test builds is really cleared afterwards, `onCleared` and all. */
    private val store = ViewModelStore()

    @Before fun rememberWhatWasHere() = runBlocking<Unit> {
        realStt = graph.stt
        realJudge = graph.judge
        realKey = graph.secrets.getClaudeKey()
        realJudging = graph.settings.claudeJudging.first()
        realSttEnabled = graph.settings.sttEnabled.first()
    }

    /**
     * The words, the recogniser, the judge, the key and the two settings were ours. Every one of them
     * goes back to what it was — put back rather than switched off, because this phone is also
     * somebody's phone and the next case must find it as it was.
     */
    @After fun putItAllBack() = runBlocking<Unit> {
        compose.runOnUiThread { store.clear() }
        graph.stt = realStt
        graph.judge = realJudge
        graph.secrets.setClaudeKey(realKey)
        graph.settings.setClaudeJudging(realJudging)
        graph.settings.setSttEnabled(realSttEnabled)
        seeded.forEach { graph.items.delete(it.id) }
    }

    @Test fun opensFromTodayAndShowsFavouritesTab() {
        compose.onNodeWithText("Μίλα").performClick()
        compose.onNodeWithText("Αγαπημένα").assertIsDisplayed()
        compose.onNodeWithText("Πες το").assertIsDisplayed()
    }

    /** The whole loop a caregiver would try first: pick a word, hear the sentence, take it back. */
    @Test fun tapFillsStripSpeaksAndBackspaceEmpties() {
        compose.onNodeWithText("Μίλα").performClick()
        // The seed import runs on the app's own scope; the food tab appears once it lands.
        compose.waitUntil(SEED_TIMEOUT_MS) { compose.onAllNodesWithText(FOOD_TAB).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(FOOD_TAB).performClick()

        compose.onNodeWithTag(BOARD_GRID_TAG).performScrollToNode(hasText(WORD))
        assertEquals("only the card should say it before the tap", 1, compose.onAllNodesWithText(WORD).fetchSemanticsNodes().size)
        compose.onAllNodesWithText(WORD)[0].performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(WORD).fetchSemanticsNodes().size == 2 }

        compose.onNodeWithText("Πες το").performClick()

        compose.onNodeWithContentDescription("Σβήσε το τελευταίο").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(WORD).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Πάτα εικόνες για να φτιάξεις πρόταση.").assertIsDisplayed()
    }

    /**
     * «Ολόκληρη», from two tapped words to a row — the core therapy of spec §13 on a real screen.
     *
     * The judge is a fake client answering with one JSON object, because the emulator has no way to
     * reach the API and Chris' key is not a test fixture. Everything after the reply is the real
     * thing: the sentence in the strip area, the microphone it hands him, the gentle check against
     * what he said, and what lands in `attempts`.
     */
    @Test fun twoWordsBecomeAWholeSentenceHeSaysBack() {
        withJudge(WHOLE_SENTENCE)
        fake.willHear("πρέπει να πάρω τα φάρμακα")
        val words = seed(FIRST, SECOND)
        val before = expansions()
        show()

        words.forEach { tapCard(it.text) }
        compose.waitUntil(UI_TIMEOUT_MS) { exists(EXPAND_TAG) }
        compose.onNodeWithTag(EXPAND_TAG).performClick()

        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(WHOLE_SENTENCE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(EXPAND_SENTENCE_TAG).assertTextEquals(WHOLE_SENTENCE)
        // The words he tapped are still under it: he can see what the sentence was built out of.
        compose.onAllNodesWithText(FIRST)[0].assertIsDisplayed()

        compose.onNodeWithTag(EXPAND_SAY_TAG).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { expansions().size == before.size + 1 }

        val row = (expansions() - before.toSet()).single()
        assertEquals("the phone agreed with him, so it is his own work", Outcome.CORRECT, row.outcome)
        assertEquals("he heard the sentence before he said it", EXPAND_CUE_LEVEL, row.cueLevel)
        assertTrue("the row says what kind of row it is: ${row.detail}", row.detail.contains("\"kind\":\"expand\""))
        assertTrue("the words he tapped belong in it: ${row.detail}", row.detail.contains("\"words\":\"$FIRST $SECOND\""))
        assertTrue("and the sentence he was given: ${row.detail}", row.detail.contains("\"expanded\":\"$WHOLE_SENTENCE\""))
        assertTrue("and that Claude wrote it: ${row.detail}", row.detail.contains("\"source\":\"JUDGE\""))
        assertTrue("and that the phone agreed: ${row.detail}", row.detail.contains("\"sttMatched\":true"))
    }

    /**
     * The board's own «Πες το», with a sentence on the screen, says the sentence again — and only
     * that. It does not read out his three telegraphic words, it does not close the sentence, and it
     * writes no row: he can hear it as many times as he needs to before «Μίλα».
     */
    @Test fun theBoardsOwnButtonSaysTheSentenceAgainAndKeepsIt() {
        withJudge(WHOLE_SENTENCE)
        val words = seed(FIRST, SECOND)
        val before = expansions()
        show()

        words.forEach { tapCard(it.text) }
        compose.waitUntil(UI_TIMEOUT_MS) { exists(EXPAND_TAG) }
        compose.onNodeWithTag(EXPAND_TAG).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(WHOLE_SENTENCE).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(SPEAK_STRIP).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(EXPAND_SENTENCE_TAG).assertTextEquals(WHOLE_SENTENCE)
        compose.onNodeWithTag(EXPAND_SAY_TAG).assertIsDisplayed()
        assertEquals("hearing it again is not an answer, so nothing is written", before.size, expansions().size)
    }

    /** Without a key and the toggle there is no button at all — not a greyed one, none. */
    @Test fun withoutTheJudgeThereIsNoWholeSentenceButton() {
        val words = seed(FIRST, SECOND)
        show()

        words.forEach { tapCard(it.text) }
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(SECOND).fetchSemanticsNodes().size == 2 }
        assertEquals("a button with nothing behind it is not on the screen", 0, compose.onAllNodesWithTag(EXPAND_TAG).fetchSemanticsNodes().size)
    }

    /**
     * The judge fell over mid-flow — no network, a key that stopped working. He still gets his own
     * words read back to him and the row says where they came from, because [LocalJudge] hands
     * `heard` back rather than inventing Greek grammar.
     *
     * Driven through the ViewModel: what is being proved is the fallback and the row, and a real
     * `TurnJudge` reaching its fake client is the whole point of the case.
     */
    @Test fun aJudgeThatCouldNotAnswerGivesHimHisOwnWordsBack() {
        graph.judge = TurnJudge(
            secrets = { KEY },
            enabled = { true },
            client = JudgeClient { _, _, _ -> throw IOException("offline") },
        )
        val words = seed(FIRST, SECOND)
        val before = expansions()

        val vm = viewModel()
        compose.runOnUiThread { words.forEach { vm.tap(it) } }
        compose.runOnUiThread { vm.expand() }

        compose.waitUntil(UI_TIMEOUT_MS) { vm.expansion.value?.sentence != null }
        assertEquals("his own words, unchanged", "$FIRST $SECOND", vm.expansion.value?.sentence)

        // And walking away from it writes what really happened, once.
        compose.runOnUiThread { vm.closeExpansion() }
        compose.waitUntil(UI_TIMEOUT_MS) { expansions().size == before.size + 1 }
        val row = (expansions() - before.toSet()).single()
        assertEquals(Outcome.SKIPPED, row.outcome)
        assertTrue("the row says the phone answered, not Claude: ${row.detail}", row.detail.contains("\"source\":\"LOCAL\""))
    }

    /** A judge that answers with one canned verdict, and the recogniser and key it needs behind it. */
    private fun withJudge(expanded: String) {
        runBlocking {
            graph.secrets.setClaudeKey(KEY)
            graph.settings.setClaudeJudging(true)
            graph.settings.setSttEnabled(true)
        }
        graph.stt = fake
        graph.judge = TurnJudge(
            secrets = { KEY },
            enabled = { true },
            client = JudgeClient { _, _, _ -> """{"accept":true,"expanded":"$expanded","feedback":null,"score":1}""" },
        )
    }

    /**
     * Two words of our own, pinned. Pinned because the favourites tab puts pinned words first and
     * alphabetically, so they are in the top row of the grid the board opens on — no tab to find and
     * no lazy grid to scroll, which is what the board's own seeded vocabulary would have cost.
     */
    private fun seed(vararg words: String): List<Item> = runBlocking {
        words.map { text ->
            graph.items.save(Item(text = text, category = Category.FOOD, pinned = true)).also { seeded += it }
        }
    }

    /** The card in the grid, never the chip in the strip: only one of the two can be pressed. */
    private fun tapCard(text: String) {
        compose.waitUntil(SEED_TIMEOUT_MS) { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(text) and hasClickAction())[0].performClick()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Built through a store of our own, so [putItAllBack] really clears it when the case is over. */
    private fun viewModel(): TalkBoardViewModel {
        lateinit var vm: TalkBoardViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(store, viewModelFactory { initializer { TalkBoardViewModel(graph) } })[TalkBoardViewModel::class.java]
        }
        return vm
    }

    private fun expansions(): List<Attempt> =
        runBlocking { graph.db.attempts().since(0).filter { it.itemId == EXPAND_ITEM } }

    /** The board on its own, with our own words in it. */
    private fun show() {
        compose.runOnUiThread {
            compose.activity.setContent {
                DimitrisTheme {
                    CompositionLocalProvider(LocalAppGraph provides graph, LocalFeedback provides graph.feedback) {
                        TalkBoardScreen(onBack = {})
                    }
                }
            }
        }
    }

    private companion object {
        const val FOOD_TAB = "Φαγητό & ποτό"
        const val WORD = "καφές"
        const val FIRST = "φάρμακα"
        const val SECOND = "πρέπει"
        const val WHOLE_SENTENCE = "Πρέπει να πάρω τα φάρμακα."

        /** The board's own bottom button: the phone reading out what is on the strip area. */
        const val SPEAK_STRIP = "Πες το"
        const val KEY = "δοκιμαστικό-κλειδί"
        const val SEED_TIMEOUT_MS = 30_000L
        const val UI_TIMEOUT_MS = 20_000L
    }
}
