package gr.dimitris.app.caregiver.progress

import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fixed epoch times in one fixed zone: the dashboard's numbers are the only ones a caregiver has,
 * so "it depends on when you run the test" is not an acceptable answer for any of them.
 *
 * The week of Saturday 2026-09-05 starts on Monday 2026-08-31 (ISO), which is what the cue trend
 * has to group on.
 */
class ProgressStatsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Athens")

    private fun at(date: String, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun startOf(date: String): Long = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun attempt(
        date: String,
        module: ModuleId = ModuleId.WORDCOACH,
        outcome: Outcome = Outcome.CORRECT,
        itemId: String = "i1",
        cue: Int? = null,
        hour: Int = 9,
    ) = Attempt(itemId = itemId, module = module, startedAt = at(date, hour), durationMs = 1000, outcome = outcome, cueLevel = cue)

    private fun session(date: String, startHour: Int, minutes: Long?) = Session(
        startedAt = at(date, startHour),
        endedAt = minutes?.let { at(date, startHour) + it * 60_000 },
        plannedModules = "WORDCOACH",
        plannedItemCount = 4,
    )

    private val items = mapOf(
        "i1" to Item(id = "i1", text = "καφές", firstSound = "κ"),
        "i2" to Item(id = "i2", text = "ψωμί", firstSound = "ψ"),
        "i3" to Item(id = "i3", text = "νερό", firstSound = "ν"),
    )

    private fun compute(
        attempts: List<Attempt> = emptyList(),
        sessions: List<Session> = emptyList(),
        schedules: List<Schedule> = emptyList(),
        from: String = "2026-08-31",
        to: String = "2026-09-05",
    ) = ProgressStats.compute(
        attempts, sessions, schedules, items,
        from = startOf(from), to = at(to, 23, 59), zone = zone,
    )

    @Test fun `every day of the window gets a row, busy or not`() {
        val p = compute(attempts = listOf(attempt("2026-09-01")))
        assertEquals(6, p.days.size)
        assertEquals(startOf("2026-08-31"), p.days.first().day)
        assertEquals(startOf("2026-09-05"), p.days.last().day)
        assertEquals(0, p.days.first().attempts)
        assertEquals(1, p.days[1].attempts)
    }

    @Test fun `minutes come from session durations and add up per day`() {
        val p = compute(sessions = listOf(session("2026-09-01", 9, 10), session("2026-09-01", 18, 5)))
        assertEquals(15, p.days[1].minutes)
    }

    /** He put the phone down mid-session. Nothing ended, so nothing is claimed. */
    @Test fun `an unfinished session is zero minutes`() {
        val p = compute(sessions = listOf(session("2026-09-02", 9, null)))
        assertEquals(0, p.days[2].minutes)
    }

    /** The phone stayed on the table all afternoon. One sitting is never more than an hour. */
    @Test fun `a session is capped at sixty minutes`() {
        val p = compute(sessions = listOf(session("2026-09-02", 9, 300)))
        assertEquals(60, p.days[2].minutes)
    }

    @Test fun `streak counts back from today`() {
        val p = compute(attempts = listOf(attempt("2026-09-05"), attempt("2026-09-04"), attempt("2026-09-03")))
        assertEquals(3, p.streakDays)
    }

    /** It is early and he has not started yet. Yesterday's work still counts as a live streak. */
    @Test fun `a streak that ended yesterday is still a streak`() {
        val p = compute(attempts = listOf(attempt("2026-09-04"), attempt("2026-09-03")))
        assertEquals(2, p.streakDays)
    }

    @Test fun `a missed day breaks the streak`() {
        val p = compute(attempts = listOf(attempt("2026-09-05"), attempt("2026-09-03"), attempt("2026-09-02")))
        assertEquals(1, p.streakDays)
    }

    @Test fun `two quiet days leave no streak at all`() {
        val p = compute(attempts = listOf(attempt("2026-09-02")))
        assertEquals(0, p.streakDays)
    }

    @Test fun `module stats split the outcomes and divide correct by all of them`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-01", outcome = Outcome.CORRECT),
                attempt("2026-09-01", outcome = Outcome.CORRECT),
                attempt("2026-09-01", outcome = Outcome.ASSISTED),
                attempt("2026-09-01", outcome = Outcome.SKIPPED),
                attempt("2026-09-01", module = ModuleId.NUMBERS, itemId = "numbers:level:2"),
            )
        )
        val words = p.modules.single { it.module == ModuleId.WORDCOACH }
        assertEquals(4, words.attempts)
        assertEquals(2, words.correct)
        assertEquals(1, words.assisted)
        assertEquals(1, words.skipped)
        assertEquals(0.5f, words.accuracy, 0.001f)
        // A synthetic id is still an exercise he did: it counts in its module, it just has no word.
        assertEquals(1, p.modules.single { it.module == ModuleId.NUMBERS }.attempts)
    }

    @Test fun `a module he never opened has no row`() {
        val p = compute(attempts = listOf(attempt("2026-09-01")))
        assertTrue(p.modules.none { it.module == ModuleId.TRACE })
    }

    @Test fun `the cue trend averages the three ladder modules by ISO week`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-01", cue = 4),
                attempt("2026-09-03", module = ModuleId.SCRIPTS, cue = 2),
                attempt("2026-09-08", module = ModuleId.SINGSAY, cue = 1),
                attempt("2026-09-09", cue = 0),
            ),
            to = "2026-09-12",
        )
        assertEquals(2, p.cueTrend.size)
        assertEquals(startOf("2026-08-31"), p.cueTrend[0].weekStart)
        assertEquals(3f, p.cueTrend[0].meanCue, 0.001f)
        assertEquals(startOf("2026-09-07"), p.cueTrend[1].weekStart)
        assertEquals(0.5f, p.cueTrend[1].meanCue, 0.001f)
    }

    @Test fun `modules without a ladder stay out of the cue trend`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-01", module = ModuleId.NUMBERS, cue = 4),
                attempt("2026-09-01", cue = null),
                attempt("2026-09-01", cue = 1),
            )
        )
        assertEquals(1, p.cueTrend.size)
        assertEquals(1f, p.cueTrend[0].meanCue, 0.001f)
    }

    /** One word learned is one word, even when it is learned in three modules. */
    @Test fun `mastered counts a word once`() {
        val p = compute(
            schedules = listOf(
                Schedule(itemId = "i1", module = ModuleId.WORDCOACH, box = 5, nextDueAt = 0),
                Schedule(itemId = "i1", module = ModuleId.SINGSAY, box = 5, nextDueAt = 0),
                Schedule(itemId = "i2", module = ModuleId.WORDCOACH, box = 4, nextDueAt = 0),
            )
        )
        assertEquals(1, p.mastered)
    }

    @Test fun `most skipped is by word, commonest first, and never a synthetic id`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-01", outcome = Outcome.SKIPPED, itemId = "i1"),
                attempt("2026-09-02", outcome = Outcome.SKIPPED, itemId = "i1"),
                attempt("2026-09-02", outcome = Outcome.SKIPPED, itemId = "i2"),
                attempt("2026-09-02", outcome = Outcome.SKIPPED, itemId = "trace:level:3", module = ModuleId.TRACE),
                attempt("2026-09-02", outcome = Outcome.SKIPPED, itemId = "arcade:tap", module = ModuleId.ARCADE),
            )
        )
        assertEquals(listOf("καφές" to 2, "ψωμί" to 1), p.mostSkipped)
    }

    @Test fun `the talk board list is by word too`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-01", module = ModuleId.TALKBOARD, itemId = "i3"),
                attempt("2026-09-01", module = ModuleId.TALKBOARD, itemId = "i3"),
                attempt("2026-09-01", module = ModuleId.TALKBOARD, itemId = "i1"),
                attempt("2026-09-01", itemId = "i2"),
            )
        )
        assertEquals(listOf("νερό" to 2, "καφές" to 1), p.mostUsedTalk)
    }

    /** The caller may hand over a wider list than the window — the insights need one. */
    @Test fun `attempts outside the window are left out`() {
        val p = compute(attempts = listOf(attempt("2026-08-20"), attempt("2026-09-01")))
        assertEquals(1, p.modules.single().attempts)
        assertEquals(1, p.days.sumOf { it.attempts })
    }

    @Test fun `the window start is the start of the day, days back`() {
        val now = at("2026-09-05", 21, 30)
        assertEquals(startOf("2026-08-30"), ProgressStats.from(now, days = 7, zone = zone))
    }
}
