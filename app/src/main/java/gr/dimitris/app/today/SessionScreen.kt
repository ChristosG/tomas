package gr.dimitris.app.today

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun SessionScreen(onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    // Leaving the session stops whatever it was saying or playing.
    DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }

    val vm: SessionViewModel = viewModel { SessionViewModel(graph) }
    val step by vm.state.collectAsStateWithLifecycle()

    when (val s = step) {
        SessionStep.Loading -> DimitrisScreen { Text("Ετοιμάζω τη σημερινή άσκηση...", style = MaterialTheme.typography.headlineMedium) }
        SessionStep.Empty -> DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            Text("Τίποτα για σήμερα. Τα λέμε αύριο!", style = MaterialTheme.typography.headlineMedium)
        }
        is SessionStep.Run -> key(s.index) {
            s.module.Screen(items = s.items, sessionId = s.sessionId, onDone = vm::moduleDone, onLeave = vm::leaveSession)
        }
        is SessionStep.Summary -> DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            if (s.completed > 0) {
                SuccessMark(visible = true)
                Spacer(Modifier.height(Sizes.gap))
                Text("Μπράβο Δημήτρη!", style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(Sizes.gapSmall))
                Text("Έκανες ${s.completed} ασκήσεις σήμερα.", style = MaterialTheme.typography.bodyLarge)
            } else {
                // No Μπράβο for a session he walked away from: an invitation, not a score.
                Text(SessionViewModel.NOTHING_DONE, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
