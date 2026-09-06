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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import gr.dimitris.app.caregiver.insights.journeyReport
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.trace.TraceViewModel
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

private const val WEEK = 7

/** What every section below «Αυτή την εβδομάδα» actually covers. */
private const val WINDOW = "Τελευταίες 4 εβδομάδες"
private val stepperWidth = 96.dp

/** What a section says when there is nothing in it yet. Never a scolding, never a zero. */
private const val NOTHING = "Τίποτα ακόμα."

/**
 * The caregiver dashboard: how much, how often, how well, how much help, and what the app itself
 * makes of it. Everything on it is read-only except the three levels at the bottom, which are the
 * one thing a caregiver may want to change the moment they see the numbers.
 */
@Composable
fun ProgressScreen(onBack: () -> Unit, onAdvice: () -> Unit = {}) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val vm: ProgressViewModel = viewModel { ProgressViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val feedback = LocalFeedback.current
    var confirmShare by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    // The module titles as the modules themselves say them, plus the talk board, which is not one.
    val names = remember(graph) {
        graph.modules.associate { it.id to it.titleGreek } + (ModuleId.TALKBOARD to "Μίλα")
    }

    DimitrisScreen(
        title = "Πρόοδος",
        onBack = onBack,
        bottom = {
            // The advice screen says the rest: it shows the summary, asks for it and reads the
            // answer out. Disabled until there is something to summarise, which is also the only
            // state in which asking could say anything at all.
            BigButton(
                "Ρώτα τον Claude",
                onClick = onAdvice,
                enabled = state.progress != null,
                icon = Icons.Rounded.AutoAwesome,
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            // The same text «Ρώτα τον Claude» sends, so a caregiver forwarding it to a speech
            // therapist and a caregiver asking Claude are talking about the same thing — which
            // means it also carries the caregivers' own notes, and this screen has no «Τι θα
            // σταλεί» section to read them in. Hence the confirmation: nobody should hand the
            // other caregiver's notes about the dentist to a physiotherapist by tapping «Εξαγωγή».
            QuietButton(
                if (sharing) "Ετοιμάζω την αναφορά…" else "Εξαγωγή αναφοράς",
                enabled = state.progress != null && !sharing,
                icon = Icons.Rounded.Share,
                onClick = { confirmShare = true },
                modifier = Modifier.semantics { testTag = "share-report" },
            )
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
            // Everything from here down is the whole window, not the seven days charted above. The
            // caption is the difference between "he did sixty word exercises this week" and the truth.
            Caption(WINDOW)
            if (p.modules.isEmpty()) Text(NOTHING, style = MaterialTheme.typography.bodyLarge)
            p.modules.forEach { m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
                ) {
                    Text(names[m.module] ?: m.module.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(attemptsLine(m.attempts), style = MaterialTheme.typography.bodyMedium)
                        // No percentage for the talk board: every tap on it is written as CORRECT
                        // because it is him talking, so «100% σωστά» would be a count of taps read as
                        // a therapy score — next to «Λέξεις 62% σωστά» that is a false comparison.
                        Text(
                            if (m.module in ProgressStats.GRADED_MODULES) "${(m.accuracy * 100).roundToInt()}% σωστά" else "πίνακας",
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

            // Lifetime, not the window: it is the one number on this screen that is not four weeks.
            Section("Μαθημένες λέξεις συνολικά: ${p.mastered}")

            Section("Δύσκολες λέξεις")
            WordList(p.mostSkipped.take(5))

            Section("Στον πίνακα λέει πιο συχνά")
            WordList(p.mostUsedTalk.take(5))

            Section("Τι βλέπω")
            Caption(WINDOW)
            if (state.insights.isEmpty()) {
                Text("Τίποτα ιδιαίτερο αυτές τις 4 εβδομάδες.", style = MaterialTheme.typography.bodyLarge)
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

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text("Εξαγωγή αναφοράς;") },
            text = { Text(SHARE_WARNING) },
            confirmButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = {
                    feedback.tap()
                    confirmShare = false
                    sharing = true
                    // The application scope, not the composition's: the report reads every attempt
                    // ever recorded, and a caregiver who backs out while it builds should not be
                    // left with a chooser that never opens and nothing said about why. The chooser
                    // carries FLAG_ACTIVITY_NEW_TASK, so the application context can start it.
                    val app = context.applicationContext
                    graph.scope.launch {
                        try {
                            val text = journeyReport(graph, zone = zone)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Πορεία — Δημήτρης")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            val chooser = Intent.createChooser(send, "Εξαγωγή αναφοράς")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { app.startActivity(chooser) }
                                .onFailure { graph.errors.record("progress share", it) }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Throwable) {
                            graph.errors.record("progress share", e)
                        } finally {
                            sharing = false
                        }
                    }
                }) { Text("Μοιράσου", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(modifier = Modifier.heightIn(min = Sizes.touchMin), onClick = {
                    feedback.tap(); confirmShare = false
                }) { Text("Άκυρο", style = MaterialTheme.typography.labelLarge) }
            },
        )
    }
}

/** What «Εξαγωγή αναφοράς» hands over. Said before it happens, because it cannot be taken back. */
internal const val SHARE_WARNING = "Θα μοιραστείς την πλήρη αναφορά, μαζί με τις σημειώσεις σας."


/** The one line that says which period the numbers under it cover. */
@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Sizes.gapSmall))
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
