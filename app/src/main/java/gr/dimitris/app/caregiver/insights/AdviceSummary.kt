package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.caregiver.progress.Progress
import gr.dimitris.app.caregiver.progress.attemptsLine
import gr.dimitris.app.caregiver.progress.minutesLine
import gr.dimitris.app.caregiver.progress.streakLine
import gr.dimitris.app.caregiver.progress.timesLine
import gr.dimitris.app.core.data.ModuleId
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
            appendLine("Μαθημένες λέξεις: ${p.mastered}")
            appendLine()

            appendLine("Ανά άσκηση")
            if (p.modules.isEmpty()) appendLine("- $NOTHING")
            p.modules.forEach { m ->
                appendLine(
                    "- ${names[m.module] ?: m.module.name}: ${attemptsLine(m.attempts)}, " +
                        "${(m.accuracy * 100).roundToInt()}% σωστά " +
                        "(σωστά ${m.correct}, με βοήθεια ${m.assisted}, προσπέρασε ${m.skipped})"
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
            insights.forEach { appendLine("- ${it.oneLine()}") }
        }
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - CUT.length) + CUT
    }

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
