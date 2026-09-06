package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.caregiver.progress.DayItemStat
import gr.dimitris.app.caregiver.progress.DayStat
import gr.dimitris.app.caregiver.progress.ItemHistory
import gr.dimitris.app.caregiver.progress.ProgressStats
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Note
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import gr.dimitris.app.core.data.Advice as AdviceRow

/**
 * The report is the only thing in this app that ever leaves the phone, so these tests are as much
 * about what it must never contain as about what it says. One fixed zone and fixed dates: "it
 * depends on when you run it" is not an answer for a text a caregiver forwards to a therapist.
 */
class JourneyReportTest {
    private val zone: ZoneId = ZoneId.of("Europe/Athens")

    private fun at(date: String, hour: Int = 9): Long =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private val levels = AdviceSummary.levels(numbers = 3, sentences = 2, trace = 1)

    /**
     * Items carrying exactly the things the report must not leak: a photo on disk, a content hash
     * that never resolved, a model recording, and ids. None of them is a field of [ItemHistory] at
     * all — which is the point: the test pins the shape of the pipeline, not a filter inside it.
     */
    private val items = mapOf(
        "item-id-1111" to Item(id = "item-id-1111", text = "καφές", kind = ItemKind.WORD, category = Category.FOOD,
            firstSound = "κ", imagePath = "media://8f3a1c/photo.jpg", modelRecordingId = "rec-id-2222"),
        "item-id-3333" to Item(id = "item-id-3333", text = "ψωμί", kind = ItemKind.WORD, category = Category.FOOD,
            firstSound = "ψ", imagePath = "/data/user/0/gr.dimitris.app/files/photos/psomi.jpg"),
        "item-id-4444" to Item(id = "item-id-4444", text = "νερό", kind = ItemKind.PHRASE, category = Category.QUICK,
            firstSound = "ν"),
    )

    private fun attempt(date: String, module: ModuleId, outcome: Outcome, itemId: String, cue: Int? = null) =
        Attempt(itemId = itemId, module = module, startedAt = at(date), durationMs = 1_000, outcome = outcome, cueLevel = cue)

    private val attempts = buildList {
        repeat(3) { add(attempt("2026-08-03", ModuleId.WORDCOACH, Outcome.CORRECT, "item-id-1111", cue = 1)) }
        add(attempt("2026-09-04", ModuleId.WORDCOACH, Outcome.SKIPPED, "item-id-3333", cue = 3))
        add(attempt("2026-09-04", ModuleId.WORDCOACH, Outcome.ASSISTED, "item-id-1111", cue = 2))
        add(attempt("2026-09-05", ModuleId.TALKBOARD, Outcome.CORRECT, "item-id-4444"))
        add(attempt("2026-09-05", ModuleId.TALKBOARD, Outcome.CORRECT, "item-id-4444"))
    }

    private val schedules = listOf(
        Schedule(itemId = "item-id-1111", module = ModuleId.WORDCOACH, box = 4, nextDueAt = at("2026-09-06")),
        Schedule(itemId = "item-id-1111", module = ModuleId.SINGSAY, box = 2, nextDueAt = at("2026-09-06")),
    )

    private val notes = listOf(
        Note(at = at("2026-09-01"), text = "Κουρασμένος όλη την εβδομάδα, οδοντίατρος.", author = "CAREGIVER"),
        Note(at = at("2026-09-05"), text = "Είπε «καλημέρα» μόνος του στη γειτόνισσα.", author = "DIMITRIS"),
    )

    private val previous = listOf(
        AdviceRow(at = at("2026-08-29"), model = "claude-opus-5", report = "παλιά αναφορά",
            caregivers = "- Δούλεψε τα ψώνια.", dimitris = "Πάει καλά.",
            focusJson = """{"items":["καφές"],"sounds":["κ"]}"""),
    )

    private fun report(
        lifetime: List<ItemHistory> = ProgressStats.lifetime(attempts, schedules, items, setOf("item-id-4444")),
        recent: List<DayItemStat> = ProgressStats.recentByDay(attempts, items, at("2026-08-09"), at("2026-09-05", 23), zone),
        days: List<DayStat> = listOf(
            DayStat(at("2026-09-04", 0), minutes = 12, attempts = 2),
            DayStat(at("2026-09-05", 0), minutes = 3, attempts = 2),
        ),
        insights: List<String> = listOf("Σερί 2 ημερών. Συνέχισε έτσι!"),
    ): String = JourneyReport.build(
        profile = JourneyReport.PROFILE,
        notes = notes,
        lifetime = lifetime,
        recent = recent,
        days = days,
        previous = previous,
        levels = levels,
        insights = insights,
        zone = zone,
    )

