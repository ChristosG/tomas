package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.caregiver.progress.ModuleStat
import gr.dimitris.app.caregiver.progress.Progress
import gr.dimitris.app.caregiver.progress.ProgressStats
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import kotlin.math.roundToInt

/**
 * CHRIS: rewrite me. These are the sentences the app says about Dimitris to the people who look
 * after him, and what counts as news is a judgement, not a calculation. Claude wrote a first
 * version so nothing blocks; InsightRulesTest describes the contract, and every string in it is
 * checked whole.
 *
 * The rules run in priority order and each one speaks at most once, so the good news comes first
 * and the screen never turns into a list. Nothing here is a diagnosis and nothing here scolds him:
 * a quiet four weeks gets one plain line, not six.
 */
object InsightRules {
    /** More than this and it stops being a summary. */
    const val MAX_LINES = 6

    // CHRIS: rewrite me. Every threshold below is a judgement call, not a measurement.
    /** Days in a row before a streak is worth naming. */
    const val STREAK_DAYS = 3
    /** A module has to have been worked at before "it is going well" means anything. */
    const val MODULE_ATTEMPTS = 20
    const val MODULE_ACCURACY = 0.8f
    /** How much the mean cue level has to fall across the window before it is a real change. */
    const val CUE_DROP = 0.5f
    const val SOUND_ATTEMPTS = 6
    const val SOUND_RISE = 0.2f
    /** A word skipped this often is a word he is stuck on, and three of them are a pattern. */
    const val SKIPS = 3
    const val HARD_WORDS = 3
    /** Days of silence before the dashboard says so. */
    const val IDLE_DAYS = 3

    fun generate(p: Progress, attempts: List<Attempt>, items: Map<String, Item>): List<String> {
        val out = mutableListOf<String>()

        if (p.streakDays >= STREAK_DAYS) out += "Σερί ${p.streakDays} ημερών. Συνέχισε έτσι!"

        bestModule(p)?.let { out += "${subject(it.module)} πάνε πολύ καλά: ${percent(it.accuracy)}% σωστά." }

        if (needsLessHelp(p)) out += "Χρειάζεται λιγότερη βοήθεια από την προηγούμενη εβδομάδα."

        improvedSound(p, attempts, items)?.let { out += "Οι λέξεις που αρχίζουν από «$it» βελτιώθηκαν." }

        hardWords(p)?.let { out += "Δύσκολες λέξεις: ${it.joinToString(", ")}. Δοκίμασε φωτογραφία ή φωνή." }

        idleDays(p)?.let { out += "Καμία άσκηση εδώ και $it μέρες." }

        return out.take(MAX_LINES)
    }

    /**
     * How a module is named as the subject of «... πάνε πολύ καλά». Every one of them is plural, so
     * the verb never has to change — and «Γράψε» and «Δεξί χέρι», which are not nouns at all, are
     * said as the things he actually does in them.
     */
    fun subject(module: ModuleId): String = when (module) {
        ModuleId.TALKBOARD -> "Οι λέξεις στον πίνακα"
        ModuleId.WORDCOACH -> "Οι Λέξεις"
        ModuleId.NUMBERS -> "Οι Αριθμοί"
        ModuleId.SINGSAY -> "Τα τραγούδια"
        ModuleId.SCRIPTS -> "Οι Διάλογοι"
        ModuleId.SENTENCES -> "Οι Προτάσεις"
        ModuleId.TRACE -> "Τα γράμματα"
        ModuleId.ARCADE -> "Οι ασκήσεις για το δεξί χέρι"
    }

    fun percent(accuracy: Float): Int = (accuracy * 100).roundToInt()

    /**
     * The busiest of the modules that are going well: one line, about the one he has really done.
     *
     * Graded modules only, for the same reason the first-sound rule skips the board: every tap there
     * is written as CORRECT, so the talk board is always 100% and usually the busiest thing he does — it
     * would own this line for ever and push the module he actually worked at off the screen.
     */
    private fun bestModule(p: Progress): ModuleStat? =
        p.modules.filter { it.module in ProgressStats.GRADED_MODULES && it.attempts >= MODULE_ATTEMPTS && it.accuracy >= MODULE_ACCURACY }
            // Ties go to the module listed first, so the same week always names the same one.
            .maxWithOrNull(compareBy<ModuleStat> { it.attempts }.thenByDescending { it.module.ordinal })

    /** Lower is better: the first week of the window against the last. */
    private fun needsLessHelp(p: Progress): Boolean {
        if (p.cueTrend.size < 2) return false
        return p.cueTrend.first().meanCue - p.cueTrend.last().meanCue >= CUE_DROP
    }

    /**
     * A first sound he is getting better at. The comparison is against the period of the same length
     * before this one, so the caller has to hand in attempts from further back than the window; when
     * it does not, there is no past to compare against and the rule simply stays quiet.
     */
    private fun improvedSound(p: Progress, attempts: List<Attempt>, items: Map<String, Item>): String? {
        val span = (p.to - p.from).coerceAtLeast(1)
        // Graded modules only. Every talk-board tap is written as CORRECT because it is him
        // speaking, so counting the board here would let "he used the board more this month" arrive
        // on the dashboard as "the «π» words got better" — the one thing this rule must never say.
        val live = attempts.filter {
            !it.deleted && it.module in ProgressStats.GRADED_MODULES && items[it.itemId]?.firstSound?.isNotBlank() == true
        }
        val now = live.filter { it.startedAt in p.from..p.to }.groupBy { items.getValue(it.itemId).firstSound }
        val before = live.filter { it.startedAt >= p.from - span && it.startedAt < p.from }
            .groupBy { items.getValue(it.itemId).firstSound }
        return now.mapNotNull { (sound, rows) ->
            val past = before[sound]
            if (rows.size < SOUND_ATTEMPTS || past.isNullOrEmpty()) return@mapNotNull null
            val rise = accuracy(rows) - accuracy(past)
            if (rise >= SOUND_RISE) sound to rise else null
        }.maxWithOrNull(compareBy<Pair<String, Float>> { it.second }.thenByDescending { it.first })?.first
    }

    private fun accuracy(rows: List<Attempt>): Float =
        if (rows.isEmpty()) 0f else rows.count { it.outcome == Outcome.CORRECT }.toFloat() / rows.size

    /** The three he skips most, when there really are three of them. */
    private fun hardWords(p: Progress): List<String>? {
        val stuck = p.mostSkipped.filter { it.second >= SKIPS }
        return if (stuck.size >= HARD_WORDS) stuck.take(HARD_WORDS).map { it.first } else null
    }

    /**
     * Days since the last day he did anything. Counted by position in [Progress.days], which is one
     * row per calendar day, so a clock change cannot add or lose a day.
     */
    private fun idleDays(p: Progress): Int? {
        if (p.days.isEmpty()) return null
        val last = p.days.indexOfLast { it.attempts > 0 }
        val idle = if (last < 0) p.days.size else p.days.lastIndex - last
        return if (idle >= IDLE_DAYS) idle else null
    }
}
