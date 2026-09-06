package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.AppGraph
import gr.dimitris.app.caregiver.progress.DayItemStat
import gr.dimitris.app.caregiver.progress.DayStat
import gr.dimitris.app.caregiver.progress.ItemHistory
import gr.dimitris.app.caregiver.progress.ProgressStats
import gr.dimitris.app.caregiver.progress.attemptsLine
import gr.dimitris.app.caregiver.progress.minutesLine
import gr.dimitris.app.core.data.AttemptDao
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Note
import gr.dimitris.app.core.scheduler.LeitnerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import gr.dimitris.app.core.data.Advice as AdviceRow
import gr.dimitris.app.core.data.now as systemNow

/**
 * Everything the app knows about Dimitris, in Greek, as one piece of plain text.
 *
 * Chris asked for the advisor to judge his progress and to remember his whole journey — "even a
 * vector DB if needed". It does not need one. These are tables: every word he has ever practised,
 * every day of the last month, the notes the people around him wrote, and the advices Claude itself
 * gave before. A language model reads a well-ordered table better than it reads a retrieved
 * fragment, and a caregiver can read this one too — which matters, because this is the one text
 * that leaves the phone.
 *
 * What is *not* here is the point, exactly as in the summary this replaces. No file path, no id, no
 * recording, no photograph — the photograph is a «ναι», never a name. That is structural rather
 * than filtered: [ItemHistory] has no id or path field to leak, so nothing downstream has to
 * remember to strip one.
 *
 * Two ceilings, in this order. [MAX_LIFETIME_LINES] lines of history, because past a few hundred
 * words the tail is noise; and [MAX_CHARS] for the whole thing, shed by dropping the oldest history
 * lines first and then the day-by-day detail older than [DETAIL_DAYS] days — the recent days are
 * the ones a week's advice turns on.
 */
object JourneyReport {
    /** A very high ceiling on a text that is normally a few tens of thousands of characters. */
    const val MAX_CHARS = 400_000

    /** One line each for the four hundred words he has practised most. */
    const val MAX_LIFETIME_LINES = 400

    /** Days of per-word detail kept when the report has to be made smaller. */
    const val DETAIL_DAYS = 14

    /** How many of the caregivers' notes travel. Newest first: this month, not this decade. */
    const val MAX_NOTES = 20

    /** How many previous advices Claude is shown, so it can say what changed. */
    const val MAX_PREVIOUS = 5

    /** How much of one note is worth sending. A note is a paragraph, not a chapter. */
    const val MAX_NOTE = Note.MAX_TEXT

    /** How much of one previous advice's caregivers' section is quoted back at it. */
    const val MAX_ADVICE = 3_000

    /** How much of one word is worth sending. A "word" longer than this is somebody's paragraph. */
    const val MAX_WORD = AdviceSummary.MAX_WORD

    /** How much of one insight line is worth sending. */
    const val MAX_LINE = AdviceSummary.MAX_LINE

    const val PROFILE_HEADING = "Προφίλ"
    const val NOTES_HEADING = "Σημειώσεις φροντιστών"
    const val LIFETIME_HEADING = "Όλη η πορεία ανά λέξη"
    const val RECENT_HEADING = "Τελευταίες 4 εβδομάδες ανά ημέρα"
    const val PREVIOUS_HEADING = "Προηγούμενες συμβουλές"
    const val LEVELS_HEADING = "Επίπεδα"
    const val INSIGHTS_HEADING = "Τι βλέπει η εφαρμογή"

    private const val CUT = "\n… (η αναφορά κόπηκε)"
    private const val NOTHING = "Τίποτα ακόμα."

    /** Greek writes a decimal comma, and the cue means are the only decimals here. */
    private val greek: Locale = Locale.forLanguageTag("el")