    @Test fun `the seven headings are all there, in the order the brief fixes`() {
        val text = report()
        val order = listOf(
            JourneyReport.PROFILE_HEADING,
            JourneyReport.NOTES_HEADING,
            JourneyReport.LIFETIME_HEADING,
            JourneyReport.RECENT_HEADING,
            JourneyReport.PREVIOUS_HEADING,
            JourneyReport.LEVELS_HEADING,
            JourneyReport.INSIGHTS_HEADING,
        )
        val positions = order.map { heading ->
            val i = text.indexOf("\n$heading\n").takeIf { it >= 0 } ?: text.indexOf("$heading\n")
            assertTrue("«$heading» is missing", i >= 0)
            i
        }
        assertEquals("the headings are out of order: $positions", positions.sorted(), positions)
    }

    @Test fun `the profile says who he is`() {
        val text = report()
        assertTrue(text, text.contains("αφασία Broca"))
        assertTrue("the board must be explained, or its CORRECTs read as a score",
            text.contains("πίνακας επικοινωνίας"))
    }

    @Test fun `the caregivers' notes travel, newest first, with who wrote them`() {
        val text = report()
        val newest = text.indexOf("Είπε «καλημέρα»")
        val older = text.indexOf("οδοντίατρος")
        assertTrue("both notes should be there", newest >= 0 && older >= 0)
        assertTrue("newest first", newest < older)
        assertTrue(text, text.contains("5/9/2026 (Δημήτρης)"))
        assertTrue(text, text.contains("1/9/2026 (φροντιστής)"))
    }

    @Test fun `a word's whole life is one line a caregiver could read out`() {
        val text = report()
        val line = text.lines().first { it.startsWith("- καφές ") }

        assertTrue(line, line.contains("λέξη"))
        assertTrue(line, line.contains(Category.FOOD.greek))
        assertTrue(line, line.contains("ασκήσεις 4"))
        assertTrue(line, line.contains("σωστά 3/με βοήθεια 1/παράλειψη 0"))
        assertTrue("mean cue of 1,1,1,2 is 1,3 — with a Greek comma", line.contains("μέση βοήθεια 1,3"))
        assertTrue("the highest box across modules", line.contains("κουτί 4"))
        assertTrue(line, line.contains("3/8/2026–4/9/2026"))
        assertTrue(line, line.contains("φωτογραφία ναι"))
        assertTrue(line, line.contains("φωνή ναι"))
    }

    /** «Πόσο» counts every module; «πόσο καλά» counts only the ones that mark him. */
    @Test fun `talk board taps are counted but never scored`() {
        val line = report().lines().first { it.startsWith("- νερό ") }

        assertTrue(line, line.contains("ασκήσεις 2"))
        assertTrue("every tap on the board is written CORRECT; it is not a score",
            line.contains("σωστά 0/με βοήθεια 0/παράλειψη 0"))
        assertTrue("no cue ladder on the board", line.contains("μέση βοήθεια —"))
        assertTrue("a word with neither picture nor voice says so", line.contains("φωτογραφία όχι"))
    }

    @Test fun `the days carry their minutes and the words of that day`() {
        val text = report()
        assertTrue(text, text.contains("- 4/9/2026: 12 λεπτά, 2 ασκήσεις"))
        assertTrue(text, text.contains("  - ψωμί: 1 (0/0/1), βοήθεια 3,0"))
        assertTrue(text, text.contains("  - νερό: 2 (0/0/0)"))
    }

    @Test fun `the previous advice comes back with its focus, so it can be compared with`() {
        val text = report()
        assertTrue(text, text.contains("- 29/8/2026"))
        assertTrue(text, text.contains("- Δούλεψε τα ψώνια."))
        assertTrue(text, text.contains("""Εστίαση: {"items":["καφές"],"sounds":["κ"]}"""))
    }

    @Test fun `the levels and what the app itself sees are both said`() {
        val text = report()
        assertTrue(text, text.contains("Αριθμοί (1–7): 3"))
        assertTrue(text, text.contains("Σερί 2 ημερών."))
    }

    @Test fun `every empty section says so rather than being missing`() {
        val text = JourneyReport.build(
            profile = JourneyReport.PROFILE, notes = emptyList(), lifetime = emptyList(), recent = emptyList(),
            days = emptyList(), previous = emptyList(), levels = emptyMap(), insights = emptyList(), zone = zone,
        )
        assertTrue(text, text.contains(JourneyReport.NOTES_HEADING))
        assertTrue(text, text.contains(JourneyReport.PREVIOUS_HEADING))
        assertEquals("- Τίποτα ακόμα.", 6, text.split("- Τίποτα ακόμα.").size - 1)
    }

    // ---- what must never be in it ---------------------------------------------------------

    @Test fun `no paths, no ids, no media hashes, ever`() {
        val text = report()
        assertFalse(text, text.contains("media://"))
        assertFalse(text, text.contains("/data/user/"))
        assertFalse(text, text.contains(".jpg"))
        assertFalse(text, text.contains("item-id-"))
        assertFalse(text, text.contains("rec-id-"))
        assertFalse("a photograph is a ναι, never a name", text.contains("psomi"))
    }

