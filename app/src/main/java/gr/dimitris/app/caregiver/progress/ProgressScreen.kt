package gr.dimitris.app.caregiver.progress

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.trace.TraceViewModel
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

private const val WEEK = 7
private val stepperWidth = 96.dp
private val greek: Locale = Locale.forLanguageTag("el")

/** What a section says when there is nothing in it yet. Never a scolding, never a zero. */
private const val NOTHING = "Τίποτα ακόμα."

/**
 * The caregiver dashboard: how much, how often, how well, how much help, and what the app itself
 * makes of it. Everything on it is read-only except the three levels at the bottom, which are the
 * one thing a caregiver may want to change the moment they see the numbers.
 */
@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val vm: ProgressViewModel = viewModel { ProgressViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    // The module titles as the modules themselves say them, plus the talk board, which is not one.
    val names = remember(graph) {
        graph.modules.associate { it.id to it.titleGreek } + (ModuleId.TALKBOARD to "Μίλα")
    }
    val levels = listOf(
        "Αριθμοί" to state.numbersLevel,
        "Προτάσεις" to state.sentencesLevel,
        "Γράψε" to state.traceLevel,
    )

    DimitrisScreen(
        title = "Πρόοδος",
        onBack = onBack,
        bottom = {
            // Task 3 wires this up. Until a key is saved there is nothing to ask with, and a button
            // that fails silently is worse than one that says why it cannot.
            BigButton("Ρώτα τον Claude", onClick = {}, enabled = false, icon = Icons.Rounded.AutoAwesome)
            Text(
                "Βάλε κλειδί στις ρυθμίσεις",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton("Εξαγωγή αναφοράς", icon = Icons.Rounded.Share, onClick = {
                val p = state.progress ?: return@QuietButton
                val text = report(p, state.insights, names, levels, zone)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Πρόοδος — Δημήτρης")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "Εξαγωγή αναφοράς")) }
                    .onFailure { graph.errors.record("progress share", it) }
            })
        },
    ) {
        val p = state.progress
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (p == null) {
                Text(if (state.loading) "Υπολογίζω…" else "Δεν μπόρεσα να διαβάσω την πρόοδο.",
                    style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            val week = p.days.takeLast(WEEK)
            Section("Αυτή την εβδομάδα")
            BarChart(
                values = week.map { it.minutes.toFloat() },
                labels = week.map { dayLabel(it.day, zone) },
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(minutesLine(week.sumOf { it.minutes }), style = MaterialTheme.typography.bodyLarge)
            Text(streakLine(p.streakDays), style = MaterialTheme.typography.bodyLarge)

            Section("Ανά άσκηση")
            if (p.modules.isEmpty()) Text(NOTHING, style = MaterialTheme.typography.bodyLarge)
            p.modules.forEach { m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
                ) {
                    Text(names[m.module] ?: m.module.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(attemptsLine(m.attempts), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${(m.accuracy * 100).roundToInt()}% σωστά",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("Πόση βοήθεια")
            if (p.cueTrend.isEmpty()) {
                Text(NOTHING, style = MaterialTheme.typography.bodyLarge)
            } else {
                BarChart(
                    values = p.cueTrend.map { it.meanCue },
                    labels = p.cueTrend.map { dateLabel(it.weekStart, zone) },
                )
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    "Λιγότερο = καλύτερα",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Μαθημένες λέξεις: ${p.mastered}")

            Section("Δύσκολες λέξεις")
            WordList(p.mostSkipped.take(5))

            Section("Στον πίνακα λέει πιο συχνά")
            WordList(p.mostUsedTalk.take(5))

            Section("Τι βλέπω")
            if (state.insights.isEmpty()) {
                Text("Τίποτα ιδιαίτερο αυτή την εβδομάδα.", style = MaterialTheme.typography.bodyLarge)
            }
            state.insights.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
                )
            }

            Section("Επίπεδα")
            LevelStepper("Αριθμοί", "numbers", state.numbersLevel, NumberProgression.MIN_LEVEL, NumberProgression.MAX_LEVEL, vm::setNumbersLevel)
            LevelStepper("Προτάσεις", "sentences", state.sentencesLevel, SentenceTemplates.MIN_LEVEL, SentenceTemplates.MAX_LEVEL, vm::setSentencesLevel)
            LevelStepper("Γράψε", "trace", state.traceLevel, TraceViewModel.MIN_LEVEL, TraceViewModel.MAX_LEVEL, vm::setTraceLevel)
            Spacer(Modifier.height(Sizes.gap))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(Sizes.gap))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(Sizes.gapSmall))
}

@Composable
private fun WordList(words: List<Pair<String, Int>>) {
    if (words.isEmpty()) {
        Text(NOTHING, style = MaterialTheme.typography.bodyLarge)
        return
    }
    words.forEach { (text, count) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(timesLine(count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** − value + , all of it a thumb's width, because a caregiver changes this standing in a kitchen. */
@Composable
private fun LevelStepper(label: String, tag: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Text("$label ($min–$max)", style = MaterialTheme.typography.bodyLarge)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
    ) {
        QuietButton("−", onClick = { onChange(value - 1) }, enabled = value > min,
            modifier = Modifier.width(stepperWidth).semantics { testTag = "minus-$tag" })
        Text(
            "$value",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { testTag = "level-$tag" },
        )
        QuietButton("+", onClick = { onChange(value + 1) }, enabled = value < max,
            modifier = Modifier.width(stepperWidth).semantics { testTag = "plus-$tag" })
    }
    Spacer(Modifier.height(Sizes.gapSmall))
}

/** Greek counts one thing in the singular, and his numbers are worth being right about. */
internal fun minutesLine(minutes: Int): String = if (minutes == 1) "1 λεπτό" else "$minutes λεπτά"

internal fun attemptsLine(attempts: Int): String = if (attempts == 1) "1 άσκηση" else "$attempts ασκήσεις"

internal fun timesLine(times: Int): String = if (times == 1) "1 φορά" else "$times φορές"

internal fun streakLine(days: Int): String = when (days) {
    0 -> "Χωρίς σερί αυτή τη στιγμή."
    1 -> "Σερί: 1 μέρα"
    else -> "Σερί: $days μέρες"
}

private fun dayLabel(day: Long, zone: ZoneId): String =
    when (Instant.ofEpochMilli(day).atZone(zone).dayOfWeek) {
        DayOfWeek.MONDAY -> "Δε"
        DayOfWeek.TUESDAY -> "Τρ"
        DayOfWeek.WEDNESDAY -> "Τε"
        DayOfWeek.THURSDAY -> "Πέ"
        DayOfWeek.FRIDAY -> "Πα"
        DayOfWeek.SATURDAY -> "Σά"
        DayOfWeek.SUNDAY -> "Κυ"
    }

private fun dateLabel(at: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return "${d.dayOfMonth}/${d.monthValue}"
}

/**
 * The shared report. Plain Greek text, no pictures, no recordings, no file paths — it goes out
 * through whatever app the caregiver picks, so it carries nothing that is not already words.
 *
 * Task 3 replaces this with `AdviceSummary.build`, which is the same text with the sections the
 * advisor's prompt expects.
 */
internal fun report(
    p: Progress,
    insights: List<String>,
    names: Map<ModuleId, String>,
    levels: List<Pair<String, Int>>,
    zone: ZoneId,
): String = buildString {
    appendLine("Πρόοδος — Δημήτρης")
    appendLine("Περίοδος: ${fullDate(p.from, zone)} – ${fullDate(p.to, zone)}")
    appendLine()
    appendLine("Σύνολο: ${minutesLine(p.days.sumOf { it.minutes })}, ${attemptsLine(p.days.sumOf { it.attempts })}")
    appendLine(streakLine(p.streakDays))
    appendLine("Μαθημένες λέξεις: ${p.mastered}")
    appendLine()
    appendLine("Ανά άσκηση")
    if (p.modules.isEmpty()) appendLine("- $NOTHING")
    p.modules.forEach { m ->
        appendLine(
            "- ${names[m.module] ?: m.module.name}: ${attemptsLine(m.attempts)}, ${(m.accuracy * 100).roundToInt()}% σωστά " +
                "(σωστά ${m.correct}, με βοήθεια ${m.assisted}, προσπέρασε ${m.skipped})"
        )
    }
    appendLine()
    appendLine("Πόση βοήθεια ανά εβδομάδα (λιγότερο = καλύτερα)")
    if (p.cueTrend.isEmpty()) appendLine("- $NOTHING")
    p.cueTrend.forEach { w -> appendLine("- ${dateLabel(w.weekStart, zone)}: ${String.format(greek, "%.1f", w.meanCue)}") }
    appendLine()
    appendLine("Δύσκολες λέξεις")
    if (p.mostSkipped.isEmpty()) appendLine("- $NOTHING")
    p.mostSkipped.forEach { (text, n) -> appendLine("- $text: ${timesLine(n)}") }
    appendLine()
    appendLine("Στον πίνακα λέει πιο συχνά")
    if (p.mostUsedTalk.isEmpty()) appendLine("- $NOTHING")
    p.mostUsedTalk.forEach { (text, n) -> appendLine("- $text: ${timesLine(n)}") }
    appendLine()
    appendLine("Επίπεδα")
    levels.forEach { (label, value) -> appendLine("- $label: $value") }
    appendLine()
    appendLine("Τι βλέπω")
    if (insights.isEmpty()) appendLine("- $NOTHING")
    insights.forEach { appendLine("- $it") }
}

private fun fullDate(at: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return "${d.dayOfMonth}/${d.monthValue}/${d.year}"
}