    /**
     * Who he is, said once at the top so everything under it is read as being about him. The same
     * facts as [ClaudeAdvisor.SYSTEM_PROMPT]'s opening, and fixed in the app for the same reason:
     * it is not something a caregiver can turn into words the phone then reads out loud to him.
     */
    val PROFILE: String = """
        Ο Δημήτρης είναι ενήλικας άνδρας στην Ελλάδα. Πριν από περίπου δυόμισι χρόνια είχε εγκεφαλικό
        στο αριστερό ημισφαίριο. Έχει δεξιά ημιπάρεση, αφασία Broca (καταλαβαίνει πολύ καλά,
        δυσκολεύεται να βγάλει τις λέξεις) και ακαλκουλία. Η μνήμη, το χιούμορ και το τραγούδι του
        είναι ακέραια — τραγουδάει λέξεις που δεν μπορεί να πει.

        Εξασκείται μόνος του στο τηλέφωνό του, στα ελληνικά. Οι ασκήσεις είναι: Λέξεις, Αριθμοί,
        Τραγούδα και πες το, Διάλογοι, Προτάσεις, Γράψε, Δεξί χέρι. Ο πίνακας επικοινωνίας («Μίλα»)
        δεν είναι άσκηση: κάθε πάτημα εκεί καταγράφεται ως σωστό επειδή είναι ο ίδιος που μιλάει,
        οπότε μετράει στις ασκήσεις αλλά ποτέ στα σωστά.

        Η βοήθεια μετριέται 0–4 σε κάθε λέξη (0 = καμία βοήθεια, 4 = του δόθηκε η λέξη). Λιγότερη
        βοήθεια είναι καλύτερα. Το «κουτί» είναι η επανάληψη με κενά: 1 = καινούργια λέξη,
        5 = μαθημένη.
    """.trimIndent()

    /**
     * The whole thing. Everything handed in is already computed — this file only decides what is
     * said and in what order, so all of it can be argued with in a unit test.
     */
    fun build(
        profile: String,
        notes: List<Note>,
        lifetime: List<ItemHistory>,
        recent: List<DayItemStat>,
        days: List<DayStat>,
        previous: List<AdviceRow>,
        levels: Map<String, Int>,
        insights: List<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        var lines = MAX_LIFETIME_LINES
        var text = render(profile, notes, lifetime, recent, days, previous, levels, insights, zone, lines, Int.MAX_VALUE)
        // The order the ceiling is paid for, cheapest loss first: the tail of the word history is
        // words he has barely met, and the day-by-day detail older than a fortnight is a month of
        // background against which nobody plans next week.
        while (text.length > MAX_CHARS && lines > 0) {
            lines /= 2
            text = render(profile, notes, lifetime, recent, days, previous, levels, insights, zone, lines, Int.MAX_VALUE)
        }
        // Then the day-by-day detail, from a fortnight back and closing in. Every day keeps its
        // total however far this goes, because a report that has given up its detail is still a
        // true account of the month — and losing the newest days to a blind cut at the end, which
        // is what happens if this stops early, would be the wrong thing to lose.
        var detail = DETAIL_DAYS
        while (text.length > MAX_CHARS) {
            text = render(profile, notes, lifetime, recent, days, previous, levels, insights, zone, lines, detail)
            if (detail == 0) break
            detail /= 2
        }
        return if (text.length <= MAX_CHARS) text else cut(text)
    }

