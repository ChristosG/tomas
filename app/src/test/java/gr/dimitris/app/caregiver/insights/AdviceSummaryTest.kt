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
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

/**
 * The summary is the only thing in this app that ever leaves the phone, so these tests are as much
 * about what it must never contain as about what it says.
 *
 * One fixed zone and fixed dates: a caregiver reading the text next to the dashboard must see the
 * same period on both, and "it depends on when you run it" is not an answer for either.
 */
class AdviceSummaryTest {
    private val zone: ZoneId = ZoneId.of("Europe/Athens")

    private fun at(date: String, hour: Int = 9): Long =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private val levels = linkedMapOf("Αριθμοί (1–7)" to 3, "Προτάσεις (1–4)" to 2, "Γράψε (1–5)" to 1)

    /**
     * Items that carry exactly the things the summary must not leak: a photo on disk and a model
     * recording. Neither is a field of [Progress] at all, which is the point — the test pins the
     * shape of the pipeline, not a filter somewhere inside it.
     */
    private val items = mapOf(
        "i1" to Item(id = "i1", text = "καφές", firstSound = "κ", imagePath = "media://photos/kafes.jpg", modelRecordingId = "r1"),
        "i2" to Item(id = "i2", text = "ψωμί", firstSound = "ψ", imagePath = "/photos/psomi.jpg"),
        "i3" to Item(id = "i3", text = "νερό", firstSound = "ν"),
    )

    private fun attempt(date: String, module: ModuleId, outcome: Outcome, itemId: String, cue: Int? = null) =
        Attempt(itemId = itemId, module = module, startedAt = at(date), durationMs = 1_000, outcome = outcome, cueLevel = cue)

    /** Three days of real work, computed the way the dashboard computes it. */
    private fun progress(): Progress {
        val attempts = buildList {
            repeat(2) { add(attempt("2026-09-03", ModuleId.WORDCOACH, Outcome.CORRECT, "i1", cue = 1)) }
            add(attempt("2026-09-04", ModuleId.WORDCOACH, Outcome.SKIPPED, "i2", cue = 3))
            add(attempt("2026-09-04", ModuleId.NUMBERS, Outcome.CORRECT, "i3"))
            add(attempt("2026-09-05", ModuleId.TALKBOARD, Outcome.CORRECT, "i3"))
            add(attempt("2026-09-05", ModuleId.TALKBOARD, Outcome.CORRECT, "i3"))
        }
        val sessions = listOf(
            Session(startedAt = at("2026-09-05", 9), endedAt = at("2026-09-05", 9) + 12 * 60_000,
                plannedModules = "WORDCOACH", plannedItemCount = 4),
        )
        val schedules = listOf(
            Schedule(itemId = "i1", module = ModuleId.WORDCOACH, box = 5, nextDueAt = at("2026-09-06")),
        )
        return ProgressStats.compute(
            attempts, sessions, schedules, items,
            from = LocalDate.parse("2026-09-03").atStartOfDay(zone).toInstant().toEpochMilli(),
            to = at("2026-09-05", 23),
            zone = zone,
        )
    }

    @Test fun `the summary carries the counts a caregiver would quote`() {
        val text = AdviceSummary.build(progress(), listOf("Σερί 3 ημερών. Συνέχισε έτσι!"), levels, zone = zone)

        assertTrue(text, text.contains("Περίοδος: 3/9/2026 – 5/9/2026 (3 μέρες)"))
        assertTrue(text, text.contains("Σύνολο: 12 λεπτά, 6 ασκήσεις"))
        assertTrue(text, text.contains("Σερί: 3 μέρες"))
        assertTrue(text, text.contains("Μαθημένες λέξεις: 1"))
        assertTrue(text, text.contains("- Λέξεις: 3 ασκήσεις, 67% σωστά (σωστά 2, με βοήθεια 0, προσπέρασε 1)"))
        assertTrue(text, text.contains("- Μίλα: 2 ασκήσεις, 100% σωστά"))
        assertTrue(text, text.contains("- ψωμί: 1 φορά"))
        assertTrue(text, text.contains("- νερό: 2 φορές"))
        assertTrue(text, text.contains("- Αριθμοί (1–7): 3"))
        assertTrue(text, text.contains("- Σερί 3 ημερών. Συνέχισε έτσι!"))
    }

    @Test fun `the summary never carries a photo, a recording or a path`() {
        val text = AdviceSummary.build(progress(), listOf("Σερί 3 ημερών. Συνέχισε έτσι!"), levels, zone = zone)

        assertFalse(text, text.contains("/photos/"))
        assertFalse(text, text.contains(".m4a"))
        assertFalse(text, text.contains("media://"))
        assertFalse(text, text.contains(".jpg"))
        // Nor the row ids that would let a leaked summary be joined back to the database.
        assertFalse(text, text.contains("i1"))
        assertFalse(text, text.contains("r1"))
    }

    @Test fun `the summary keeps its Greek headings`() {
        val text = AdviceSummary.build(progress(), emptyList(), levels, zone = zone)

        listOf(
            "Πρόοδος — Δημήτρης",
            "Περίοδος:",
            "Ανά άσκηση",
            "Πόση βοήθεια ανά εβδομάδα",
            "Δύσκολες λέξεις",
            "Στον πίνακα λέει πιο συχνά",
            "Επίπεδα",
            "Τι βλέπω",
        ).forEach { heading -> assertTrue(heading, text.contains(heading)) }
    }

    @Test fun `an empty month says so instead of showing zeros`() {
        val empty = Progress(
            from = at("2026-09-01"), to = at("2026-09-05"),
            days = listOf(DayStat(at("2026-09-01"), 0, 0)),
            streakDays = 0, modules = emptyList(), cueTrend = emptyList(),
            mastered = 0, mostSkipped = emptyList(), mostUsedTalk = emptyList(),
        )
        val text = AdviceSummary.build(empty, emptyList(), levels, zone = zone)

        // Five sections have nothing to show; the levels always do, so they are the sixth and say a number.
        assertEquals(5, text.split("Τίποτα ακόμα.").size - 1)
        assertTrue(text, text.contains("- Αριθμοί (1–7): 3"))
        assertTrue(text, text.contains("Χωρίς σερί αυτή τη στιγμή."))
    }

    /**
     * A caregiver may type a paragraph into a word. Ten of those in each list, plus everything else,
     * must still fit in one request — and the text must still be readable, one item per line.
     */
    @Test fun `a huge input is cut to the ceiling and stays one item per line`() {
        val long = "λ".repeat(4_000) + "\nκαι\nάλλα"
        val huge = Progress(
            from = at("2026-08-09"), to = at("2026-09-05"),
            days = (0 until 28).map { DayStat(at("2026-09-05") + it, 30, 40) },
            streakDays = 28,
            modules = ModuleId.entries.map { ModuleStat(it, 40, 30, 5, 5) },
            cueTrend = (0 until 5).map { WeekCue(at("2026-08-09") + it, 2.5f) },
            mastered = 99,
            mostSkipped = (1..10).map { "$long $it" to it },
            mostUsedTalk = (1..10).map { "$long $it" to it },
        )
        val text = AdviceSummary.build(huge, List(6) { long }, levels, zone = zone)

        assertTrue("${text.length}", text.length <= AdviceSummary.MAX_CHARS)
        assertTrue(text, text.endsWith("… (η περίληψη κόπηκε)"))
        // Each word is one line: the newlines a caregiver typed cannot break the headings apart.
        assertTrue(text, text.contains("- ${"λ".repeat(AdviceSummary.MAX_WORD)}: 1 φορά"))
    }
}
