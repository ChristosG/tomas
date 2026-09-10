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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

    // ------------------------------------------- when the database arrives after the app does

    /**
     * The realistic second-device flow, as the DAOs see it: the derivation is asked once over a
     * database that holds nothing but the seed, and **then** his practice history turns up — by a
     * backup restored, or by the first sync pull. Asked again over the same DAOs, it now answers.
     *
     * Without the second ask the dots stayed at the default, and at the default the sing-then-say
     * ceiling is four syllables: every longer phrase that came with his data would have sat out of
     * the pool with its schedule row overdue for ever.
     */
    @Test fun `a derivation over an empty database answers once his rows arrive`() = runTest {
        assertNull("nothing on the device yet", DifficultyInit.singSayDot(items, schedules))

        val long = phrase("θέλω να πάω στο σπίτι μου")     // 9 syllables, restored with his data
        practised(long.id, ModuleId.SINGSAY)

        assertEquals(Difficulty.syllableDot(9), DifficultyInit.singSayDot(items, schedules))
    }

    /**
     * And the rule that gets it asked again: a database swap forgets the two dots that are read out
     * of the database, and nothing else. The first generation is the one the startup run was already
     * given, so it is not a swap.
     */
    @Test fun `a database swap forgets the two dots that came out of the database`() = runTest {
        val generations = MutableStateFlow(0)
        val forgotten = mutableListOf<Set<ModuleId>>()
        var derivations = 0
        backgroundScope.launch {
            DifficultyInit.watch(generations, forget = { forgotten += it }, derive = { derivations++ })
        }
        runCurrent()
        assertEquals("the generation the startup run already had is not a swap", 0, derivations)

        generations.value = 1                    // a backup restored
        runCurrent()
        generations.value = 2                    // and then a sync pull
        runCurrent()

        assertEquals(2, derivations)
        assertEquals(listOf(DifficultyInit.FROM_DATABASE, DifficultyInit.FROM_DATABASE), forgotten)
        // The other four read a level or a target size out of the preference store, which neither a
        // restore nor a sync touches.
        assertEquals(setOf(ModuleId.SINGSAY, ModuleId.SCRIPTS), DifficultyInit.FROM_DATABASE)
    }
}