    @Suppress("LongParameterList")
    private fun render(
        profile: String,
        notes: List<Note>,
        lifetime: List<ItemHistory>,
        recent: List<DayItemStat>,
        days: List<DayStat>,
        previous: List<AdviceRow>,
        levels: Map<String, Int>,
        insights: List<String>,
        zone: ZoneId,
        lifetimeLines: Int,
        detailDays: Int,
    ): String = buildString {
        appendLine(PROFILE_HEADING)
        appendLine(profile.trim())
        appendLine()

        appendLine(NOTES_HEADING)
        val recentNotes = notes.filter { !it.deleted }.sortedByDescending { it.at }.take(MAX_NOTES)
        if (recentNotes.isEmpty()) appendLine("- $NOTHING")
        recentNotes.forEach { n ->
            appendLine("- ${date(n.at, zone)} (${author(n.author)}): ${n.text.oneLine().take(MAX_NOTE)}")
        }
        appendLine()

        appendLine(LIFETIME_HEADING)
        appendLine(
            "(οι ασκήσεις μετρούν και τον πίνακα επικοινωνίας· τα σωστά/με βοήθεια/παράλειψη μετρούν " +
                "μόνο τις ασκήσεις που βαθμολογούνται)"
        )
        val shown = lifetime.take(lifetimeLines.coerceAtLeast(0))
        if (shown.isEmpty()) appendLine("- $NOTHING")
        shown.forEach { appendLine("- ${line(it, zone)}") }
        if (lifetime.size > shown.size) appendLine("- … και άλλες ${lifetime.size - shown.size} λέξεις με λιγότερες ασκήσεις")
        appendLine()

        appendLine(RECENT_HEADING)
        val busy = days.filter { it.attempts > 0 || it.minutes > 0 }
        if (busy.isEmpty()) appendLine("- $NOTHING")
        // Detail is kept for the newest [detailDays] days he actually worked; the older days keep
        // their one-line total, because "he did nothing for a fortnight" is itself information.
        val detailed = busy.takeLast(detailDays.coerceAtLeast(0)).map { it.day }.toSet()
        val byDay = recent.groupBy { it.day }
        busy.forEach { d ->
            appendLine("- ${date(d.day, zone)}: ${minutesLine(d.minutes)}, ${attemptsLine(d.attempts)}")
            if (d.day in detailed) {
                byDay[d.day].orEmpty().forEach { s ->
                    val cue = s.meanCue?.let { ", βοήθεια ${decimal(it)}" }.orEmpty()
                    appendLine("  - ${s.text.oneLine().take(MAX_WORD)}: ${s.attempts} (${s.correct}/${s.assisted}/${s.skipped})$cue")
                }
            }
        }
        appendLine()

        appendLine(PREVIOUS_HEADING)
        val past = previous.filter { !it.deleted }.sortedByDescending { it.at }.take(MAX_PREVIOUS)
        if (past.isEmpty()) appendLine("- $NOTHING")
        past.forEachIndexed { i, a ->
            if (i > 0) appendLine()
            appendLine("- ${date(a.at, zone)}")
            appendLine(a.caregivers.trim().take(MAX_ADVICE).ifBlank { NOTHING })
            appendLine("Εστίαση: ${a.focusJson.oneLine().ifBlank { "—" }}")
        }
        appendLine()

        appendLine(LEVELS_HEADING)
        if (levels.isEmpty()) appendLine("- $NOTHING")
        levels.forEach { (label, value) -> appendLine("- $label: $value") }
        appendLine()

        appendLine(INSIGHTS_HEADING)
        if (insights.isEmpty()) appendLine("- $NOTHING")
        insights.forEach { appendLine("- ${it.oneLine().take(MAX_LINE)}") }
    }

    /**
     * One word's whole life, on one line:
     * `λέξη · είδος · κατηγορία · πρώτος ήχος · ασκήσεις N · σ/β/π · μέση βοήθεια x,x · κουτί b ·
     * πρώτη/τελευταία φορά · φωτογραφία ναι/όχι · φωνή ναι/όχι`.
     */
    internal fun line(h: ItemHistory, zone: ZoneId): String = listOf(
        h.text.oneLine().take(MAX_WORD),
        kind(h.kind),
        h.category.greek,
        h.firstSound.ifBlank { "—" },
        "ασκήσεις ${h.attempts}",
        "σωστά ${h.correct}/με βοήθεια ${h.assisted}/παράλειψη ${h.skipped}",
        "μέση βοήθεια ${h.meanCue?.let { decimal(it) } ?: "—"}",
        "κουτί ${h.box}",
        "${date(h.firstAt, zone)}–${date(h.lastAt, zone)}",
        "φωτογραφία ${yesNo(h.hasPhoto)}",
        "φωνή ${yesNo(h.hasVoice)}",
    ).joinToString(" · ")

