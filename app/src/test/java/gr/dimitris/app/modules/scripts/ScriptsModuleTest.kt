package gr.dimitris.app.modules.scripts

import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.FakeScriptDao
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.scheduler.LeitnerPolicy
import gr.dimitris.app.core.scheduler.Scheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules of the dialogue module that decide what he is handed and what it costs him: which
 * dialogue a session offers, and what a whole run does to that dialogue's Leitner box.
 *
 * Both used to be untested, and the first was wrong: reading the due rows through a `Set` threw away
 * `ScheduleDao.due`'s ordering and left the choice to `activeScripts()` — `ORDER BY title` — so the
 * alphabetically first dialogue was handed to him every day and the rest were never seen again.
 */
class ScriptsModuleTest {
    private val scripts = FakeScriptDao()
    private val schedules = FakeScheduleDao()

    private val day = 24 * 60 * 60 * 1000L

    /** Far enough in that "a month ago" is still a real timestamp and not before the epoch. */
    private val now = 400 * day

    /** A dialogue with one line for each of them, which is what makes it worth planning. */
    private suspend fun dialogue(title: String, forHim: Boolean = true): String {
        val script = Script(title = title)
        scripts.upsertScript(script)
        val lines = buildList {
            add(ScriptLine(scriptId = script.id, position = 0, speaker = Speaker.OTHER, itemId = "${script.id}-0"))
            if (forHim) add(ScriptLine(scriptId = script.id, position = 1, speaker = Speaker.DIMITRIS, itemId = "${script.id}-1"))
        }
        scripts.upsertLines(lines)
        return script.id
    }

    private suspend fun due(scriptId: String, lastSeenAt: Long, dueAt: Long = now - day) {
        schedules.upsert(
            Schedule(itemId = scriptId, module = ModuleId.SCRIPTS, box = 1, nextDueAt = dueAt, lastSeenAt = lastSeenAt)
        )
    }

    @Test fun `nothing to practise gives nothing`() = runTest {
        assertNull(ScriptsModule.choose(scripts, schedules, now))
    }

    /** A dialogue of nothing but the other person's lines would produce no attempt at all. */
    @Test fun `a dialogue with no turn of his is never planned`() = runTest {
        dialogue("Μόνο ο άλλος", forHim = false)
        assertNull(ScriptsModule.choose(scripts, schedules, now))
        val his = dialogue("Με σειρά του")
        assertEquals(his, ScriptsModule.choose(scripts, schedules, now))
    }

    /**
     * The defect this replaces. «Α» sorts first and is due today; «Ω» is due too and he has not seen
     * it in a month. The month-old one is the one he needs.
     */
    @Test fun `among the due dialogues he gets the one he has gone longest without`() = runTest {
        val alphabeticallyFirst = dialogue("Α στην καφετέρια")
        val longestUnseen = dialogue("Ω στο ταξί")
        due(alphabeticallyFirst, lastSeenAt = now - day)
        due(longestUnseen, lastSeenAt = now - 30 * day)
        assertEquals(longestUnseen, ScriptsModule.choose(scripts, schedules, now))
    }

    /** Two rows last seen at the same moment: the DAO's own order — soonest due first — decides. */
    @Test fun `a tie keeps the order the dao gave them`() = runTest {
        val later = dialogue("Α αργότερα")
        val sooner = dialogue("Ω νωρίτερα")
        due(later, lastSeenAt = now - day, dueAt = now - day)
        due(sooner, lastSeenAt = now - day, dueAt = now - 5 * day)
        assertEquals(sooner, ScriptsModule.choose(scripts, schedules, now))
    }

    /** A dialogue not due yet is not offered, however long ago he last did it. */
    @Test fun `a dialogue that is not due is not chosen over one that is`() = runTest {
        val notDue = dialogue("Α όχι ακόμα")
        val isDue = dialogue("Ω σήμερα")
        schedules.upsert(Schedule(itemId = notDue, module = ModuleId.SCRIPTS, nextDueAt = now + 5 * day, lastSeenAt = now - 90 * day))
        due(isDue, lastSeenAt = now - day)
        assertEquals(isDue, ScriptsModule.choose(scripts, schedules, now))
    }

