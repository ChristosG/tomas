package gr.dimitris.app.caregiver.progress

import com.google.gson.JsonParser
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.scheduler.LeitnerPolicy
import gr.dimitris.app.core.scheduler.startOfDay
import gr.dimitris.app.today.SessionViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** One calendar day of the window. [day] is that day's midnight, in the caregiver's zone. */
data class DayStat(val day: Long, val minutes: Int, val attempts: Int)

/** What one module was in the window. [accuracy] is 0f..1f, and 0f when he never opened it. */
data class ModuleStat(val module: ModuleId, val attempts: Int, val correct: Int, val assisted: Int, val skipped: Int) {
    val accuracy: Float get() = if (attempts <= 0) 0f else correct.toFloat() / attempts
}

/** Mean cue level of one ISO week. Lower is better: it is how much help he needed. */
data class WeekCue(val weekStart: Long, val meanCue: Float)

/**
 * One word, from the first time he ever met it to the last. The unit of «Όλη η πορεία ανά λέξη».
 *
 * The house rule of this file decides which counter counts what: **how much** counts every module,
 * **how well** counts [ProgressStats.GRADED_MODULES] only. So [attempts] includes the talk board —
 * saying a word at the board is practice and belongs in his journey — while [correct], [assisted]
 * and [skipped] do not, because every tap on the board is written as CORRECT for the simple reason
 * that it is him speaking and not him being marked. The three therefore need not add up to
 * [attempts], and the report says so where it prints them.
 *
 * [meanCue] is over attempts that carry a cue level at all, which is the cue-ladder modules; it is
 * `null` when he has never been given a ladder for this word.
 *
 * No id, no path: an [ItemHistory] is what may leave the phone, so it holds nothing that could not
 * be read out loud in a room. [hasPhoto] and [hasVoice] are the two facts a caregiver can act on —
 * "this word has no picture yet" — and they are booleans, never file names.
 */
data class ItemHistory(
    val text: String,
    val kind: ItemKind,
    val category: Category,
    val firstSound: String,
    val attempts: Int,
    val correct: Int,
    val assisted: Int,
    val skipped: Int,
    /** How often he wrote this word in «Γράψε». His right hand, kept apart from his words. */
    val traced: Int,
    val meanCue: Float?,
    /** The highest Leitner box this word has reached in any module; 0 when it is not scheduled. */
    val box: Int,
    val firstAt: Long,
    val lastAt: Long,
    val hasPhoto: Boolean,
    val hasVoice: Boolean,
)

/** One word on one day: the detail under «Τελευταίες 4 εβδομάδες ανά ημέρα». */
data class DayItemStat(
    val day: Long,
    val text: String,
    val attempts: Int,
    val correct: Int,
    val assisted: Int,
    val skipped: Int,
    val meanCue: Float?,
)

/**
 * One module over the whole journey. The coarse view the per-word table cannot give: Αριθμοί,
 * Προτάσεις, Γράψε and Δεξί χέρι write synthetic item ids («numbers:level:3», «arcade:tap»), so
 * they are invisible to every list of words — and those are three of the four modules Claude is
 * asked whether to move a level on.
 *
 * [meanMs] is how long one exercise took him, which is the one thing in here that a level change
 * shows up in first. [levels] is the level he was actually working at, week by week, read from
 * `detail.level`, so "he has been at level 2 since June" is a fact rather than an inference.
 */
data class ModuleHistory(
    val module: ModuleId,
    val attempts: Int,
    val correct: Int,
    val assisted: Int,
    val skipped: Int,
    val meanMs: Long,
    val meanCue: Float?,
    val firstAt: Long,
    val lastAt: Long,
    val levels: List<WeekLevel>,
)

/** The level a module was worked at in one ISO week, averaged over that week's rows. */
data class WeekLevel(val weekStart: Long, val level: Float)

data class Progress(
    val from: Long,
    val to: Long,
    val days: List<DayStat>,
    val streakDays: Int,
    val modules: List<ModuleStat>,
    val cueTrend: List<WeekCue>,
    val mastered: Int,
    val mostSkipped: List<Pair<String, Int>>,
    val mostUsedTalk: List<Pair<String, Int>>,
)

/**
 * Everything the caregiver dashboard shows, from rows the app already writes. Pure: hand it lists
 * and it hands back numbers, so all of it can be argued with in a unit test.
 *
 * Two rules run through the whole file.
 *
 * An attempt whose `itemId` is not in [items] is *synthetic* — `numbers:level:3`, `arcade:tap`, a
 * word a caregiver has since deleted. It is a real exercise he really did, so it counts in its
 * module's row and in his day; it is simply not a word, so it can never appear in a list of words.
 * That is a lookup, not a string pattern: no module has to keep its id format in sync with this file.
 *
 * The lists handed in may be wider than [from]..[to] — the insight rules need the period before this
 * one to compare against — so everything here filters to the window itself first.
 */
