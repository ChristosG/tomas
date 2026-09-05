package gr.dimitris.app.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val module = remember(moduleId) { graph.modules.first { it.id == moduleId } }
    var items by remember { mutableStateOf<List<Item>?>(null) }

    LaunchedEffect(moduleId) {
        items = runCatching { module.practiceFor(graph) }.getOrElse { graph.errors.record("practice ${module.id}", it); emptyList() }
    }

    when (val list = items) {
        null -> DimitrisScreen { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium) }
        else -> if (list.isEmpty()) DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            Text("Δεν υπάρχουν λέξεις ακόμα. Ζήτα από κάποιον να προσθέσει.", style = MaterialTheme.typography.headlineMedium)
        } else module.Screen(items = list, sessionId = null, onDone = onDone)
    }
}
