package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.AppGraph
import gr.dimitris.app.caregiver.progress.DayItemStat
import gr.dimitris.app.caregiver.progress.DayStat
import gr.dimitris.app.caregiver.progress.ItemHistory
import gr.dimitris.app.caregiver.progress.JudgeUse
import gr.dimitris.app.caregiver.progress.ModuleHistory
import gr.dimitris.app.caregiver.progress.ProgressStats
import gr.dimitris.app.caregiver.progress.attemptsLine
import gr.dimitris.app.caregiver.progress.minutesLine
import gr.dimitris.app.core.data.AttemptDao
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Note
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.scheduler.LeitnerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
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
    const val MODULES_HEADING = "Ανά άσκηση"
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

        Τον Σεπτέμβριο του 2026 δοκίμασε ο ίδιος την εφαρμογή και είπε ότι είναι πολύ εύκολη. Λέει
        τις περισσότερες καθημερινές λέξεις (όχι πάντα καθαρά), διαβάζει ελληνικά αργά αλλά
        καταλαβαίνει και αφηρημένες λέξεις, και σκέφτεται καλά. Ο λόγος του είναι τηλεγραφικός —
        λέει «φάρμακα πρέπει πάρω». Αυτό που του λείπει είναι οι ολόκληρες προτάσεις και οι εργασίες
        με πολλά βήματα, και εκεί είναι τώρα το κέντρο της εφαρμογής.

        Εξασκείται μόνος του στο τηλέφωνό του, στα ελληνικά. Οι ασκήσεις, με τα ονόματα που βλέπει
        και τους κωδικούς που χρησιμοποιεί η εφαρμογή:
        - Λέξεις = WORDCOACH (βρίσκει τη λέξη για μια εικόνα)
        - Αριθμοί = NUMBERS (ποσά, πράξεις, ευρώ και ρέστα, ώρα, μέρες, προβλήματα)
        - Τραγούδα και πες το = SINGSAY (τραγουδάει τη φράση και μετά τη λέει)
        - Διάλογοι = SCRIPTS (ανοιχτοί διάλογοι· απαντάει με δικά του λόγια)
        - Προτάσεις = SENTENCES (φτιάχνει, συμπληρώνει ή γράφει ολόκληρη πρόταση)
        - Γράψε = TRACE (γράφει γράμματα και λέξεις με το δάχτυλο)
        - Δεξί χέρι = ARCADE (ασκήσεις για το δεξί του χέρι, όχι λόγος)
        - Βήματα = STEPS (βάζει τα βήματα μιας δουλειάς στη σειρά και μετά τα λέει: πρώτα… μετά…
          τέλος. Δύο γραμμές ανά δουλειά: steps:order:… η σειρά, steps:tell:… το να τα πει)
        - SQL = SQL (απλές ερωτήσεις SELECT σε πίνακες· ήταν προγραμματιστής και το ζήτησε)
        - Μίλα = TALKBOARD, ο πίνακας επικοινωνίας. Δεν είναι άσκηση: κάθε πάτημα καταγράφεται ως
          σωστό επειδή είναι ο ίδιος που μιλάει, οπότε μετράει στις ασκήσεις αλλά ποτέ στα σωστά.

        Η «δυσκολία» σε κάθε άσκηση είναι μια σειρά από πέντε κουκκίδες, 1 έως 5, στην πρώτη της
        οθόνη. Τις πατάει ο ίδιος· ο φροντιστής βάζει μόνο κάτω και πάνω όριο. Η κουκκίδα διαλέγει
        ζώνη επιπέδων και μέσα στη ζώνη το επίπεδο ανεβοκατεβαίνει μόνο του, όπως πάντα. Το «Μίλα»
        δεν έχει κουκκίδες.

        Ο «Claude» σε μια γραμμή άσκησης είναι ο έλεγχος της απάντησής του από το μοντέλο («Έλεγχος
        με Claude», προαιρετικός, τον ανοίγει ο φροντιστής). Κρίνει αν μια ανοιχτή απάντηση μετράει
        και του δίνει ολόκληρη τη σωστή πρόταση για να την επαναλάβει. Το «χωρίς Claude» είναι οι
        φορές που αποφάσισε μόνο του το τηλέφωνο — κλειστός διακόπτης, χωρίς κλειδί, ή αποτυχία.

        Η βοήθεια μετριέται 0–4 σε κάθε λέξη, σε αυτή τη σειρά:
        0 = το είπε μόνος του, με μόνη βοήθεια την εικόνα
        1 = του δόθηκε ο πρώτος ήχος
        2 = του δόθηκε η πρώτη συλλαβή
        3 = άκουσε τη λέξη
        4 = άκουσε και είδε γραμμένη τη λέξη

        Σημαντικό: το κουμπί «Άκου» υπάρχει σε κάθε οθόνη και του λέει τη λέξη όποτε το ζητήσει.
        Αυτό είναι σκόπιμο (μάθηση χωρίς λάθη) και τον ενθαρρύνουμε να το πατάει. Κάθε φορά που το
        πατάει, η βοήθεια της προσπάθειας γράφεται τουλάχιστον 3. Άρα υψηλή μέση βοήθεια μπορεί να
        σημαίνει απλώς ότι διάλεξε να ακούσει, όχι ότι πήγε πίσω. Μην προτείνεις ποτέ να του
        στερήσουν το «Άκου».

        Το αποτέλεσμα κάθε προσπάθειας είναι ένα από τα τρία:
        σωστά = το είπε με βοήθεια 0–2
        με βοήθεια = το είπε αφού άκουσε ή είδε τη λέξη (βοήθεια 3–4)
        παράλειψη = το προσπέρασε χωρίς να το πει

        Το «κουτί» είναι η επανάληψη με κενά, 1 έως 5: 1 = καινούργια λέξη, 5 = μαθημένη. Όσο πιο
        ψηλά το κουτί, τόσο πιο αραιά του ξαναέρχεται η λέξη. Παύλα σημαίνει ότι η λέξη δεν έχει
        μπει ακόμα σε πρόγραμμα επανάληψης.

        Το «γράψιμο» σε μια γραμμή λέξης είναι πόσες φορές την έγραψε στο «Γράψε». Μετριέται χωριστά
        επειδή αφορά το δεξί του χέρι και όχι την εύρεση της λέξης, και για τον ίδιο λόγο δεν
        μετράει στα σωστά/με βοήθεια/παράλειψη.
    """.trimIndent()

    /**
     * The whole thing. Everything handed in is already computed — this file only decides what is
     * said and in what order, so all of it can be argued with in a unit test.
     */
    fun build(
        profile: String,
        notes: List<Note>,
        modules: List<ModuleHistory>,
        lifetime: List<ItemHistory>,
        recent: List<DayItemStat>,
        wordless: Map<Long, Map<ModuleId, Int>>,
        days: List<DayStat>,
        previous: List<AdviceRow>,
        levels: Map<String, Int>,
        insights: List<String>,
        /**
         * The dot each module is set to right now, 1–5 (spec §13). A module missing from the map
         * gets **no segment** rather than a «δυσκολία —»: the talk board has no dots at all, and a
         * setting that could not be read is not a dot of 2 that nobody chose. Either way the line
         * stays true, and the reader is told nothing false about a number he set himself.
         */
        difficulty: Map<ModuleId, Int> = emptyMap(),
        /** What the turn judge did in the window, per module. See [ProgressStats.judgeUse]. */
        judge: Map<ModuleId, JudgeUse> = emptyMap(),
        names: Map<ModuleId, String> = AdviceSummary.MODULE_NAMES,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val all = Sections(profile, notes, modules, lifetime, recent, wordless, days, previous, levels, insights, difficulty, judge, names, zone)
        var lines = MAX_LIFETIME_LINES
        var text = render(all, lines, Int.MAX_VALUE)
        // The order the ceiling is paid for, cheapest loss first: the tail of the word history is
        // words he has barely met, and the day-by-day detail older than a fortnight is a month of
        // background against which nobody plans next week.
        while (text.length > MAX_CHARS && lines > 0) {
            lines /= 2
            text = render(all, lines, Int.MAX_VALUE)
        }
        // Then the day-by-day detail, from a fortnight back and closing in. Every day keeps its
        // total however far this goes, because a report that has given up its detail is still a
        // true account of the month — and losing the newest days to a blind cut at the end, which
        // is what happens if this stops early, would be the wrong thing to lose.
        var detail = DETAIL_DAYS
        while (text.length > MAX_CHARS) {
            text = render(all, lines, detail)
            if (detail == 0) break
            detail /= 2
        }
        return if (text.length <= MAX_CHARS) text else cut(text)
    }

    /** Everything [build] was handed, so the renderer can be called again for less of it. */
    private class Sections(
        val profile: String,
        val notes: List<Note>,
        val modules: List<ModuleHistory>,
        val lifetime: List<ItemHistory>,
        val recent: List<DayItemStat>,
        val wordless: Map<Long, Map<ModuleId, Int>>,
        val days: List<DayStat>,
        val previous: List<AdviceRow>,
        val levels: Map<String, Int>,
        val insights: List<String>,
        val difficulty: Map<ModuleId, Int>,
        val judge: Map<ModuleId, JudgeUse>,
        val names: Map<ModuleId, String>,
        val zone: ZoneId,
    )

    private fun render(all: Sections, lifetimeLines: Int, detailDays: Int): String = buildString {
        val profile = all.profile
        val notes = all.notes
        val lifetime = all.lifetime
        val recent = all.recent
        val days = all.days
        val previous = all.previous
        val levels = all.levels
        val insights = all.insights
        val zone = all.zone
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

        appendLine(MODULES_HEADING)
        appendLine("(όλη η πορεία, και οι ασκήσεις χωρίς λέξη — Αριθμοί, Προτάσεις, Βήματα, Γράψε, Δεξί χέρι, SQL)")
        appendLine(
            "(«δυσκολία» είναι η κουκκίδα 1–5 που είναι βαλμένη τώρα· ο «Claude» μετράει μόνο τις " +
                "τελευταίες 4 εβδομάδες)"
        )
        if (all.modules.isEmpty()) appendLine("- $NOTHING")
        all.modules.forEach { appendLine("- ${moduleLine(it, all.names, zone, all.difficulty, all.judge)}") }
        appendLine()

        appendLine(LIFETIME_HEADING)
        appendLine(
            "(οι ασκήσεις μετρούν και τον πίνακα επικοινωνίας· τα σωστά/με βοήθεια/παράλειψη μετρούν " +
                "μόνο τον λόγο, όχι το «Γράψε», που φαίνεται χωριστά ως «γράψιμο»)"
        )
        val shown = lifetime.take(lifetimeLines.coerceAtLeast(0))
        if (shown.isEmpty()) appendLine("- $NOTHING")
        shown.forEach { appendLine("- ${line(it, zone)}") }
        // Only one of the two lines: a section that says «Τίποτα ακόμα.» and then counts what it
        // left out is telling the reader two different things about the same emptiness.
        if (lifetime.size > shown.size && shown.isNotEmpty()) {
            appendLine("- … και άλλες ${lifetime.size - shown.size} λέξεις με λιγότερες ασκήσεις")
        } else if (lifetime.isNotEmpty() && shown.isEmpty()) {
            appendLine("- (${lifetime.size} λέξεις, παραλείφθηκαν για να χωρέσει η αναφορά)")
        }
        appendLine()

        appendLine(RECENT_HEADING)
        val busy = days.filter { it.attempts > 0 || it.minutes > 0 }
        if (busy.isEmpty()) appendLine("- $NOTHING")
        // Detail is kept for the newest [detailDays] days he actually worked; the older days keep
        // their one-line total, because "he did nothing for a fortnight" is itself information.
        val detailed = busy.takeLast(detailDays.coerceAtLeast(0)).map { it.day }.toSet()
        val byDay = recent.groupBy { it.day }
        busy.forEach { d ->
            // The day total counts every exercise; the lines under it are words only. Without this
            // «12 ασκήσεις» over five word lines reads as data loss rather than as the numbers
            // module having had a good morning.
            val wordless = all.wordless[d.day].orEmpty()
            val missing = wordless.values.sum()
            val whichever = if (missing == 0) "" else
                " (εκ των οποίων $missing χωρίς λέξη: " +
                    wordless.entries.joinToString(", ") { (m, n) -> "${all.names[m] ?: m.name} $n" } + ")"
            appendLine("- ${date(d.day, zone)}: ${minutesLine(d.minutes)}, ${attemptsLine(d.attempts)}$whichever")
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
            appendLine("Εστίαση: ${a.focusJson.oneLine().take(MAX_ADVICE).ifBlank { "—" }}")
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
        "γράψιμο ${h.traced}",
        "μέση βοήθεια ${h.meanCue?.let { decimal(it) } ?: "—"}",
        // A dialogue line is scheduled against its script, never against itself, so it has no box
        // of its own. «κουτί 0» would be a value outside the 1–5 scale the profile explains, and a
        // reader would take it for "he has forgotten it" rather than "this is not a word".
        "κουτί ${if (h.box <= 0) "—" else h.box.toString()}",
        "${date(h.firstAt, zone)}–${date(h.lastAt, zone)}",
        "φωτογραφία ${yesNo(h.hasPhoto)}",
        "φωνή ${yesNo(h.hasVoice)}",
    ).joinToString(" · ")

    /**
     * One module's whole life on one line:
     * `Λέξεις (WORDCOACH) · ασκήσεις N · σ/β/π · μέση βοήθεια x,x · μέσος χρόνος N δευτ. ·
     * πρώτη/τελευταία φορά · δυσκολία 3/5 · επίπεδο ανά εβδομάδα: 3/8 2,0 · 10/8 3,0 ·
     * Claude: έκρινε 12, δέχτηκε 9 (75%), ολόκληρη πρόταση 5`.
     *
     * The enum name is printed beside the Greek one on purpose: the focus JSON asks for module ids,
     * and a model that has only ever been shown «Γράψε» has to guess that it means `TRACE`.
     *
     * The last two segments are phase 12. The dot is what he has actually set — the advice is asked
     * to name the dot each module should go to next, and it cannot do that without knowing where the
     * dot is now — and the judge segment is there because «Έλεγχος με Claude» is opt-in: without it
     * a month of open dialogues where the key had expired reads exactly like a month where it
     * worked. Both are left off a module that has nothing to say rather than printed as zeroes.
     */
    internal fun moduleLine(
        m: ModuleHistory,
        names: Map<ModuleId, String>,
        zone: ZoneId,
        difficulty: Map<ModuleId, Int> = emptyMap(),
        judge: Map<ModuleId, JudgeUse> = emptyMap(),
    ): String {
        val scored = if (m.module in ProgressStats.GRADED_MODULES) {
            "σωστά ${m.correct}/με βοήθεια ${m.assisted}/παράλειψη ${m.skipped}"
        } else {
            "πίνακας επικοινωνίας, χωρίς σωστό και λάθος"
        }
        val levels = if (m.levels.isEmpty()) "χωρίς επίπεδο" else
            "επίπεδο ανά εβδομάδα: " + m.levels.joinToString(" · ") { "${shortDate(it.weekStart, zone)} ${decimal(it.level)}" }
        return listOfNotNull(
            "${names[m.module] ?: m.module.name} (${m.module.name})",
            "ασκήσεις ${m.attempts}",
            scored,
            "μέση βοήθεια ${m.meanCue?.let { decimal(it) } ?: "—"}",
            "μέσος χρόνος ${seconds(m.meanMs)}",
            "${date(m.firstAt, zone)}–${date(m.lastAt, zone)}",
            difficulty[m.module]?.let { "δυσκολία $it/${Difficulty.MAX}" },
            levels,
            judge[m.module]?.takeIf { !it.isEmpty }?.let { judgeLine(it) },
        ).joinToString(" · ")
    }

    /**
     * What the judge did in this module over the window, in the order a caregiver would ask it: how
     * many Claude decided, how many of those it accepted, how many whole sentences it handed him to
     * repeat, and — only when there were any — how many turns the phone had to decide by itself.
     *
     * The percentage is over what Claude decided, never over the fallback's rows: see [JudgeUse].
     * «γραπτές προτάσεις» is «Προτάσεις» alone and is printed only where there are some.
     */
    internal fun judgeLine(u: JudgeUse): String = buildString {
        append("Claude: ")
        val parts = buildList {
            if (u.judged > 0) {
                add("έκρινε ${u.judged}")
                add("δέχτηκε ${u.accepted} (${percent(u.acceptRate)})")
                add("ολόκληρη πρόταση ${u.expanded}")
            }
            if (u.local > 0) add("χωρίς Claude ${u.local}")
            if (u.typed > 0) add("γραπτές προτάσεις ${u.typed}")
        }
        append(parts.joinToString(", "))
    }

    /** A rate as a whole percent, the way a caregiver reads one. Null is «—». */
    private fun percent(rate: Float?): String = if (rate == null) "—" else "${(rate * 100).roundToInt()}%"

    /** Milliseconds as the seconds a person would say. */
    private fun seconds(ms: Long): String = "${decimal(ms / 1000f)} δευτ."

    private fun shortDate(at: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return "${d.dayOfMonth}/${d.monthValue}"
    }

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
    // Every id the vocabulary has ever had, soft-deleted rows included: a word a caregiver has
    // since removed was still a word he practised, and its attempts are not «χωρίς λέξη».
    val everKnown = db.items().all().mapTo(mutableSetOf()) { it.id }
    val schedules = db.schedules().allRows()
    // A caregiver's voice only: his own takes are him practising, not a model to practise against.
    val voices = db.recordings().itemsWithVoice(Who.CAREGIVER).toSet()
    val sessions = db.sessions().between(from, to)
    val mastered = db.schedules().masteredCount(LeitnerPolicy.MAX_BOX)
    val notes = db.notes().recent(JourneyReport.MAX_NOTES)
    val previous = db.advice().recent(JourneyReport.MAX_PREVIOUS)
    val levels = AdviceSummary.levels(
        graph.settings.numbersLevel.first(),
        graph.settings.sentencesLevel.first(),
        graph.settings.traceLevel.first(),
    )
    // The dot each module is set to right now (spec §13). Only the modules that have one: the talk
    // board is not graded and has no row of dots, and a «δυσκολία 2» beside it would be a fact about
    // nothing. A read that fails leaves that module without the segment rather than reporting a
    // default nobody set.
    val difficulty = graph.modules.mapNotNull { m ->
        runCatching { m.id to graph.settings.difficulty(m.id).first() }.getOrNull()
    }.toMap()
    // The names the modules themselves carry, so a renamed module renames its line here too.
    val names = graph.modules.associate { it.id to it.titleGreek } + (ModuleId.TALKBOARD to "Μίλα")

    return withContext(Dispatchers.Default) {
        val window = everything.filter { it.startedAt >= earlier }
        val p = ProgressStats.compute(window, sessions, emptyList(), items, from, to, zone, mastered = mastered)
        JourneyReport.build(
            profile = JourneyReport.PROFILE,
            notes = notes,
            modules = ProgressStats.moduleHistory(everything, zone = zone),
            lifetime = ProgressStats.lifetime(everything, schedules, items, voices),
            recent = ProgressStats.recentByDay(window, items, from, to, zone),
            wordless = ProgressStats.wordlessByDay(window, everKnown, from, to, zone),
            days = p.days,
            previous = previous,
            levels = levels,
            insights = InsightRules.generate(p, window, items),
            difficulty = difficulty,
            // The same four weeks the day-by-day section covers, not the lifetime and not the eight
            // weeks `window` holds for the insight rules: «δέχτηκε 9 στα 12» has to be a statement
            // about the period the rest of the report is about, or nobody can act on it.
            judge = ProgressStats.judgeUse(everything.filter { it.startedAt >= from }),
            names = names,
            zone = zone,
        )
    }
}
