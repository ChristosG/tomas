package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.caregiver.progress.Progress
import gr.dimitris.app.caregiver.progress.ProgressStats
import gr.dimitris.app.caregiver.progress.attemptsLine
import gr.dimitris.app.caregiver.progress.minutesLine
import gr.dimitris.app.caregiver.progress.streakLine
import gr.dimitris.app.caregiver.progress.timesLine
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.trace.TraceViewModel
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything the app is willing to say about Dimitris in words, and nothing else.
 *
 * This is the one text that leaves the phone — the caregiver sends it to Claude, or shares it with
 * a speech therapist — so what is *not* in it matters more than what is. No recording, no
 * photograph, no file path, no id, no free text a caregiver typed anywhere except the words
 * Dimitris practises, which are the whole point. It is readable before it is sent: the advice
 * screen shows it in full under «Τι θα σταλεί» for exactly that reason.
 *
 * Plain Greek with plain headings rather than JSON, because the two readers are a language model
 * and a human being and both of them read Greek.
 */
object AdviceSummary {
    /** A hard ceiling. Four weeks of a busy month is nowhere near it; a pathological item text is. */
    const val MAX_CHARS = 6_000

    /** How much of one word is worth sending. A "word" longer than this is a caregiver's paragraph. */
    const val MAX_WORD = 80

    /** How much of one insight line is worth sending. Four words' worth: they are one-liners. */
    const val MAX_LINE = MAX_WORD * 4

    private const val CUT = "\n… (η περίληψη κόπηκε)"
    private const val NOTHING = "Τίποτα ακόμα."

    /** Greek uses a comma for the decimal point, and the cue trend is the only decimal here. */
    private val greek: Locale = Locale.forLanguageTag("el")

    /**
     * The module names as the modules themselves say them, plus «Μίλα» for the talk board, which is
     * not a module. The screen passes its own copy of this map (built from `graph.modules`, so a
     * renamed module renames its line); the default exists so the summary is testable on its own.
     */
    val MODULE_NAMES: Map<ModuleId, String> = mapOf(
        ModuleId.WORDCOACH to "Λέξεις",
        ModuleId.NUMBERS to "Αριθμοί",
        ModuleId.SINGSAY to "Τραγούδα και πες το",
        ModuleId.SCRIPTS to "Διάλογοι",
        ModuleId.SENTENCES to "Προτάσεις",
        ModuleId.TRACE to "Γράψε",
        ModuleId.ARCADE to "Δεξί χέρι",
        ModuleId.TALKBOARD to "Μίλα",
    )

    fun build(
        p: Progress,
        insights: List<String>,
        levels: Map<String, Int>,
        names: Map<ModuleId, String> = MODULE_NAMES,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val text = buildString {
            appendLine("Πρόοδος — Δημήτρης")
            appendLine("Περίοδος: ${date(p.from, zone)} – ${date(p.to, zone)} (${dayCount(p.days.size)})")
            appendLine()
            appendLine("Σύνολο: ${minutesLine(p.days.sumOf { it.minutes })}, ${attemptsLine(p.days.sumOf { it.attempts })}")
            appendLine(streakLine(p.streakDays))
            // "Συνολικά", because it is: the count has no date filter, and three lines under
            // «Περίοδος: …» a bare number reads as "this month", to a caregiver and to Claude alike.
            appendLine("Μαθημένες λέξεις συνολικά (από την αρχή): ${p.mastered}")
            appendLine()

            appendLine("Ανά άσκηση")
            if (p.modules.isEmpty()) appendLine("- $NOTHING")
            p.modules.forEach { m ->
                val name = names[m.module] ?: m.module.name
                // The talk board has no accuracy to report: every tap on it is written as CORRECT
                // because it is him speaking, so «100% σωστά» there would be a count of taps.
                appendLine(
                    if (m.module in ProgressStats.GRADED_MODULES) {
                        "- $name: ${attemptsLine(m.attempts)}, ${(m.accuracy * 100).roundToInt()}% σωστά " +
                            "(σωστά ${m.correct}, με βοήθεια ${m.assisted}, προσπέρασε ${m.skipped})"
                    } else {
                        "- $name: ${attemptsLine(m.attempts)} (πίνακας επικοινωνίας, χωρίς σωστό και λάθος)"
                    }
                )
            }
            appendLine()

            appendLine("Πόση βοήθεια ανά εβδομάδα (0–4, λιγότερο = καλύτερα)")
            if (p.cueTrend.isEmpty()) appendLine("- $NOTHING")
            p.cueTrend.forEach { w ->
                appendLine("- εβδομάδα ${date(w.weekStart, zone)}: ${String.format(greek, "%.1f", w.meanCue)}")
            }
            appendLine()

            appendLine("Δύσκολες λέξεις (τις προσπερνά)")
            words(p.mostSkipped)
            appendLine()

            appendLine("Στον πίνακα λέει πιο συχνά")
            words(p.mostUsedTalk)
            appendLine()

            appendLine("Επίπεδα")
            if (levels.isEmpty()) appendLine("- $NOTHING")
            levels.forEach { (label, value) -> appendLine("- $label: $value") }
            appendLine()

            appendLine("Τι βλέπω")
            if (insights.isEmpty()) appendLine("- $NOTHING")
            insights.forEach { appendLine("- ${it.oneLine().take(MAX_LINE)}") }
        }
        return if (text.length <= MAX_CHARS) text else cut(text)
    }

    /**
     * The ceiling, honoured at a line boundary. Every section above the last one is bounded by
     * construction, so today the blind cut could only ever land in the final list — but "today" is
     * not a guarantee, and half a heading would tell the reader something false about what was sent.
     */
    private fun cut(text: String): String {
        val room = MAX_CHARS - CUT.length
        val lastLine = text.lastIndexOf('\n', room - 1)
        return text.take(if (lastLine > 0) lastLine else room) + CUT
    }

    /**
     * The three levels as the summary and the share text name them, with their ranges, built in one
     * place so the dashboard and the advice screen cannot drift apart on either the label or the
     * range. Ordered, because the reader reads them in this order on the screen too.
     */
    fun levels(numbers: Int, sentences: Int, trace: Int): Map<String, Int> = linkedMapOf(
        "Αριθμοί (${NumberProgression.MIN_LEVEL}–${NumberProgression.MAX_LEVEL})" to numbers,
        "Προτάσεις (${SentenceTemplates.MIN_LEVEL}–${SentenceTemplates.MAX_LEVEL})" to sentences,
        "Γράψε (${TraceViewModel.MIN_LEVEL}–${TraceViewModel.MAX_LEVEL})" to trace,
    )

    private fun StringBuilder.words(list: List<Pair<String, Int>>) {
        if (list.isEmpty()) {
            appendLine("- $NOTHING")
            return
        }
        list.forEach { (text, n) -> appendLine("- ${text.oneLine().take(MAX_WORD)}: ${timesLine(n)}") }
    }

    /** One item per line, whatever the item holds: a newline inside a word would break the headings. */
    private fun String.oneLine(): String = replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    private fun date(at: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return "${d.dayOfMonth}/${d.monthValue}/${d.year}"
    }

    private fun dayCount(days: Int): String = if (days == 1) "1 μέρα" else "$days μέρες"
}
