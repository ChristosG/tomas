package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.caregiver.progress.DayStat
import gr.dimitris.app.caregiver.progress.ModuleStat
import gr.dimitris.app.caregiver.progress.Progress
import gr.dimitris.app.caregiver.progress.ProgressStats
import gr.dimitris.app.caregiver.progress.WeekCue
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The lines a caregiver actually reads. Every string here is checked whole: a dashboard that says
 * something slightly wrong in Greek is worse than one that says nothing.
 */
class InsightRulesTest {
    private val zone: ZoneId = ZoneId.of("Europe/Athens")
    private fun startOf(date: String): Long = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private val from = startOf("2026-08-09")
    private val to = startOf("2026-09-05") + 23 * 3_600_000L

    /** Four weeks of days; [busyFrom] onwards has attempts, everything before it is quiet. */
    private fun days(busyFrom: String = "2026-08-09", busyTo: String = "2026-09-05"): List<DayStat> {
        var d = LocalDate.parse("2026-08-09")
        val last = LocalDate.parse("2026-09-05")
        val out = mutableListOf<DayStat>()
        while (!d.isAfter(last)) {
            val busy = !d.isBefore(LocalDate.parse(busyFrom)) && !d.isAfter(LocalDate.parse(busyTo))
            out += DayStat(d.atStartOfDay(zone).toInstant().toEpochMilli(), minutes = if (busy) 10 else 0, attempts = if (busy) 4 else 0)
            d = d.plusDays(1)
        }
        return out
    }

    private fun progress(
        streak: Int = 1,
        modules: List<ModuleStat> = emptyList(),
        cueTrend: List<WeekCue> = emptyList(),
        mostSkipped: List<Pair<String, Int>> = emptyList(),
        days: List<DayStat> = days(),
    ) = Progress(
        from = from, to = to, days = days, streakDays = streak, modules = modules, cueTrend = cueTrend,
        mastered = 0, mostSkipped = mostSkipped, mostUsedTalk = emptyList(),
    )

    private fun lines(p: Progress, attempts: List<Attempt> = emptyList(), items: Map<String, Item> = emptyMap()) =
        InsightRules.generate(p, attempts, items)

    @Test fun `a quiet week says nothing at all`() =
        assertEquals(emptyList<String>(), lines(progress()))

    @Test fun `three days in a row is worth saying`() =
        assertTrue("Σερί 3 ημερών. Συνέχισε έτσι!" in lines(progress(streak = 3)))

    @Test fun `two days in a row is not`() =
        assertTrue(lines(progress(streak = 2)).none { it.startsWith("Σερί") })

    @Test fun `a module going well is named with its number`() {
        val stat = ModuleStat(ModuleId.WORDCOACH, attempts = 20, correct = 17, assisted = 2, skipped = 1)
        assertTrue("Οι Λέξεις πάνε πολύ καλά: 85% σωστά." in lines(progress(modules = listOf(stat))))
    }

    @Test fun `a good module with too few tries stays quiet`() {
        val stat = ModuleStat(ModuleId.WORDCOACH, attempts = 10, correct = 10, assisted = 0, skipped = 0)
        assertTrue(lines(progress(modules = listOf(stat))).none { it.contains("πάνε πολύ καλά") })
    }

    @Test fun `a busy module below eighty percent stays quiet`() {
        val stat = ModuleStat(ModuleId.WORDCOACH, attempts = 40, correct = 30, assisted = 5, skipped = 5)
        assertTrue(lines(progress(modules = listOf(stat))).none { it.contains("πάνε πολύ καλά") })
    }

    /** Every module has a Greek subject that takes a plural verb; none of them reads as a rewrite. */
    @Test fun `every module can be named in the sentence`() {
        ModuleId.entries.forEach { id ->
            val subject = InsightRules.subject(id)
            assertTrue("$id: $subject", subject.startsWith("Ο") || subject.startsWith("Τα"))
            assertTrue("$id: $subject", subject.length > 5)
        }
        // The seven graded ones say it for real; the talk board is the exception below.
        ProgressStats.GRADED_MODULES.forEach { id ->
            val stat = ModuleStat(id, attempts = 20, correct = 20, assisted = 0, skipped = 0)
            val line = lines(progress(modules = listOf(stat))).single { it.contains("πάνε πολύ καλά") }
            assertTrue("$id: $line", line.endsWith(" πάνε πολύ καλά: 100% σωστά.") && line.length > 30)
        }
    }