object ProgressStats {
    /** Four weeks: long enough for a trend, short enough that a caregiver recognises the days. */
    const val DEFAULT_DAYS = 28

    /** A sitting is never more than an hour, whatever the row says: the phone was on the table. */
    const val MAX_SESSION_MS = 60L * 60 * 1000

    /** How many words a list of words shows. The screen shows fewer; the Claude summary sends these. */
    const val TOP_N = 10

    /** The three modules that run a cue ladder. In all of them a lower level means less help. */
    val CUE_MODULES = setOf(ModuleId.WORDCOACH, ModuleId.SCRIPTS, ModuleId.SINGSAY)

    /**
     * The modules whose outcome is a judgement about how he did. The talk board is not one of them:
     * every tap on it is written as [Outcome.CORRECT] because it is him speaking, not him being
     * marked, so an accuracy taken over the board is a count of button presses wearing a percentage
     * sign. Anything that says "how well" — the accuracy line, the first-sound insight — asks here
     * first; anything that says "how much" counts every module.
     */
    val GRADED_MODULES: Set<ModuleId> = ModuleId.entries.toSet() - ModuleId.TALKBOARD

    /**
     * What a *word* is judged on. [GRADED_MODULES] minus «Γράψε», because handwriting is not
     * vocabulary recall.
     *
     * From level 4 the tracing module writes real item ids, so a word he gave up *tracing* with a
     * hemiparetic right hand used to land in that word's «παράλειψη» beside his word-finding, and a
     * reader — a therapist or Claude — would take his hand for his aphasia and put the word in a
     * speech focus. [gr.dimitris.app.caregiver.insights.InsightRules] already refuses to make that
     * claim; the per-word lines now refuse it too, and «Γράψε» is reported whole in the report's own
     * per-module section instead, where it says something true.
     */
    val SPEECH_MODULES: Set<ModuleId> = GRADED_MODULES - ModuleId.TRACE

    /**
     * The one attempt row per sitting that is about the sitting rather than about an exercise
     * ([gr.dimitris.app.today.SessionViewModel.SESSION_SUMMARY]). Its `durationMs` is the whole
     * sitting and its outcome is a placeholder, so counting it would add a phantom exercise to his
     * day, to one module's row and to that module's mean duration. Nothing here counts it.
     */
    const val SUMMARY_ITEM = SessionViewModel.SESSION_SUMMARY

    /** Every row that is a real exercise: not the sitting's own summary row. */
    fun exercises(attempts: List<Attempt>): List<Attempt> =
        attempts.filter { !it.deleted && it.itemId != SUMMARY_ITEM }

    /**
     * What «Μαθημένες λέξεις» is allowed to count. A dialogue line is an item too, but it is a line
     * of a script, not a word he now has.
     */
    val WORD_KINDS = setOf(ItemKind.WORD, ItemKind.PHRASE)

    /** How many weeks of level history one module's line carries. A season, not a life. */
    const val LEVEL_WEEKS = 12

    /** Two attempts further apart than this are two sittings, not one with a pause in it. */
    const val SITTING_GAP_MS = 5L * 60 * 1000

    /** A sitting is never worth less than a minute, however fast he was. */
    const val MIN_SITTING_MS = 60L * 1000