    /** The one place free text could carry a path is a note, and a note is a caregiver's words. */
    @Test fun `a note is put on one line so it cannot forge a heading`() {
        val text = JourneyReport.build(
            profile = "Προφίλ.",
            notes = listOf(Note(at = at("2026-09-05"), text = "Πρώτη γραμμή\n${JourneyReport.LEVELS_HEADING}\nΨέμα", author = "CAREGIVER")),
            lifetime = emptyList(), recent = emptyList(), days = emptyList(), previous = emptyList(),
            levels = levels, insights = emptyList(), zone = zone,
        )
        assertEquals("one heading line, not two", 1, text.lines().count { it == JourneyReport.LEVELS_HEADING })
    }

    // ---- the ceilings ----------------------------------------------------------------------

    @Test fun `the word history is capped and says how much it left out`() {
        val many = (1..600).map { i ->
            ItemHistory("λέξη$i", ItemKind.WORD, Category.CUSTOM, "λ", attempts = 1000 - i, correct = 1, assisted = 0,
                skipped = 0, meanCue = 1f, box = 1, firstAt = at("2026-01-01"), lastAt = at("2026-09-01"),
                hasPhoto = false, hasVoice = false)
        }
        val text = report(lifetime = many)

        assertEquals(JourneyReport.MAX_LIFETIME_LINES, text.lines().count { it.startsWith("- λέξη") })
        assertTrue("the busiest words are the ones kept", text.contains("- λέξη1 ·"))
        assertFalse(text.contains("- λέξη600 ·"))
        assertTrue(text, text.contains("και άλλες 200 λέξεις"))
    }

    private val month = (1..28).map { d -> DayStat(at("2026-09-%02d".format(d), 0), minutes = 20, attempts = 400) }

    private fun crowdedDays(wordsPerDay: Int) = month.flatMap { day ->
        (1..wordsPerDay).map { i ->
            DayItemStat(day.day, "λέξη$i", attempts = 3, correct = 2, assisted = 1, skipped = 0, meanCue = 1.5f)
        }
    }

    private fun longHistory(n: Int) = (1..n).map { i ->
        ItemHistory("λέξη$i", ItemKind.WORD, Category.CUSTOM, "λ", 1000 - i, 1, 0, 0, 1f, 1,
            at("2026-01-01"), at("2026-09-01"), hasPhoto = false, hasVoice = false)
    }

    /**
     * The order the ceiling is paid for: the word history first, then the day-by-day detail from a
     * fortnight back. The recent days are what next week is planned from, so they are the last
     * thing given up — and every day keeps its total whatever happens.
     */
    @Test fun `over the ceiling, the history goes first and the last fortnight keeps its detail`() {
        val text = report(lifetime = longHistory(600), recent = crowdedDays(450), days = month)

        assertTrue("${text.length} characters", text.length <= JourneyReport.MAX_CHARS)
        assertTrue("the word history is given up first", text.lines().none { it.startsWith("- λέξη1 ·") })
        assertTrue("every day keeps its total", text.contains("- 1/9/2026: 20 λεπτά, 400 ασκήσεις"))
        assertTrue(text.contains("- 28/9/2026: 20 λεπτά, 400 ασκήσεις"))
        // The last fourteen days keep their words; the fortnight before them keeps only the total.
        assertTrue("the newest day keeps its detail", text.contains("- 28/9/2026: 20 λεπτά, 400 ασκήσεις\n  - λέξη"))
        assertFalse("the oldest does not", text.contains("- 1/9/2026: 20 λεπτά, 400 ασκήσεις\n  - λέξη"))
    }

    /** Past all of that, the totals still survive whole and nothing is cut mid-line. */
    @Test fun `an impossible month keeps every day's total and ends on a whole line`() {
        val text = report(lifetime = longHistory(600), recent = crowdedDays(2_000), days = month)

        assertTrue("${text.length} characters", text.length <= JourneyReport.MAX_CHARS)
        month.forEach { d ->
            assertTrue("a day lost its total", text.contains(": 20 λεπτά, 400 ασκήσεις"))
        }
        assertTrue(text.contains("- 1/9/2026: 20 λεπτά, 400 ασκήσεις"))
        assertTrue(text.contains("- 28/9/2026: 20 λεπτά, 400 ασκήσεις"))
        val ends = text.trimEnd().lines().last()
        assertTrue("the last line is whole: «$ends»", ends.startsWith("- ") || ends.startsWith("…"))
    }

    /** Under the ceiling nothing is shed at all, and no cut marker is invented. */
    @Test fun `an ordinary month is nowhere near the ceiling`() {
        val text = report()
        assertTrue("${text.length} characters", text.length < 20_000)
        assertFalse(text, text.contains("η αναφορά κόπηκε"))
    }
}