    /** With nothing due, the fallback: the one he has gone longest without, never-opened first. */
    @Test fun `with nothing due he gets the least recently practised`() = runTest {
        val old = dialogue("Α παλιό")
        val newer = dialogue("Β πρόσφατο")
        schedules.upsert(Schedule(itemId = old, module = ModuleId.SCRIPTS, nextDueAt = now + day, lastSeenAt = now - 10 * day))
        schedules.upsert(Schedule(itemId = newer, module = ModuleId.SCRIPTS, nextDueAt = now + day, lastSeenAt = now - day))
        assertEquals(old, ScriptsModule.choose(scripts, schedules, now))

        val never = dialogue("Γ ποτέ")
        assertEquals("a dialogue nobody has opened goes first", never, ScriptsModule.choose(scripts, schedules, now))
    }

    /** A schedule row left behind by a deleted dialogue can never be handed back to him. */
    @Test fun `a due row whose dialogue is gone is skipped`() = runTest {
        val live = dialogue("Ω ζωντανός")
        due("διαγραμμένος", lastSeenAt = 0)
        due(live, lastSeenAt = now - day)
        assertEquals(live, ScriptsModule.choose(scripts, schedules, now))
    }

    /** A conversation is never cut to fit the session budget: that is what makes the count honest. */
    @Test fun `the dialogue runs as one unit`() = assertTrue(ScriptsModule.atomic)

    // The other half: what a finished run does to the dialogue's box.

    @Test fun `a run with no help at all is correct`() =
        assertEquals(Outcome.CORRECT, ScriptsViewModel.outcomeOf(skipped = false, worstCue = 2))

    @Test fun `one turn that needed the line said makes the whole run assisted`() =
        assertEquals(Outcome.ASSISTED, ScriptsViewModel.outcomeOf(skipped = false, worstCue = 3))

    @Test fun `a turn passed over makes the run skipped whatever the cues were`() {
        assertEquals(Outcome.SKIPPED, ScriptsViewModel.outcomeOf(skipped = true, worstCue = 0))
        assertEquals(Outcome.SKIPPED, ScriptsViewModel.outcomeOf(skipped = true, worstCue = 4))
    }

    /**
     * What that outcome then costs him, through the same [Scheduler] the module calls: a clean run
     * moves the dialogue up a box, a run he needed the line for holds it where it is, and a turn he
     * passed over sends it back to the first box, due again today.
     */
    @Test fun `the outcome is what moves the dialogue through its boxes`() = runTest {
        val scheduler = Scheduler(schedules) { now }
        val id = dialogue("Στην καφετέρια")

        val clean = scheduler.record(id, ModuleId.SCRIPTS, ScriptsViewModel.outcomeOf(skipped = false, worstCue = 0), 0)
        assertEquals(2, clean.box)

        val helped = scheduler.record(id, ModuleId.SCRIPTS, ScriptsViewModel.outcomeOf(skipped = false, worstCue = 4), 4)
        assertEquals("said to him is still work: the box holds", 2, helped.box)

        val passed = scheduler.record(id, ModuleId.SCRIPTS, ScriptsViewModel.outcomeOf(skipped = true, worstCue = 0), 0)
        assertEquals(LeitnerPolicy.MIN_BOX, passed.box)
        assertEquals(0, passed.streak)
        assertEquals(now, passed.lastSeenAt)
    }

    // ---- the difficulty he sets himself (spec §13) --------------------------------------------