    /** Midnight [days] days back, counting today as one of them. */
    fun from(now: Long, days: Int = DEFAULT_DAYS, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(startOfDay(now, zone)).atZone(zone).toLocalDate()
            .minusDays((days - 1).coerceAtLeast(0).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

    fun compute(
        attempts: List<Attempt>,
        sessions: List<Session>,
        schedules: List<Schedule>,
        items: Map<String, Item>,
        from: Long,
        to: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        /**
         * How many distinct words are in the last box. Counted here from [schedules] when it is
         * null, which is what the tests do; the dashboard passes
         * [gr.dimitris.app.core.data.ScheduleDao.masteredCount] instead and hands in no schedules at
         * all, because reading items × modules rows into memory to count them is work SQLite does
         * better. The rule is the same either way: distinct items, not rows.
         */
        mastered: Int? = null,
    ): Progress {
        val window = exercises(attempts).filter { it.startedAt in from..to }
        val sat = sessions.filter { !it.deleted && it.startedAt in from..to }

        val dayKeys = calendarDays(from, to, zone)
        val attemptsPerDay = window.groupingBy { startOfDay(it.startedAt, zone) }.eachCount()
        val msPerDay = mutableMapOf<Long, Long>()
        sat.forEach { s ->
            val length = ((s.endedAt ?: s.startedAt) - s.startedAt).coerceIn(0L, MAX_SESSION_MS)
            val key = startOfDay(s.startedAt, zone)
            msPerDay[key] = (msPerDay[key] ?: 0L) + length
        }
        // The rest of his practice, which writes no session row at all: a module opened from the
        // Today grid, and the talk board. Without this, twenty minutes of word coach read as
        // «0 λεπτά» on the screen and went to Claude as the premise for a week's advice.
        sittings(window, zone).forEach { (day, ms) -> msPerDay[day] = (msPerDay[day] ?: 0L) + ms }
        // Summed first and rounded once: three short sittings are a quarter of an hour, not zero.
        val days = dayKeys.map { d ->
            DayStat(d, minutes = ((msPerDay[d] ?: 0L) / 60_000.0).roundToInt(), attempts = attemptsPerDay[d] ?: 0)
        }

        val busy = days.filter { it.attempts > 0 }.map { it.day }.toSet()
        val modules = ModuleId.entries.mapNotNull { id ->
            val rows = window.filter { it.module == id }
            if (rows.isEmpty()) null else ModuleStat(
                module = id,
                attempts = rows.size,
                correct = rows.count { it.outcome == Outcome.CORRECT },
                assisted = rows.count { it.outcome == Outcome.ASSISTED },
                skipped = rows.count { it.outcome == Outcome.SKIPPED },
            )
        }

        val cueTrend = window.filter { it.module in CUE_MODULES && it.cueLevel != null }
            .groupBy { weekStart(it.startedAt, zone) }
            .map { (week, rows) -> WeekCue(week, rows.sumOf { it.cueLevel ?: 0 }.toFloat() / rows.size) }
            .sortedBy { it.weekStart }

        return Progress(
            from = from,
            to = to,
            days = days,
            streakDays = streak(busy, startOfDay(to, zone), zone),
            modules = modules,
            cueTrend = cueTrend,
            // One word learned is one word, even when it is learned in three modules — and a
            // schedule row only counts when it points at a live word. Script practice schedules
            // against the *script's* id, so without the [items] membership test a mastered dialogue
            // arrived here as a mastered word.
            mastered = mastered ?: schedules
                .filter { !it.deleted && it.box >= LeitnerPolicy.MAX_BOX && items[it.itemId]?.kind in WORD_KINDS }
                .map { it.itemId }.distinct().size,
            // Cue-ladder modules only. «Δύσκολες λέξεις» carries the advice «Δοκίμασε φωτογραφία ή
            // φωνή», which is about finding a word; a word he skipped *tracing* at level 4 is a
            // statement about his right hand, and it writes a real item id, so nothing else keeps
            // it out of this list.
            mostSkipped = byText(window.filter { it.outcome == Outcome.SKIPPED && it.module in CUE_MODULES }, items),
            mostUsedTalk = byText(window.filter { it.module == ModuleId.TALKBOARD }, items),
        )
    }

    /**
     * Every word he has ever practised, once each, busiest first.
     *
     * This is the part of the report no window can give: a word he was stuck on in March and has
     * not been offered since is exactly the kind of thing the advisor should be able to notice, and
     * four weeks of history cannot show it. [attempts] may reach back years — [ItemDao] rows are
     * the vocabulary as it stands *today*, so a word a caregiver deleted simply drops out of the
     * list rather than arriving as a bare id, which is the same rule the dashboard's word lists
     * follow.
     *
     * Ties are broken by the most recent practice and then alphabetically, so the same history
     * always produces the same lines and a diff of two reports is readable.
     */
    fun lifetime(
        attempts: List<Attempt>,
        schedules: List<Schedule>,
        items: Map<String, Item>,
        recordings: Set<String> = emptySet(),
    ): List<ItemHistory> {
        val topBox = schedules.filter { !it.deleted }
            .groupBy { it.itemId }.mapValues { (_, rows) -> rows.maxOf { it.box } }
        return exercises(attempts).filter { items[it.itemId] != null }
            .groupBy { it.itemId }
            .mapNotNull { (id, rows) ->
                val item = items[id] ?: return@mapNotNull null
                // Speech only: «Γράψε» is his right hand, and it is counted on its own below.
                val graded = rows.filter { it.module in SPEECH_MODULES }
                val cued = rows.mapNotNull { it.cueLevel }
                ItemHistory(
                    text = item.text,
                    kind = item.kind,
                    category = item.category,
                    firstSound = item.firstSound,
                    attempts = rows.size,
                    correct = graded.count { it.outcome == Outcome.CORRECT },
                    assisted = graded.count { it.outcome == Outcome.ASSISTED },
                    skipped = graded.count { it.outcome == Outcome.SKIPPED },
                    traced = rows.count { it.module == ModuleId.TRACE },
                    meanCue = if (cued.isEmpty()) null else cued.sum().toFloat() / cued.size,
                    box = topBox[id] ?: 0,
                    firstAt = rows.minOf { it.startedAt },
                    lastAt = rows.maxOf { it.startedAt },
                    hasPhoto = !item.imagePath.isNullOrBlank(),
                    // The item's own model voice, or any recording made against it: both are "there
                    // is a voice for this word", which is the only thing a caregiver acts on.
                    hasVoice = !item.modelRecordingId.isNullOrBlank() || id in recordings,
                )
            }
            .sortedWith(
                compareByDescending<ItemHistory> { it.attempts }
                    .thenByDescending { it.lastAt }
                    .thenBy { it.text }
            )
    }

    /**
     * The window, word by word and day by day: what he actually did each morning, not a monthly
     * average of it. Days he did nothing are simply absent — [Progress.days] already carries the
     * empty ones, and repeating them here would be four hundred lines of «τίποτα».
     *
     * Ordered oldest day first, and within a day busiest word first, because that is how the report
     * reads: a month walked forwards.
     */
    fun recentByDay(
        attempts: List<Attempt>,
        items: Map<String, Item>,
        from: Long,
        to: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayItemStat> =
        exercises(attempts).filter { it.startedAt in from..to && items[it.itemId] != null }
            // Grouped by *text*: two live items spelled the same are one word to everybody who
            // reads this, and a day that listed «καφές» twice would read as a bug rather than as
            // two rows in the vocabulary.
            .groupBy { startOfDay(it.startedAt, zone) to items.getValue(it.itemId).text }
            .map { (key, rows) ->
                val graded = rows.filter { it.module in SPEECH_MODULES }
                val cued = rows.mapNotNull { it.cueLevel }
                DayItemStat(
                    day = key.first,
                    text = key.second,
                    attempts = rows.size,
                    correct = graded.count { it.outcome == Outcome.CORRECT },
                    assisted = graded.count { it.outcome == Outcome.ASSISTED },
                    skipped = graded.count { it.outcome == Outcome.SKIPPED },
                    meanCue = if (cued.isEmpty()) null else cued.sum().toFloat() / cued.size,
                )
            }
            .sortedWith(compareBy<DayItemStat> { it.day }.thenByDescending { it.attempts }.thenBy { it.text })

    /**
     * Every module over the whole journey, in the enum's own order so two reports line up.
     *
     * Unlike everything else in this file it counts **synthetic ids too** — `numbers:level:3`,
     * `arcade:tap`, the sentence builder's own rows — because that is the whole point: Αριθμοί,
     * Προτάσεις, Γράψε and Δεξί χέρι never appear in a list of words, and three of them are the
     * modules whose level Claude is asked to move. Only the sitting's own summary row is left out;
     * it is not an exercise.
     *
     * [ModuleHistory.levels] is read out of `detail.level`, which the three levelled modules write
     * (phase 11, `docs/ADAPTATION.md`). It is a mean over the week rather than the last value: a
     * week that straddles a level change should read as the half-step it was.
     */
    fun moduleHistory(attempts: List<Attempt>, weeks: Int = LEVEL_WEEKS, zone: ZoneId = ZoneId.systemDefault()): List<ModuleHistory> {
        val rows = exercises(attempts)
        if (rows.isEmpty()) return emptyList()
        val byModule = rows.groupBy { it.module }
        return ModuleId.entries.mapNotNull { id ->
            val mine = byModule[id] ?: return@mapNotNull null
            val graded = if (id in GRADED_MODULES) mine else emptyList()
            val cued = mine.mapNotNull { it.cueLevel }
            val levels = mine.mapNotNull { row -> levelOf(row)?.let { weekStart(row.startedAt, zone) to it } }
                .groupBy({ it.first }, { it.second })
                .map { (week, values) -> WeekLevel(week, values.sum().toFloat() / values.size) }
                .sortedBy { it.weekStart }
                .takeLast(weeks.coerceAtLeast(0))
            ModuleHistory(
                module = id,
                attempts = mine.size,
                correct = graded.count { it.outcome == Outcome.CORRECT },
                assisted = graded.count { it.outcome == Outcome.ASSISTED },
                skipped = graded.count { it.outcome == Outcome.SKIPPED },
                meanMs = mine.sumOf { it.durationMs } / mine.size,
                meanCue = if (cued.isEmpty()) null else cued.sum().toFloat() / cued.size,
                firstAt = mine.minOf { it.startedAt },
                lastAt = mine.maxOf { it.startedAt },
                levels = levels,
            )
        }
    }

    /**
     * How many of a day's exercises had no word behind them, and which modules they were.
     *
     * Without this the day header does not add up to the lines under it — «12 ασκήσεις» over five
     * word lines totalling five — and a reader who decides a table is unreliable stops using it.
     * The modules are read from the rows rather than listed as a constant, because which of them
     * writes a word depends on the level he is at that day.
     */
    fun wordlessByDay(
        attempts: List<Attempt>,
        items: Map<String, Item>,
        from: Long,
        to: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<Long, Map<ModuleId, Int>> =
        exercises(attempts).filter { it.startedAt in from..to && items[it.itemId] == null }
            .groupBy { startOfDay(it.startedAt, zone) }
            .mapValues { (_, rows) -> rows.groupingBy { it.module }.eachCount().toSortedMap(compareBy { it.ordinal }) }

    /** `detail.level` as a number, or null when this module does not write one. */
    private fun levelOf(row: Attempt): Int? = runCatching {
        JsonParser.parseString(row.detail).takeIf { it.isJsonObject }?.asJsonObject
            ?.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    }.getOrNull()

    /**
     * Minutes he practised without a session row, per day.
     *
     * Only one screen in the app writes a [Session]: the Today session. A module opened from the
     * Today grid and every tap on the talk board write attempts with no `sessionId`, and those are
     * real minutes of his life — so they are reconstructed from the attempts themselves. Attempts
     * closer together than [SITTING_GAP_MS] are one sitting; the sitting is as long as it spans,
     * never less than [MIN_SITTING_MS] (one tap is a minute of standing at the board, not nothing)
     * and never more than [MAX_SESSION_MS] (the phone was on the table). A sitting belongs to the
     * day it began, exactly as a session does, so nothing is split across a midnight.
     *
     * Attempts that *do* carry a `sessionId` are left out: their minutes are already in the session.
     */
    private fun sittings(window: List<Attempt>, zone: ZoneId): Map<Long, Long> {
        val loose = window.filter { it.sessionId == null }.map { it.startedAt }.sorted()
        if (loose.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, Long>()
        var first = loose.first()
        var last = first
        fun close() {
            val ms = (last - first).coerceIn(MIN_SITTING_MS, MAX_SESSION_MS)
            val key = startOfDay(first, zone)
            out[key] = (out[key] ?: 0L) + ms
        }
        loose.drop(1).forEach { at ->
            if (at - last < SITTING_GAP_MS) {
                last = at
            } else {
                close()
                first = at
                last = at
            }
        }
        close()
        return out
    }

    /** Consecutive days with at least one attempt, ending today — or yesterday, when today is young. */
    private fun streak(busy: Set<Long>, today: Long, zone: ZoneId): Int {
        var cursor = when {
            today in busy -> today
            previousDay(today, zone) in busy -> previousDay(today, zone)
            else -> return 0
        }
        var n = 0
        while (cursor in busy) {
            n++
            cursor = previousDay(cursor, zone)
        }
        return n
    }

    /** Commonest first, then alphabetical so two words with the same count keep a fixed order. */
    private fun byText(rows: List<Attempt>, items: Map<String, Item>): List<Pair<String, Int>> =
        rows.mapNotNull { items[it.itemId]?.text }
            .groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(TOP_N)

    /** Every midnight from [from]'s day to [to]'s day, so a quiet day is a gap on the chart, not a missing bar. */
    private fun calendarDays(from: Long, to: Long, zone: ZoneId): List<Long> {
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        if (last.isBefore(first)) return emptyList()
        val out = mutableListOf<Long>()
        var d = first
        // A guard, not a policy: a window measured in years is a bug somewhere, not a dashboard.
        while (!d.isAfter(last) && out.size < 400) {
            out += d.atStartOfDay(zone).toInstant().toEpochMilli()
            d = d.plusDays(1)
        }
        return out
    }

    private fun previousDay(day: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(day).atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The Monday of that week, ISO. */
    private fun weekStart(at: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return date.minusDays((date.dayOfWeek.value - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
