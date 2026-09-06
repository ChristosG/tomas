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
        minute: Int = 0,
        sessionId: String? = null,
    ) = Attempt(
        itemId = itemId, module = module, sessionId = sessionId, startedAt = at(date, hour, minute),
        durationMs = 1000, outcome = outcome, cueLevel = cue,
    )

    private fun session(date: String, startHour: Int, minutes: Long?, id: String = "s") = Session(
        id = id,
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

    /**
     * Free practice writes no session row at all — a module opened from the Today grid, and every
     * tap on the talk board. Those are real minutes of his life, so they are reconstructed from the
     * attempts: anything closer together than five minutes is one sitting, and a sitting lasts as
     * long as it spans. Twenty minutes of word coach used to read as «0 λεπτά».
     */
    @Test fun `free practice is counted as a sitting as long as it spans`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-02", hour = 10, minute = 0),
                attempt("2026-09-02", hour = 10, minute = 2),
                attempt("2026-09-02", hour = 10, minute = 4),
            )
        )
        assertEquals(4, p.days[2].minutes)
    }

    /** Standing at the talk board to say one word is a minute of his day, not nothing. */
    @Test fun `a lone tap is worth one minute`() {
        val p = compute(attempts = listOf(attempt("2026-09-02", module = ModuleId.TALKBOARD, itemId = "i3")))
        assertEquals(1, p.days[2].minutes)
    }

    /** More than five minutes apart is two sittings, not one long one with a pause in it. */
    @Test fun `a long gap starts a second sitting`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-02", hour = 10, minute = 0),
                attempt("2026-09-02", hour = 10, minute = 3),
                attempt("2026-09-02", hour = 17, minute = 0),
                attempt("2026-09-02", hour = 17, minute = 4),
            )
        )
        // Three minutes in the morning, four in the evening — not seven hours in between.
        assertEquals(7, p.days[2].minutes)
    }

    /** An attempt that belongs to a sitting the app already timed must not be counted twice. */
    @Test fun `attempts inside a session add no minutes of their own`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-02", hour = 9, minute = 1, sessionId = "s"),
                attempt("2026-09-02", hour = 9, minute = 5, sessionId = "s"),
            ),
            sessions = listOf(session("2026-09-02", 9, 10)),
        )
        assertEquals(10, p.days[2].minutes)
    }

    /**
     * `schedules.itemId` is not one namespace: script practice records against the *script's* id,
     * not an item's. A mastered dialogue is not a mastered word, and «Μαθημένες λέξεις» says word.
     */
    @Test fun `a mastered dialogue is not a mastered word`() {
        val p = compute(
            schedules = listOf(
                Schedule(itemId = "i1", module = ModuleId.WORDCOACH, box = 5, nextDueAt = 0),
                Schedule(itemId = "script-7", module = ModuleId.SCRIPTS, box = 5, nextDueAt = 0),
            )
        )
        assertEquals(1, p.mastered)
    }

    /**
     * «Δύσκολες λέξεις» carries the advice «Δοκίμασε φωτογραφία ή φωνή», which is about finding a
     * word. «Γράψε» at levels 4–5 writes real item ids, so a word he skipped *tracing* would
     * otherwise arrive there as a word he cannot find.
     */
    @Test fun `a word skipped while writing is not a word he cannot find`() {
        val p = compute(
            attempts = listOf(
                attempt("2026-09-01", outcome = Outcome.SKIPPED, itemId = "i1"),
                attempt("2026-09-01", outcome = Outcome.SKIPPED, itemId = "i2", module = ModuleId.TRACE),
            )
        )
        assertEquals(listOf("καφές" to 1), p.mostSkipped)
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

    /**
     * A sitting that runs past midnight belongs, whole, to the day he sat down. Splitting it across
     * two days would give him two half-days on the chart for one evening's work, and a streak he
     * did not earn.
     */
    @Test fun `a session that runs past midnight counts on the day he sat down`() {
        val start = at("2026-09-02", 23, 40)
        val session = Session(
            startedAt = start, endedAt = start + 40 * 60_000,          // 20 minutes into the 3rd
            plannedModules = "WORDCOACH", plannedItemCount = 4,
        )
        val p = compute(sessions = listOf(session))

        assertEquals(40, p.days[2].minutes)                             // 2026-09-02
        assertEquals(0, p.days[3].minutes)                              // 2026-09-03
    }

    /**
     * The Sunday Greece goes onto winter time has 25 hours. Day arithmetic that added 86 400 000 ms
     * would land inside that Sunday twice and drop a day off the end of the window; going through
     * `LocalDate` cannot. Same test for the 23-hour spring day, which is the one that loses a day.
     */
    @Test fun `a clock change neither adds nor loses a day`() {
        val autumn = ProgressStats.compute(
            attempts = emptyList(), sessions = emptyList(), schedules = emptyList(), items = items,
            from = LocalDate.parse("2026-10-24").atStartOfDay(zone).toInstant().toEpochMilli(),
            to = LocalDateTime.of(LocalDate.parse("2026-10-27"), java.time.LocalTime.of(23, 0)).atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        assertEquals(4, autumn.days.size)
        assertEquals(
            listOf("2026-10-24", "2026-10-25", "2026-10-26", "2026-10-27").map { startOf(it) },
            autumn.days.map { it.day },
        )

        val spring = ProgressStats.compute(
            attempts = emptyList(), sessions = emptyList(), schedules = emptyList(), items = items,
            from = LocalDate.parse("2026-03-28").atStartOfDay(zone).toInstant().toEpochMilli(),
            to = LocalDateTime.of(LocalDate.parse("2026-03-30"), java.time.LocalTime.of(23, 0)).atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        assertEquals(3, spring.days.size)
        assertEquals(
            listOf("2026-03-28", "2026-03-29", "2026-03-30").map { startOf(it) },
            spring.days.map { it.day },
        )
    }

    /**
     * The zone is an argument, not the machine's. A caregiver reading the dashboard in Athens and a
     * developer running the tests in Reykjavík must see the same attempt on the same day, and an
     * attempt at 00:30 Athens time is the previous day in UTC — so the two zones must disagree here,
     * which is exactly what proves the parameter is really being used.
     */
    @Test fun `the zone argument decides the day, not the machine`() {
        val justAfterMidnightInAthens = at("2026-09-03", 0, 30)
        val rows = listOf(
            Attempt(itemId = "i1", module = ModuleId.WORDCOACH, startedAt = justAfterMidnightInAthens,
                durationMs = 1000, outcome = Outcome.CORRECT),
        )
        val window = startOf("2026-08-31") to at("2026-09-05", 23, 59)

        val athens = ProgressStats.compute(rows, emptyList(), emptyList(), items, window.first, window.second, zone)
        val utc = ProgressStats.compute(rows, emptyList(), emptyList(), items, window.first, window.second, ZoneId.of("UTC"))

        assertEquals(1, athens.days.single { it.day == startOf("2026-09-03") }.attempts)
        assertEquals(0, utc.days.count { it.attempts > 0 && it.day == startOf("2026-09-03") })
        assertEquals(1, utc.days.sumOf { it.attempts })                 // still counted, on 2 September
    }

    /** The dashboard hands in the count SQLite made; the schedules it was made from are not read. */
    @Test fun `a counted mastered total wins over the schedules`() {
        val p = ProgressStats.compute(
            attempts = emptyList(), sessions = emptyList(), schedules = emptyList(), items = items,
            from = startOf("2026-08-31"), to = at("2026-09-05", 23, 59), zone = zone, mastered = 7,
        )
        assertEquals(7, p.mastered)
    }

    // ---- his whole journey, for the report ---------------------------------------------------

    /**
     * The lifetime pass has no window at all — a word he was stuck on in March is exactly what four
     * weeks of totals cannot show, and it is the reason phase 11 reads the whole table.
     */
    @Test fun `a word's lifetime spans everything, not the window`() {
        val rows = listOf(
            attempt("2026-03-02", cue = 4),
            attempt("2026-07-14", outcome = Outcome.ASSISTED, cue = 2),
            attempt("2026-09-05", cue = 0),
        )
        val schedules = listOf(
            Schedule(itemId = "i1", module = ModuleId.WORDCOACH, box = 3, nextDueAt = 0),
            Schedule(itemId = "i1", module = ModuleId.SINGSAY, box = 5, nextDueAt = 0),
        )

        val h = ProgressStats.lifetime(rows, schedules, items).single()

        assertEquals("καφές", h.text)
        assertEquals(3, h.attempts)
        assertEquals(2, h.correct)
        assertEquals(1, h.assisted)
        assertEquals(2f, h.meanCue!!, 0.001f)
        assertEquals("the highest box it has reached in any module", 5, h.box)
        assertEquals(at("2026-03-02"), h.firstAt)
        assertEquals(at("2026-09-05"), h.lastAt)
    }

    /** Busiest first, then the most recently practised, then alphabetical: two reports must diff. */
    @Test fun `the lifetime list is ordered so two reports can be compared`() {
        val rows = listOf(
            attempt("2026-09-01", itemId = "i1"), attempt("2026-09-02", itemId = "i1"),
            attempt("2026-09-05", itemId = "i2"),
            attempt("2026-08-01", itemId = "i3"),
        )
        assertEquals(
            listOf("καφές", "ψωμί", "νερό"),
            ProgressStats.lifetime(rows, emptyList(), items).map { it.text },
        )
    }

    /** «Πόσο» counts every module; «πόσο καλά» counts only the ones that mark him. */
    @Test fun `the board is counted in a word's attempts but never in its score`() {
        val rows = listOf(
            attempt("2026-09-01", module = ModuleId.TALKBOARD),
            attempt("2026-09-01", module = ModuleId.TALKBOARD),
            attempt("2026-09-02", module = ModuleId.WORDCOACH, cue = 1),
        )
        val h = ProgressStats.lifetime(rows, emptyList(), items).single()

        assertEquals(3, h.attempts)
        assertEquals("only the graded try", 1, h.correct)
        assertEquals("the board has no cue ladder", 1f, h.meanCue!!, 0.001f)
    }

    /** A word a caregiver deleted drops out rather than arriving as a bare id. */
    @Test fun `an attempt against a word that no longer exists is not a line`() {
        val rows = listOf(attempt("2026-09-01", itemId = "gone"), attempt("2026-09-01", itemId = "i1"))
        assertEquals(listOf("καφές"), ProgressStats.lifetime(rows, emptyList(), items).map { it.text })
    }

    @Test fun `a photograph and a voice are booleans, from the item and from any recording`() {
        val withPhoto = mapOf("i1" to Item(id = "i1", text = "καφές", imagePath = "photos/x.jpg", modelRecordingId = "r1"))
        val plain = ProgressStats.lifetime(listOf(attempt("2026-09-01")), emptyList(), items).single()
        val rich = ProgressStats.lifetime(listOf(attempt("2026-09-01")), emptyList(), withPhoto).single()
        val voiced = ProgressStats.lifetime(listOf(attempt("2026-09-01")), emptyList(), items, setOf("i1")).single()

        assertTrue(rich.hasPhoto && rich.hasVoice)
        assertTrue(!plain.hasPhoto && !plain.hasVoice)
        assertTrue("a caregiver's recording is a voice too", voiced.hasVoice)
    }

    // ---- the month, day by day ----------------------------------------------------------------

    @Test fun `the days are walked forwards and each one's busiest word comes first`() {
        val rows = listOf(
            attempt("2026-09-04", itemId = "i2", cue = 3, outcome = Outcome.SKIPPED),
            attempt("2026-09-03", itemId = "i1", cue = 1),
            attempt("2026-09-03", itemId = "i1", cue = 3),
            attempt("2026-09-03", itemId = "i3"),
        )
        val out = ProgressStats.recentByDay(rows, items, startOf("2026-09-01"), at("2026-09-05", 23, 59), zone)

        assertEquals(
            listOf(startOf("2026-09-03") to "καφές", startOf("2026-09-03") to "νερό", startOf("2026-09-04") to "ψωμί"),
            out.map { it.day to it.text },
        )
        assertEquals(2, out.first().attempts)
        assertEquals(2f, out.first().meanCue!!, 0.001f)
        assertEquals(1, out.last().skipped)
        assertTrue("a quiet day is simply absent", out.none { it.day == startOf("2026-09-01") })
    }

    @Test fun `a day outside the window is not in the detail`() {
        val rows = listOf(attempt("2026-08-01"), attempt("2026-09-03"))
        val out = ProgressStats.recentByDay(rows, items, startOf("2026-09-01"), at("2026-09-05", 23, 59), zone)
        assertEquals(listOf(startOf("2026-09-03")), out.map { it.day })
    }
}