    /**
     * Every talk-board tap is written as CORRECT because it is him speaking, so the board is 100%
     * by construction and usually the busiest thing he does. Left in, it would own the one piece of
     * good news on the dashboard for ever and push the module he really worked at off the screen.
     */
    @Test fun `the talk board is never the module that is going well`() {
        val board = ModuleStat(ModuleId.TALKBOARD, attempts = 200, correct = 200, assisted = 0, skipped = 0)
        val words = ModuleStat(ModuleId.WORDCOACH, attempts = 20, correct = 18, assisted = 1, skipped = 1)

        assertTrue(lines(progress(modules = listOf(board))).none { it.contains("πάνε πολύ καλά") })
        assertEquals(
            listOf("Οι Λέξεις πάνε πολύ καλά: 90% σωστά."),
            lines(progress(modules = listOf(board, words))).filter { it.contains("πάνε πολύ καλά") },
        )
    }

    /**
     * The first-sound rule reads outcomes as "how well he did". Talk-board taps are all CORRECT, so
     * a month of using the board more would otherwise arrive as «οι «π» λέξεις βελτιώθηκαν» while
     * his word-coach accuracy had not moved at all.
     */
    @Test fun `talking on the board is not a first sound getting better`() {
        val items = mapOf(
            "p1" to Item(id = "p1", text = "πόρτα", firstSound = "π"),
            "p2" to Item(id = "p2", text = "ποτήρι", firstSound = "π"),
        )
        // The past: six graded tries on «π» words, two of them right.
        val old = List(6) { i ->
            Attempt(itemId = if (i % 2 == 0) "p1" else "p2", module = ModuleId.WORDCOACH, startedAt = from - 5 * 86_400_000L,
                durationMs = 1000, outcome = if (i < 2) Outcome.CORRECT else Outcome.ASSISTED)
        }
        // This period: the same three-out-of-three-wrong-ish word coach, plus a lot of board taps.
        val graded = List(3) { i ->
            Attempt(itemId = "p1", module = ModuleId.WORDCOACH, startedAt = from + 5 * 86_400_000L,
                durationMs = 1000, outcome = if (i < 1) Outcome.CORRECT else Outcome.ASSISTED)
        }
        val board = List(6) {
            Attempt(itemId = "p2", module = ModuleId.TALKBOARD, startedAt = from + 6 * 86_400_000L,
                durationMs = 1000, outcome = Outcome.CORRECT)
        }

        assertTrue(lines(progress(), old + graded + board, items).none { it.contains("αρχίζουν από") })
    }

    @Test fun `less help than last week is worth saying`() {
        val trend = listOf(WeekCue(startOf("2026-08-24"), 2.6f), WeekCue(startOf("2026-08-31"), 1.8f))
        assertTrue("Χρειάζεται λιγότερη βοήθεια από την προηγούμενη εβδομάδα." in lines(progress(cueTrend = trend)))
    }

    @Test fun `a small change in help is not news`() {
        val trend = listOf(WeekCue(startOf("2026-08-24"), 2.2f), WeekCue(startOf("2026-08-31"), 2.0f))
        assertTrue(lines(progress(cueTrend = trend)).none { it.contains("λιγότερη βοήθεια") })
    }

    @Test fun `a first sound that improved is named`() {
        val items = mapOf(
            "p1" to Item(id = "p1", text = "πόρτα", firstSound = "π"),
            "p2" to Item(id = "p2", text = "ποτήρι", firstSound = "π"),
        )
        val old = List(6) { i ->
            Attempt(itemId = if (i % 2 == 0) "p1" else "p2", module = ModuleId.WORDCOACH, startedAt = from - 5 * 86_400_000L,
                durationMs = 1000, outcome = if (i < 2) Outcome.CORRECT else Outcome.ASSISTED)
        }
        val fresh = List(6) { i ->
            Attempt(itemId = if (i % 2 == 0) "p1" else "p2", module = ModuleId.WORDCOACH, startedAt = from + 5 * 86_400_000L,
                durationMs = 1000, outcome = if (i < 5) Outcome.CORRECT else Outcome.SKIPPED)
        }
        assertTrue("Οι λέξεις που αρχίζουν από «π» βελτιώθηκαν." in lines(progress(), old + fresh, items))
    }