    /** A dialogue of two turns each, graded [tier] — what the seed writes and the editor sets. */
    private suspend fun graded(title: String, tier: Int, hisTier: Int = tier): String {
        val script = Script(title = title)
        scripts.upsertScript(script)
        scripts.upsertLines(
            listOf(
                ScriptLine(scriptId = script.id, position = 0, speaker = Speaker.OTHER, itemId = "${script.id}-0", tier = tier),
                ScriptLine(scriptId = script.id, position = 1, speaker = Speaker.DIMITRIS, itemId = "${script.id}-1", tier = hisTier),
            ),
        )
        return script.id
    }

    /**
     * The dots he sets himself, against the tier the dialogue carries: dot n takes every dialogue of
     * tier n and below. Dimitris told us the app was too easy; the eight harder dialogues phase 12
     * ships are tiers 3 to 5, and dot 1 is not the day for the one about the form at the office.
     */
    @Test fun `the difficulty he set caps how hard a dialogue may be`() = runTest {
        val easy = graded("Στον φούρνο", tier = 1)
        val hard = graded("Βοήθεια με ένα χαρτί", tier = 5)
        assertEquals("dot 1 is tier 1, and the hard one is not his today", listOf(easy), ScriptsModule.practisable(scripts, difficulty = 1))
        assertEquals(easy, ScriptsModule.choose(scripts, schedules, now, difficulty = 1))
        assertEquals(setOf(easy, hard), ScriptsModule.practisable(scripts, difficulty = 5).toSet())
    }

    /**
     * A ceiling, not a window: the errand at the bakery is still worth having on the day he asked
     * for hard work, and a caregiver who writes an easy one must not have it silently dropped with
     * its Leitner row left overdue for ever.
     */
    @Test fun `an easier dialogue stays practisable at a harder dot`() = runTest {
        val easy = graded("Στον φούρνο", tier = 1)
        graded("Βοήθεια με ένα χαρτί", tier = 5)
        assertTrue(easy in ScriptsModule.practisable(scripts, difficulty = 5))
        assertTrue(easy in ScriptsModule.practisable(scripts, difficulty = 3))
    }

    /**
     * A conversation *is* this module, so a ceiling no dialogue is under widens back to all of them:
     * "nothing for you today" for having asked for *easier* work is not an answer either.
     */
    @Test fun `a ceiling no dialogue is under widens back to all of them`() = runTest {
        val only = graded("Παράπονο στο μαγαζί", tier = 4)
        assertEquals(listOf(only), ScriptsModule.practisable(scripts, difficulty = 1))
        assertEquals(only, ScriptsModule.choose(scripts, schedules, now, difficulty = 1))
        assertEquals(listOf(only), ScriptsModule.practisable(scripts, difficulty = 4))
    }

    /**
     * A dialogue is as hard as its hardest turn, and a line that arrived over sync from a phone with
     * no tiers at all — a plain zero — is the easiest thing there is rather than a hole.
     */
    @Test fun `a dialogue is as hard as its hardest turn`() = runTest {
        val mixed = graded("Ραντεβού στον γιατρό", tier = 1, hisTier = 3)
        // A second, easy one, or the ceiling would have nothing under it and widen back to both.
        graded("Στον φούρνο", tier = 1)
        assertEquals(3, ScriptsModule.tierOf(scripts.linesFor(mixed)))
        assertTrue("his turn is the tier-3 one", mixed !in ScriptsModule.practisable(scripts, difficulty = 2))
        assertTrue(mixed in ScriptsModule.practisable(scripts, difficulty = 3))

        val old = graded("Πριν από τα tier", tier = 0)
        assertEquals("a line written before tiers is tier 1", 1, ScriptsModule.tierOf(scripts.linesFor(old)))
        assertTrue(old in ScriptsModule.practisable(scripts, difficulty = 1))
    }

    /** A dialogue of nothing but the other person's lines stays out, whatever the dots say. */
    @Test fun `the difficulty never admits a dialogue with no turn of his`() = runTest {
        dialogue("Μόνο ο άλλος", forHim = false)
        (1..5).forEach { d -> assertTrue(ScriptsModule.practisable(scripts, difficulty = d).isEmpty()) }
    }
}
