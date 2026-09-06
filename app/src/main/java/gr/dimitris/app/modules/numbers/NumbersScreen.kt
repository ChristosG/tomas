package gr.dimitris.app.modules.numbers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.LISTEN
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

/** [count] exercises, one per item the session budgeted for this module. */
@Composable
fun NumbersScreen(count: Int, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    // The count is part of the key: a resumed ViewModel would otherwise keep the old session's length.
    val vm: NumbersViewModel = viewModel(key = "numbers-${sessionId ?: "practice"}-$count") { NumbersViewModel(graph, sessionId, count) }
    val s by vm.state.collectAsStateWithLifecycle()

    /**
     * A mixed session ends with its own summary, so a second "well done" screen in the middle of it
     * is one tap of noise: the module hands straight back. Free practice keeps the end screen — it is
     * the only ending there is — and so does a level change, whichever it is, because the level is
     * announced on it and he has to be able to read and hear it before it goes.
     */
    val endScreen = sessionId == null || s.levelChanged != null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με τους αριθμούς!", style = MaterialTheme.typography.headlineMedium)
            if (s.levelChanged != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(if (s.levelChanged!! > s.level) "Ανεβαίνεις στο επίπεδο ${s.levelChanged}. Μπράβο!" else "Πάμε λίγο πιο εύκολα: επίπεδο ${s.levelChanged}.", style = MaterialTheme.typography.bodyLarge)
            }
            // The end screen speaks too, so a silent phone has to be said here as well.
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val e = s.exercise
    DimitrisScreen(
        title = "Αριθμοί ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // Answered, or shown after two misses: either way the question is over and the only way
            // on is «Επόμενο». Green only when he found it himself.
            if (s.correct == true) BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            else if (s.revealed) BigButton("Επόμενο", onClick = vm::next)
            else QuietButton("Παράλειψη", onClick = vm::skip)
        },
    ) {
        if (e == null) { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium); return@DimitrisScreen }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(e.prompt, style = MaterialTheme.typography.headlineMedium)
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            // The same one word as the four speech modules: «Άκου ξανά» reads as a second chance,
            // and hearing the question again is not one — it is the question, said again.
            QuietButton(LISTEN, onClick = vm::speakPrompt, icon = Icons.Rounded.VolumeUp)
            // Above the options, not under them: eleven buttons below it would put the tick he is
            // owed for a right answer off the bottom of the screen, and the icon is one of the three
            // things a success is made of.
            SuccessMark(visible = s.correct == true, modifier = Modifier.fillMaxWidth())
            // A miss says so in writing as well as out loud: the sound may be off, or missed.
            if (s.correct == false) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    if (s.revealed) "Να το σωστό." else "Ξανά.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(Sizes.gap))
            when (e) {
                is NumberExercise.Compare -> CompareView(e, s, vm::choose)
                is NumberExercise.NumberLine -> NumberLineView(e, s, vm::choose)
                is NumberExercise.Count -> CountView(e, s, vm::tapObject, vm::choose)
                is NumberExercise.WordMatch -> WordMatchView(e, s, vm::choose)
                is NumberExercise.CoinPick -> CoinPickView(e, s, vm::choose)
                is NumberExercise.PriceCompare -> PriceCompareView(e, s, vm::choose)
                is NumberExercise.Pay -> PayView(e, s, vm::choose)
            }
            Spacer(Modifier.height(Sizes.gap))
        }
    }
}
