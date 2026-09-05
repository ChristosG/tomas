package gr.dimitris.app.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen

/** Free practice of one module from the Today grid: same screen, no session row. */
@Composable
fun PracticeScreen(moduleId: ModuleId, onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    // Leaving practice stops whatever it was saying or playing, the same as leaving a session does.
    DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }

    // Null only if the route outlived the module registry; the empty screen is the answer either way.
    val module = remember(moduleId) { graph.modules.firstOrNull { it.id == moduleId } }
    var items by remember { mutableStateOf<List<Item>?>(null) }

    LaunchedEffect(moduleId) {
        items = module?.let { m -> runCatching { m.practiceFor(graph) }.getOrElse { graph.errors.record("practice ${m.id}", it); emptyList() } }
            ?: emptyList()
    }

    when (val list = items) {
        null -> DimitrisScreen { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium) }
        else -> if (module == null || list.isEmpty()) DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            // Every module lands here, and not all of them are made of words.
            Text("Δεν υπάρχει υλικό ακόμα. Ζήτα από κάποιον να προσθέσει.", style = MaterialTheme.typography.headlineMedium)
        // Free practice has nowhere to go next: finishing and leaving both pop back to Today.
        } else module.Screen(items = list, sessionId = null, onDone = onDone, onLeave = onDone)
    }
}
