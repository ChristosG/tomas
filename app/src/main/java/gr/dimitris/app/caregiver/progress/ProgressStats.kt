package gr.dimitris.app.caregiver.progress

import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.scheduler.LeitnerPolicy
import gr.dimitris.app.core.scheduler.startOfDay
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
    ): Progress {
        val window = attempts.filter { !it.deleted && it.startedAt in from..to }
        val sat = sessions.filter { !it.deleted && it.startedAt in from..to }

        val dayKeys = calendarDays(from, to, zone)
        val attemptsPerDay = window.groupingBy { startOfDay(it.startedAt, zone) }.eachCount()
        val msPerDay = mutableMapOf<Long, Long>()
        sat.forEach { s ->
            val length = ((s.endedAt ?: s.startedAt) - s.startedAt).coerceIn(0L, MAX_SESSION_MS)
            val key = startOfDay(s.startedAt, zone)
            msPerDay[key] = (msPerDay[key] ?: 0L) + length
        }
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
            // One word learned is one word, even when it is learned in three modules.
            mastered = schedules.filter { !it.deleted && it.box >= LeitnerPolicy.MAX_BOX }.map { it.itemId }.distinct().size,
            mostSkipped = byText(window.filter { it.outcome == Outcome.SKIPPED }, items),
            mostUsedTalk = byText(window.filter { it.module == ModuleId.TALKBOARD }, items),
        )
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
