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
    // Leaving the session stops whatever it was saying or playing — and closes the microphone, which
    // a module screen only does on a back press: navigating away with «Μίλα» would otherwise leave
    // the take running and the talk board refusing every tap while it does.
    DisposableEffect(Unit) {
        onDispose {
            if (graph.voice.isRecording) graph.voice.cancelRecording()
            graph.voice.quiet()
        }
    }

    val vm: SessionViewModel = viewModel { SessionViewModel(graph) }
    val step by vm.state.collectAsStateWithLifecycle()

    when (val s = step) {
        SessionStep.Loading -> DimitrisScreen { Text("Ετοιμάζω τη σημερινή άσκηση...", style = MaterialTheme.typography.headlineMedium) }
        is SessionStep.Empty -> DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            Text(
                if (s.allOff) SessionViewModel.ALL_MODULES_OFF else SessionViewModel.NOTHING_TODAY,
                style = MaterialTheme.typography.headlineMedium,
            )
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
                Text(SessionWording.summary(s.completed, s.titles), style = MaterialTheme.typography.bodyLarge)
            } else {
                // No Μπράβο for a session he walked away from: an invitation, not a score.
                Text(SessionWording.NOTHING_DONE, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
