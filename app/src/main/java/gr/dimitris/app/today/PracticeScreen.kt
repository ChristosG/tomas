package gr.dimitris.app.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen

/**
 * Free practice of one module from the Today grid: same screen, no session row.
 *
 * [itemId] and [scriptId] come from the caregiver's «Δοκίμασέ το» / «Παίξ' το»: the sitting is then
 * that one word or that one dialogue, and [onDone] goes back to the editor she came from.
 */
@Composable
fun PracticeScreen(moduleId: ModuleId, onDone: () -> Unit, itemId: String? = null, scriptId: String? = null) {
    val graph = LocalAppGraph.current
    // Leaving practice stops whatever it was saying or playing, the same as leaving a session does.
    // The microphone too: the module screens hand the take back on *back*, but the «Μίλα» button in
    // the corner is a navigation, not a back press, and a take left running there mutes the talk
    // board he has just gone to (every tap on it is refused while the microphone is open).
    DisposableEffect(Unit) {
        onDispose {
            if (graph.voice.isRecording) graph.voice.cancelRecording()
            graph.voice.quiet()
        }
    }

    // Scoped to this route entry, not to the composition: a «Μίλα» detour and back resumes the same
    // items instead of asking the module for a fresh plan and losing his place.
    val vm: PracticeViewModel = viewModel(key = "practice-${moduleId.name}-${itemId ?: scriptId ?: ""}") {
        PracticeViewModel(graph, moduleId, itemId, scriptId)
    }
    val items by vm.items.collectAsStateWithLifecycle()
    val module = vm.module

    when (val list = items) {
        null -> DimitrisScreen { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium) }
        else -> if (module == null || list.isEmpty()) DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            // Every module lands here, and not all of them are made of words.
            Text("Δεν υπάρχει υλικό ακόμα. Ζήτα από κάποιον να προσθέσει.", style = MaterialTheme.typography.headlineMedium)
        // Free practice has nowhere to go next: finishing and leaving both pop back to Today.
        } else module.Screen(items = list, sessionId = null, onDone = onDone, onLeave = onDone)
    }
}
