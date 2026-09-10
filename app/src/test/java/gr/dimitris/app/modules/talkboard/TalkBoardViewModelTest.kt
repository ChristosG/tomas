package gr.dimitris.app.modules.talkboard

import com.google.gson.Gson
import com.google.gson.JsonObject
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.LocalJudge
import gr.dimitris.app.core.judge.Source
import gr.dimitris.app.core.judge.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Ολόκληρη», the core therapy of spec §13, argued with in a second rather than on a phone.
 *
 * The board's ViewModel cannot be built without an Android context, so the rules of the expansion
 * live in [ExpansionFlow] and everything it touches is a function value — which is why a fake judge
 * here is three lines rather than a network. What is pinned:
 *
 * * the words he tapped go up as an EXPAND and nothing else does;
 * * the sentence that comes back is shown **and** spoken, because hearing it is the whole point;
 * * a repeat the phone agrees with is written as his own work, at the cue level a sentence he was
 *   given says it was;
 * * a judge that fell back hands his own words back unchanged — it never invents Greek grammar;
 * * and every way out of the flow writes exactly one row, or none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TalkBoardViewModelTest {
    private val gson = Gson()

    private val words = "φάρμακα πρέπει πάρω"
    private val whole = "Πρέπει να πάρω τα φάρμακα."

    private val asked = mutableListOf<Ask>()
    private val said = mutableListOf<String>()
    private val rows = mutableListOf<Attempt>()
    private var successes = 0
    private var nudges = 0
    private var stops = 0
    private var windowsOpened = 0

    /** A clock that only moves when something took time, so a `ms` in a row means something. */
    private var clock = 10_000L

    /** What the judge answers. A real Haiku verdict by default; a test that wants LOCAL says so. */
    private var verdict: Verdict = Verdict(accept = true, expanded = whole, source = Source.JUDGE)

    /** Held open when a test wants to look at the screen while the judge is still thinking. */
    private var judgeGate: CompletableDeferred<Unit>? = null

    /** What the recogniser hands back, window by window. */
    private val windows = ArrayDeque<Heard>()

    private var sttOn = true
    private var speakFails = false

    /**
     * The flow under test, on the test scheduler. A [TestScope] of its own rather than the test's own
     * `backgroundScope`, which is what `AdviceSessionTest` does and for the same reason: it is the
     * shape that actually runs under `advanceUntilIdle` here.
     */
    private fun TestScope.flow() = ExpansionFlow(
        scope = TestScope(StandardTestDispatcher(testScheduler)),
        askJudge = { ask ->
            asked += ask
            judgeGate?.await()
            clock += JUDGE_MS
            verdict
        },
        difficulty = { EXPAND_DIFFICULTY },
        speakOut = { text ->
            said += text
            if (speakFails) Result.failure(IllegalStateException("no voice")) else Result.success(Unit)
        },
        sttOn = { sttOn },
        openWindow = {
            windowsOpened++
            clock += WINDOW_MS
            windows.removeFirstOrNull() ?: Heard.Silence
        },
        closeWindow = { stops++ },
        writeRow = { row -> rows += row },
        onSuccess = { successes++ },
        onNudge = { nudges++ },
        now = { clock },
    )

    private fun detail(row: Attempt): JsonObject = gson.fromJson(row.detail, JsonObject::class.java)

    // ---------------------------------------------------------------- The sentence

    @Test fun `the words he tapped come back as one whole sentence, shown and spoken`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        val ask = asked.single()
        assertEquals("the judge is asked for an expansion and nothing else", Kind.EXPAND, ask.kind)
        assertEquals("what he tapped, joined, is the whole of what goes up", words, ask.heard)
        assertNull("there was no question asked of him", ask.prompt)
        assertNull("and no sentence he was supposed to produce", ask.target)
        assertEquals("his own difficulty goes with it", EXPAND_DIFFICULTY, ask.difficulty)

        val state = flow.state.value!!
        assertEquals("the sentence is on the screen", whole, state.sentence)
        assertFalse("and the screen has stopped waiting", state.thinking)
        assertEquals("and it was said out loud, which is the point of it", listOf(whole), said)
        assertEquals("nothing is written for a sentence he has not answered yet", 0, rows.size)
    }

    @Test fun `while the judge is thinking there is a line on the screen and no sentence yet`() = runTest {
        judgeGate = CompletableDeferred()
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        assertTrue("the screen says it is being made", flow.state.value!!.thinking)
        assertEquals("and nothing has been said out loud", 0, said.size)

        judgeGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(whole, flow.state.value!!.sentence)
    }

    /**
     * The fallback, which is the state the phone is in whenever the network is not there: it hands
     * [Ask.heard] straight back. His own words, unchanged — never a local guess at Greek grammar,
     * which would teach him wrong forms — and the rest of the flow runs exactly as it does otherwise.
     */
    @Test fun `a judge that fell back shows his own words unchanged`() = runTest {
        verdict = LocalJudge.judge(Ask(kind = Kind.EXPAND, heard = words, difficulty = EXPAND_DIFFICULTY))
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        assertEquals("his words, and nothing invented around them", words, flow.state.value!!.sentence)
        assertEquals("and they are still said to him", listOf(words), said)

        windows += Heard.Words(words)
        flow.sayIt()
        advanceUntilIdle()
        assertEquals("the fallback is on the row for a reader to see", "LOCAL", detail(rows.single())["judge"].asJsonObject["source"].asString)
    }

    @Test fun `a sentence that could not be said is said on the screen instead`() = runTest {
        speakFails = true
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        assertEquals("silence is the one failure he cannot diagnose himself", SPEECH_FAILED, flow.state.value!!.error)
        assertEquals("and the sentence is still there to read and repeat", whole, flow.state.value!!.sentence)
    }

    // ---------------------------------------------------------------- Saying it back

    @Test fun `a repeat the phone agrees with is written as his own work`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        windows += Heard.Words("πρέπει να πάρω τα φάρμακα")
        flow.sayIt()
        advanceUntilIdle()

        val state = flow.state.value!!
        assertTrue("the phone agreed with him, so it is done", state.done)
        assertTrue(state.matched)
        assertEquals("and he is told so", 1, successes)

        val row = rows.single()
        assertEquals(Outcome.CORRECT, row.outcome)
        assertEquals(ModuleId.TALKBOARD, row.module)
        assertEquals("an expansion is about the sentence, not about any one word in it", EXPAND_ITEM, row.itemId)
        assertEquals("he heard it before he said it, and the row says so", EXPAND_CUE_LEVEL, row.cueLevel)

        val o = detail(row)
        assertEquals(EXPAND_KIND, o["kind"].asString)
        assertEquals(words, o["words"].asString)
        assertEquals(whole, o["expanded"].asString)
        assertEquals("πρέπει να πάρω τα φάρμακα", o["sttHeard"].asString)
        assertTrue(o["sttMatched"].asBoolean)
        assertEquals("agreeing with him cost him no try", 0, o["sttTries"].asInt)
        val judge = o["judge"].asJsonObject
        assertEquals("JUDGE", judge["source"].asString)
        assertTrue(judge["accept"].asBoolean)
        assertEquals("how long the verdict took is the reason that key exists", JUDGE_MS, judge["ms"].asLong)
    }

    /**
     * The other half of the gentle check: a miss is a nudge and never a wall. One «Δοκίμασε ξανά»
     * with the sentence untouched, and then «Το είπα!» comes back and confirms as it always did.
     */
    @Test fun `two misses leave him the confirm and the row says he was helped`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        windows += Heard.Words("κάτι άλλο")
        flow.sayIt()
        advanceUntilIdle()
        var state = flow.state.value!!
        assertEquals("one miss is one nudge", true, state.nudge)
        assertEquals(1, state.sttTries)
        assertEquals("«Το είπα!» is not his yet", false, state.canConfirm)
        assertEquals("the sentence has not moved", whole, state.sentence)
        assertEquals("and nothing has been written", 0, rows.size)
        assertEquals(1, nudges)

        windows += Heard.Words("κάτι άλλο")
        flow.sayIt()
        advanceUntilIdle()
        state = flow.state.value!!
        assertEquals("the phone stops asking", false, state.nudge)
        assertEquals("and the button is his", true, state.canConfirm)

        flow.confirm()
        val row = rows.single()
        assertEquals("his word against the phone's is work done with help", Outcome.ASSISTED, row.outcome)
        val o = detail(row)
        assertFalse("and the row says the phone never agreed", o["sttMatched"].asBoolean)
        assertEquals(2, o["sttTries"].asInt)
        assertTrue("the flow is done and only «Κλείσε» is left", flow.state.value!!.done)
    }

    @Test fun `a window that heard nothing is answered gently and costs him no try`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        windows += Heard.Silence
        flow.sayIt()
        advanceUntilIdle()

        val state = flow.state.value!!
        assertEquals(HEARD_NOTHING, state.error)
        assertEquals("the phone did not disagree with him, so no try was spent", 0, state.sttTries)
        assertEquals("and he is not nudged for it", false, state.nudge)
        assertEquals("one dead window is not yet two goes", false, state.canConfirm)
        assertEquals("nothing was written", 0, rows.size)
    }

    /**
     * A phone that could not listen at all. Chris' whole report was about the app putting its own
     * trouble on him: it costs him no try, the confirm opens at once and stays open, and the line
     * names the real trouble instead of saying something about his voice.
     */
    @Test fun `a phone that cannot listen costs him nothing and never takes the confirm back`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        windows += Heard.Broken(BROKEN_LINE)
        flow.sayIt()
        advanceUntilIdle()

        var state = flow.state.value!!
        assertEquals(BROKEN_LINE, state.error)
        assertEquals(0, state.sttTries)
        assertEquals(false, state.nudge)
        assertEquals("«Το είπα!» is his at once", true, state.canConfirm)

        // And a miss afterwards does not close the door the broken recogniser opened.
        windows += Heard.Words("κάτι άλλο")
        flow.sayIt()
        advanceUntilIdle()
        state = flow.state.value!!
        assertEquals("the confirm a broken recogniser opened is not taken away", true, state.canConfirm)

        flow.confirm()
        assertEquals(Outcome.ASSISTED, rows.single().outcome)
    }

    @Test fun `with recognition off the confirm is his from the first moment`() = runTest {
        sttOn = false
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        assertEquals("there is nothing to repeat into, so nothing is withheld", true, flow.state.value!!.canConfirm)
        flow.sayIt()
        advanceUntilIdle()
        assertEquals("and no window was ever opened", 0, windowsOpened)

        flow.confirm()
        val row = rows.single()
        assertEquals(Outcome.ASSISTED, row.outcome)
        assertFalse("a row with no recogniser says nothing about one: ${row.detail}", detail(row).has("sttHeard"))
    }

    @Test fun `«Στοπ» closes the window he had open, and only one`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        flow.stop()
        assertEquals("there was no window to close", 0, stops)

        windows += Heard.Words("πρέπει να πάρω τα φάρμακα")
        flow.sayIt()
        flow.stop()
        assertEquals(1, stops)
        advanceUntilIdle()
        assertTrue("and what it had heard still counted", flow.state.value!!.done)
    }

    // ---------------------------------------------------------------- One row, or none

    @Test fun `a sentence he heard and walked away from is a row that says so`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        flow.close()
        assertNull("the board is back to its ordinary state", flow.state.value)
        val row = rows.single()
        assertEquals(Outcome.SKIPPED, row.outcome)
        assertEquals("the sentence he was given is still on the row", whole, detail(row)["expanded"].asString)
        assertEquals("and he was not congratulated for it", 0, successes)
    }

    @Test fun `closing after a match does not add a second row`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()
        windows += Heard.Words("πρέπει να πάρω τα φάρμακα")
        flow.sayIt()
        advanceUntilIdle()

        flow.close()
        assertEquals("one expansion is one row", 1, rows.size)
        assertEquals(Outcome.CORRECT, rows.single().outcome)
    }

    @Test fun `a sentence that never arrived writes nothing at all`() = runTest {
        judgeGate = CompletableDeferred()
        val flow = flow()
        flow.open(words, len = 3)
        advanceUntilIdle()

        flow.close()
        assertNull(flow.state.value)
        assertEquals("he saw no sentence, so there is nothing he passed on", 0, rows.size)

        // The ask is abandoned with it: nothing comes back to a screen that has gone.
        judgeGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(flow.state.value)
        assertEquals(0, rows.size)
    }

    @Test fun `a second tap while one is open does nothing`() = runTest {
        val flow = flow()
        flow.open(words, len = 3)
        flow.open("άλλα λόγια", len = 2)
        advanceUntilIdle()

        assertEquals("one judge call, not two", 1, asked.size)
        assertEquals(words, flow.state.value!!.words)
    }

    // ---------------------------------------------------------------- Whether it is there at all

    /**
     * Without the judge there is no button, and that is deliberate: [LocalJudge] cannot build a Greek
     * sentence out of content words and must not pretend to, so a button that only ever handed his
     * own words back would be a promise the app breaks every time he presses it.
     */
    @Test fun `without the judge the button is not on the screen`() = runTest {
        assertFalse("no toggle, no key, no button", showsExpand(stripLen = 3, judgeReady = false))
        assertTrue(showsExpand(stripLen = 2, judgeReady = true))
        assertFalse("one word is not a sentence to expand", showsExpand(stripLen = 1, judgeReady = true))
        assertFalse(showsExpand(stripLen = 0, judgeReady = true))

        // And the door behind the button keeps the same rule, for a tap that raced the strip emptying.
        val flow = flow()
        flow.open("καφές", len = 1)
        advanceUntilIdle()
        assertNull(flow.state.value)
        assertEquals(0, asked.size)
    }

    private companion object {
        const val JUDGE_MS = 640L
        const val WINDOW_MS = 3_000L
        const val BROKEN_LINE = "Χρειάζεται σύνδεση για την αναγνώριση."
    }
}
