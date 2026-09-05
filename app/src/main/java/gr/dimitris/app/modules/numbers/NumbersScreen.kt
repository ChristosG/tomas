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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun NumbersScreen(sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: NumbersViewModel = viewModel(key = "numbers-${sessionId ?: "practice"}") { NumbersViewModel(graph, sessionId) }
    val s by vm.state.collectAsStateWithLifecycle()

    if (s.done) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με τους αριθμούς!", style = MaterialTheme.typography.headlineMedium)
            if (s.levelChanged != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(if (s.levelChanged!! > s.level) "Ανεβαίνεις στο επίπεδο ${s.levelChanged}. Μπράβο!" else "Πάμε λίγο πιο εύκολα: επίπεδο ${s.levelChanged}.", style = MaterialTheme.typography.bodyLarge)
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
            if (s.correct == true) BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
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
            QuietButton("Άκου ξανά", onClick = vm::speakPrompt, icon = Icons.Rounded.VolumeUp)
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
            SuccessMark(visible = s.correct == true, modifier = Modifier.fillMaxWidth())
        }
    }
}