    @Test fun `a first sound with no past to compare against stays quiet`() {
        val items = mapOf("p1" to Item(id = "p1", text = "πόρτα", firstSound = "π"))
        val fresh = List(6) {
            Attempt(itemId = "p1", module = ModuleId.WORDCOACH, startedAt = from + 5 * 86_400_000L,
                durationMs = 1000, outcome = Outcome.CORRECT)
        }
        assertTrue(lines(progress(), fresh, items).none { it.contains("αρχίζουν από") })
    }

    @Test fun `three words he keeps skipping become a suggestion`() {
        val skipped = listOf("καφές" to 5, "ψωμί" to 4, "νερό" to 3, "τραπέζι" to 1)
        assertTrue(
            "Δύσκολες λέξεις: καφές, ψωμί, νερό. Δοκίμασε φωτογραφία ή φωνή." in lines(progress(mostSkipped = skipped))
        )
    }

    @Test fun `two hard words are not a pattern`() {
        val skipped = listOf("καφές" to 5, "ψωμί" to 4, "νερό" to 2)
        assertTrue(lines(progress(mostSkipped = skipped)).none { it.startsWith("Δύσκολες λέξεις") })
    }

    @Test fun `a long silence is said plainly`() {
        val p = progress(days = days(busyTo = "2026-08-31"))
        assertTrue("Καμία άσκηση εδώ και 5 μέρες." in lines(p))
    }

    @Test fun `two quiet days are not worth a line`() {
        val p = progress(days = days(busyTo = "2026-09-03"))
        assertTrue(lines(p).none { it.startsWith("Καμία άσκηση") })
    }

    /** Nothing at all in four weeks is still one honest line, not a blank screen. */
    @Test fun `a window with no work at all counts the whole window`() {
        val p = progress(days = days().map { it.copy(minutes = 0, attempts = 0) })
        assertTrue("Καμία άσκηση εδώ και 28 μέρες." in lines(p))
    }

    @Test fun `six lines at most, best news first`() {
        val items = mapOf("p1" to Item(id = "p1", text = "πόρτα", firstSound = "π"))
        val old = List(6) {
            Attempt(itemId = "p1", module = ModuleId.WORDCOACH, startedAt = from - 5 * 86_400_000L,
                durationMs = 1000, outcome = Outcome.SKIPPED)
        }
        val fresh = List(6) {
            Attempt(itemId = "p1", module = ModuleId.WORDCOACH, startedAt = from + 5 * 86_400_000L,
                durationMs = 1000, outcome = Outcome.CORRECT)
        }
        val p = progress(
            streak = 7,
            modules = listOf(
                ModuleStat(ModuleId.WORDCOACH, attempts = 20, correct = 18, assisted = 1, skipped = 1),
                ModuleStat(ModuleId.NUMBERS, attempts = 30, correct = 29, assisted = 1, skipped = 0),
            ),
            cueTrend = listOf(WeekCue(startOf("2026-08-24"), 3f), WeekCue(startOf("2026-08-31"), 1f)),
            mostSkipped = listOf("καφές" to 5, "ψωμί" to 4, "νερό" to 3),
            days = days(busyTo = "2026-08-31"),
        )
        val out = lines(p, old + fresh, items)
        assertEquals(6, out.size)
        assertTrue(out[0].startsWith("Σερί"))
        assertTrue(out[1].contains("πάνε πολύ καλά"))
        assertTrue(out[2].contains("λιγότερη βοήθεια"))
        assertTrue(out[3].contains("αρχίζουν από"))
        assertTrue(out[4].startsWith("Δύσκολες λέξεις"))
        assertTrue(out[5].startsWith("Καμία άσκηση"))
    }

    /** The busiest of the modules that qualify is the one worth naming. */
    @Test fun `only one module is named`() {
        val p = progress(
            modules = listOf(
                ModuleStat(ModuleId.WORDCOACH, attempts = 20, correct = 20, assisted = 0, skipped = 0),
                ModuleStat(ModuleId.NUMBERS, attempts = 40, correct = 36, assisted = 4, skipped = 0),
            )
        )
        val named = lines(p).filter { it.contains("πάνε πολύ καλά") }
        assertEquals(listOf("Οι Αριθμοί πάνε πολύ καλά: 90% σωστά."), named)
    }
}