    private fun kind(kind: ItemKind): String = when (kind) {
        ItemKind.WORD -> "λέξη"
        ItemKind.PHRASE -> "φράση"
        ItemKind.NUMBER -> "αριθμός"
        ItemKind.SCRIPT_LINE -> "ατάκα διαλόγου"
    }

    /** The device role that wrote a note, said in Greek. Anything else is left as it arrived. */
    internal fun author(name: String): String = when (name) {
        "CAREGIVER" -> "φροντιστής"
        "DIMITRIS" -> "Δημήτρης"
        else -> name.oneLine().take(MAX_WORD).ifBlank { "—" }
    }

    private fun yesNo(value: Boolean): String = if (value) "ναι" else "όχι"

    private fun decimal(value: Float): String = String.format(greek, "%.1f", value)

    /**
     * The ceiling, honoured at a line boundary. Every section above the last one is bounded by
     * construction, so today a blind cut could only land in the final list — but "today" is not a
     * guarantee, and half a heading would tell the reader something false about what was sent.
     */
    private fun cut(text: String): String {
        val room = MAX_CHARS - CUT.length
        val lastLine = text.lastIndexOf('\n', room - 1)
        return text.take(if (lastLine > 0) lastLine else room) + CUT
    }

    /** One thing per line, whatever it holds: a newline inside a word would break the headings. */
    private fun String.oneLine(): String = replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    private fun date(at: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return "${d.dayOfMonth}/${d.monthValue}/${d.year}"
    }
}

/**
 * The report from the live database, built once and read by both screens that need it: «Ρώτα τον
 * Claude» sends it, and «Εξαγωγή αναφοράς» on the dashboard shares exactly the same text — so a
 * caregiver sending it to a speech therapist and a caregiver asking Claude are talking about the
 * same thing, and neither has to wonder which.
 *
 * Built on demand rather than kept in a screen's state. It reads every attempt ever recorded
 * ([AttemptDao.LIFETIME_LIMIT]), which is not work to do on the way to drawing a dashboard nobody
 * has asked to share yet. Call it off the main thread.
 */
suspend fun journeyReport(
    graph: AppGraph,
    now: Long = systemNow(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val db = graph.db
    val to = now
    val from = ProgressStats.from(to, zone = zone)
    // Twice the window, because one insight rule compares this period with the one before it —
    // the same read the dashboard does, for the same reason.
    val earlier = ProgressStats.from(to, ProgressStats.DEFAULT_DAYS * 2, zone)

    val everything = db.attempts().all(AttemptDao.LIFETIME_LIMIT)
    val items = db.items().allActive().associateBy { it.id }
    val schedules = db.schedules().allRows()
    val voices = db.recordings().itemsWithVoice().toSet()
    val sessions = db.sessions().between(from, to)
    val mastered = db.schedules().masteredCount(LeitnerPolicy.MAX_BOX)
    val notes = db.notes().recent(JourneyReport.MAX_NOTES)
    val previous = db.advice().recent(JourneyReport.MAX_PREVIOUS)
    val levels = AdviceSummary.levels(
        graph.settings.numbersLevel.first(),
        graph.settings.sentencesLevel.first(),
        graph.settings.traceLevel.first(),
    )

    return withContext(Dispatchers.Default) {
        val window = everything.filter { it.startedAt >= earlier }
        val p = ProgressStats.compute(window, sessions, emptyList(), items, from, to, zone, mastered = mastered)
        JourneyReport.build(
            profile = JourneyReport.PROFILE,
            notes = notes,
            lifetime = ProgressStats.lifetime(everything, schedules, items, voices),
            recent = ProgressStats.recentByDay(window, items, from, to, zone),
            days = p.days,
            previous = previous,
            levels = levels,
            insights = InsightRules.generate(p, window, items),
            zone = zone,
        )
    }
}
