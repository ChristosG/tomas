package gr.dimitris.app.core.difficulty

import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.FakeScriptDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Speaker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the two modules that are graded by *what they offer him* start their dots, on a phone that
 * was already being practised with before the dots existed.
 *
 * Both are read the same way: the hardest thing he has actually been given, where a schedule row is
 * the proof that he was given it. A phrase or a dialogue nobody has ever opened says nothing about
 * him and must not set his difficulty.
 */
class DifficultyInitTest {
    private val items = FakeItemDao()
    private val scripts = FakeScriptDao()
    private val schedules = FakeScheduleDao()

    private suspend fun phrase(text: String): Item =
        Item(text = text, kind = ItemKind.PHRASE).also { items.upsert(it) }

    private suspend fun practised(itemId: String, module: ModuleId) {
        schedules.upsert(Schedule(itemId = itemId, module = module, nextDueAt = 0))
    }

    private suspend fun dialogue(title: String, turns: Int): String {
        val script = Script(title = title)
        scripts.upsertScript(script)
        scripts.upsertLines(
            (0 until turns).map { i ->
                ScriptLine(scriptId = script.id, position = i, speaker = Speaker.DIMITRIS, itemId = "${script.id}-$i")
            },
        )
        return script.id
    }

    // ------------------------------------------------- «Τραγούδα και πες το»

    /** The dot that still admits the longest phrase he has been singing — so nothing he had is lost. */
    @Test fun `sing-then-say starts at the dot that admits the longest phrase he has practised`() = runTest {
        val short = phrase("ναι")                        // 1 syllable
        val long = phrase("καλημέρα σας")                // 5
        phrase("θέλω να πάω στο σπίτι μου")              // 9 — never opened, so not evidence
        practised(short.id, ModuleId.SINGSAY)
        practised(long.id, ModuleId.SINGSAY)

        assertEquals(Difficulty.syllableDot(5), DifficultyInit.singSayDot(items, schedules))
    }

    /** Nothing practised is no evidence, and the caller falls back to the spec's default. */
    @Test fun `sing-then-say has nothing to say about a phone nobody has sung on`() = runTest {
        phrase("θέλω καφέ")
        assertNull(DifficultyInit.singSayDot(items, schedules))
    }

    /** And whatever it derives keeps his own phrases in reach, which is the point of the derivation. */
    @Test fun `the derived sing-then-say dot still admits everything he was practising`() = runTest {
        val a = phrase("ναι")
        val b = phrase("καλημέρα σας")
        practised(a.id, ModuleId.SINGSAY)
        practised(b.id, ModuleId.SINGSAY)
        val dot = DifficultyInit.singSayDot(items, schedules)!!
        listOf(a, b).forEach {
            assertEquals(
                "«${it.text}» dropped out on the upgrade", true,
                Difficulty.syllablesOf(it.text) <= Difficulty.syllableCeiling(dot),
            )
        }
    }

    // --------------------------------------------------------------- «Διάλογοι»

    @Test fun `dialogues start at the dot that admits the longest conversation he has had`() = runTest {
        val short = dialogue("Στον φούρνο", turns = 2)
        val long = dialogue("Τηλέφωνο", turns = 8)
        dialogue("Ποτέ", turns = 12)                     // never opened, so not evidence
        practised(short, ModuleId.SCRIPTS)
        practised(long, ModuleId.SCRIPTS)

        assertEquals(Difficulty.turnDot(8), DifficultyInit.scriptsDot(scripts, schedules))
    }

    @Test fun `dialogues have nothing to say about a phone nobody has talked on`() = runTest {
        dialogue("Στην καφετέρια", turns = 4)
        assertNull(DifficultyInit.scriptsDot(scripts, schedules))
    }

    /** The six the app ships give him four turns each, which is the default dot exactly. */
    @Test fun `a phone that has only had the shipped dialogues lands on the default`() = runTest {
        val id = dialogue("Στην καφετέρια", turns = 4)
        practised(id, ModuleId.SCRIPTS)
        assertEquals(Difficulty.DEFAULT, DifficultyInit.scriptsDot(scripts, schedules))
    }
}
